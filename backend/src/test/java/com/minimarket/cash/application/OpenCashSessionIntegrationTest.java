package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.application.AuthSessionStore;
import com.minimarket.auth.application.NewAuthSession;
import com.minimarket.auth.domain.SessionClient;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link OpenCashSessionUseCase} contra PostgreSQL real (Dev Services), no caixa
 * {@code CAIXA-01} do seed da V11.
 *
 * <p>Sem {@code @TestTransaction}: o caso de uso comita de verdade, como na API — o teste confere o
 * que ficou no banco por SQL (sessão, movimento, evento e o vínculo da sessão autenticada) e limpa
 * tudo o que comitou ao final, na ordem que as FKs exigem: movimentos → sessão → eventos → sessão
 * de auth → usuário. A fixture (operador e sessão autenticada) sai das portas, em transação própria
 * — o mesmo recurso do {@code CashSessionLockTest}.
 */
@QuarkusTest
class OpenCashSessionIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "caixa.abertura.606";

  @Inject OpenCashSessionUseCase useCase;

  @Inject UserStore userStore;

  @Inject AuthSessionStore authSessionStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID registerId;

  private UUID userId;

  private UUID authSessionId;

  private UUID cashSessionId;

  @Test
  @DisplayName(
      "abre o CAIXA-01 real: sessão OPEN, movimento OPENING, auditoria e vínculo do operador")
  void opensSeededRegister() throws SQLException {
    UUID store = storeId();
    userId = newOperator();
    authSessionId = newAuthSession(userId, store);
    UUID register = registerId();

    CashSessionSummary session =
        useCase.execute(
            new OpenCashSessionCommand(register, new BigDecimal("150.00"), userId, authSessionId));
    cashSessionId = session.id();

    assertThat(session.status()).isEqualTo(CashSessionStatus.OPEN);
    assertThat(session.storeId()).isEqualTo(store);
    assertThat(session.cashRegisterId()).isEqualTo(register);
    assertThat(session.openedByUserId()).isEqualTo(userId);
    assertThat(session.openingAmount()).isEqualByComparingTo("150.00");
    assertThat(session.closedAt()).as("nasce aberta, sem conferência").isNull();

    SessionRow stored = sessionRow(cashSessionId);
    assertThat(stored.status()).isEqualTo("OPEN");
    assertThat(stored.openingAmount()).isEqualTo("150.00");
    assertThat(stored.storeId()).isEqualTo(store);
    assertThat(stored.cashRegisterId()).isEqualTo(register);
    assertThat(stored.openedByUserId()).isEqualTo(userId);
    assertThat(stored.closedAt()).isNull();

    MovementRow movement = movementRow(cashSessionId);
    assertThat(movement.type()).isEqualTo("OPENING");
    assertThat(movement.amount()).as("abertura entra positiva").isEqualTo("150.00");
    assertThat(movement.createdByUserId()).isEqualTo(userId);
    assertThat(movement.createdAt())
        .as("o movimento nasce no mesmo instante da abertura, do relógio do caso de uso")
        .isEqualTo(stored.openedAt());
    assertThat(movement.paymentMethod()).isNull();
    assertThat(movement.reason()).isNull();

    Event event = eventOf(cashSessionId);
    assertThat(event.action()).isEqualTo("CASH_SESSION_OPENED");
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.actorUserId()).isNull();
    assertThat(event.cashRegisterId()).isEqualTo(register.toString());
    assertThat(event.openingAmount()).isEqualTo("150.00");

    assertThat(boundCashRegisterOf(authSessionId))
        .as("a sessão autenticada passa a apontar para o caixa aberto")
        .isEqualTo(register);
  }

  @Test
  @DisplayName("segunda abertura no mesmo caixa recusa com 409 CASH_REGISTER_ALREADY_OPEN")
  void rejectsSecondOpen() throws SQLException {
    userId = newOperator();
    UUID register = registerId();
    cashSessionId =
        useCase
            .execute(new OpenCashSessionCommand(register, new BigDecimal("100.00"), userId, null))
            .id();

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new OpenCashSessionCommand(register, new BigDecimal("50.00"), userId, null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_REGISTER_ALREADY_OPEN));
  }

  @Test
  @DisplayName("caixa desconhecido recusa com 404 CASH_REGISTER_NOT_FOUND sem gravar nada")
  void rejectsUnknownRegister() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new OpenCashSessionCommand(
                        UUID.randomUUID(), new BigDecimal("100.00"), UUID.randomUUID(), null)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_REGISTER_NOT_FOUND));
  }

  /** Operador de verdade: o {@code opened_by_user_id} da sessão tem FK para {@code users}. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de caixa", "hash", "ACTIVE")));
  }

  /** Sessão autenticada sem caixa, como a que a abertura vincula ao caixa. */
  private UUID newAuthSession(UUID operator, UUID store) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return callInOwnTransaction(
        () ->
            authSessionStore.insert(
                new NewAuthSession(
                    operator,
                    "hash-caixa-abertura-" + UUID.randomUUID(),
                    SessionClient.TUI,
                    store,
                    null,
                    null,
                    "terminal/1.0",
                    now,
                    now.plus(12, ChronoUnit.HOURS))));
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "delete from cash_movements where cash_session_id = ?", cashSessionId);
      execute(connection, "delete from cash_sessions where id = ?", cashSessionId);
      execute(connection, "delete from audit_events where entity_id = ?", cashSessionId);
      execute(connection, "delete from auth_sessions where id = ?", authSessionId);
      execute(connection, "delete from users where id = ?", userId);
    }
  }

  private static void execute(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  /** Sessão de caixa como o banco a guardou. */
  private SessionRow sessionRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, opening_amount::text as opening_amount, store_id, cash_register_id,"
                    + " opened_by_user_id, opened_at, closed_at from cash_sessions where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão de caixa %s gravada", id).isTrue();
        return new SessionRow(
            resultSet.getString("status"),
            resultSet.getString("opening_amount"),
            resultSet.getObject("store_id", UUID.class),
            resultSet.getObject("cash_register_id", UUID.class),
            resultSet.getObject("opened_by_user_id", UUID.class),
            resultSet.getTimestamp("opened_at").toInstant(),
            resultSet.getObject("closed_at"));
      }
    }
  }

  /** Movimento da sessão como o banco o guardou. */
  private MovementRow movementRow(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, amount::text as amount, payment_method, reason, created_by_user_id,"
                    + " created_at from cash_movements where cash_session_id = ?")) {
      statement.setObject(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento da sessão %s gravado", sessionId).isTrue();
        MovementRow movement =
            new MovementRow(
                resultSet.getString("type"),
                resultSet.getString("amount"),
                resultSet.getString("payment_method"),
                resultSet.getString("reason"),
                resultSet.getObject("created_by_user_id", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
        assertThat(resultSet.next()).as("a abertura grava um único movimento").isFalse();
        return movement;
      }
    }
  }

  /** Evento de auditoria da abertura; {@code details} vem extraído por chave do jsonb. */
  private Event eventOf(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action, entity_type, source, actor_user_id, details->>'cashRegisterId' as"
                    + " cash_register_id, details->>'openingAmount' as opening_amount"
                    + " from audit_events where entity_id = ?")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento gravado para a sessão %s", entityId).isTrue();
        return new Event(
            resultSet.getString("action"),
            resultSet.getString("entity_type"),
            resultSet.getString("source"),
            resultSet.getObject("actor_user_id", UUID.class),
            resultSet.getString("cash_register_id"),
            resultSet.getString("opening_amount"));
      }
    }
  }

  /** Caixa vinculado à sessão autenticada; nulo quando o vínculo não foi gravado. */
  private UUID boundCashRegisterOf(UUID authSessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select cash_register_id from auth_sessions where id = ?")) {
      statement.setObject(1, authSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão autenticada %s gravada", authSessionId).isTrue();
        return resultSet.getObject("cash_register_id", UUID.class);
      }
    }
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static <T> T callInOwnTransaction(Callable<T> work) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      return QuarkusTransaction.requiringNew().call(work);
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          callInOwnTransaction(
              () ->
                  storeLookup
                      .findByCode(defaultStoreCode)
                      .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
                      .id());
    }
    return storeId;
  }

  /** Id do caixa semeado pela V11; a leitura é por SQL porque a porta só busca por id. */
  private UUID registerId() throws SQLException {
    if (registerId == null) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "select id from cash_registers where code = 'CAIXA-01'")) {
        try (ResultSet resultSet = statement.executeQuery()) {
          assertThat(resultSet.next()).as("seed do CAIXA-01 da V11 presente").isTrue();
          registerId = resultSet.getObject("id", UUID.class);
        }
      }
    }
    return registerId;
  }

  /** Linha de {@code cash_sessions} como o banco a guardou. */
  private record SessionRow(
      String status,
      String openingAmount,
      UUID storeId,
      UUID cashRegisterId,
      UUID openedByUserId,
      Instant openedAt,
      Object closedAt) {}

  /** Linha de {@code cash_movements} como o banco a guardou. */
  private record MovementRow(
      String type,
      String amount,
      String paymentMethod,
      String reason,
      UUID createdByUserId,
      Instant createdAt) {}

  /** Linha de {@code audit_events} como o banco a guardou. */
  private record Event(
      String action,
      String entityType,
      String source,
      UUID actorUserId,
      String cashRegisterId,
      String openingAmount) {}
}
