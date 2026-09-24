package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.domain.CashMovementType;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link RecordWithdrawalUseCase} contra PostgreSQL real (Dev Services): abre o
 * {@code CAIXA-01} do seed da V11 pelo {@link OpenCashSessionUseCase} de verdade e sangra por cima
 * da sessão comitada, provando o caminho completo — o esperado antes/depois lido dos totais que o
 * banco somou (com o movimento recém-inserido aparecendo na releitura) e o evento de auditoria
 * gravado na mesma transação.
 *
 * <p>Sem {@code @TestTransaction}: os casos de uso comitam de verdade, como na API — o teste
 * confere por SQL o que ficou no banco (movimento negativo e evento) e limpa tudo o que comitou ao
 * final, na ordem que as FKs exigem: movimentos → sessão → eventos → usuário. A fixture (operador)
 * sai da porta, em transação própria.
 */
@QuarkusTest
class RecordWithdrawalIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "caixa.sangria.609";

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject RecordWithdrawalUseCase recordWithdrawalUseCase;

  @Inject UserStore userStore;

  private UUID userId;

  private UUID cashSessionId;

  @Test
  @DisplayName(
      "sangra a sessão aberta do CAIXA-01: movimento negativo, esperado antes/depois e auditoria")
  void recordsWithdrawalOnOpenSession() throws SQLException {
    userId = newOperator();
    UUID register = cashRegisterId("CAIXA-01");
    cashSessionId = openCashSession(register, "100.00");

    CashMovementResult result =
        recordWithdrawalUseCase.execute(
            new RecordWithdrawalCommand(
                register, new BigDecimal("250.00"), "depósito bancário", userId));

    assertThat(result.sessionId()).isEqualTo(cashSessionId);
    assertThat(result.type()).isEqualTo(CashMovementType.WITHDRAWAL);
    assertThat(result.amount()).isEqualByComparingTo("250.00");
    assertThat(result.reason()).isEqualTo("depósito bancário");
    assertThat(result.expectedBefore())
        .as("só a abertura entra no esperado; o movimento OPENING do ledger não conta duas vezes")
        .isEqualByComparingTo("100.00");
    assertThat(result.expectedAfter()).isEqualByComparingTo("-150.00");
    assertThat(result.aboveExpected()).as("250 > 100: alerta, mas a sangria é registrada").isTrue();

    MovementRow movement = withdrawalRow();
    assertThat(movement.type()).isEqualTo("WITHDRAWAL");
    assertThat(movement.amount()).as("sangria entra negativa no ledger").isEqualTo("-250.00");
    assertThat(movement.reason()).isEqualTo("depósito bancário");
    assertThat(movement.createdByUserId()).isEqualTo(userId);
    assertThat(movement.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));

    Event event = withdrawalEvent();
    assertThat(event.action()).isEqualTo("CASH_WITHDRAWAL");
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.reason()).isEqualTo("depósito bancário");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.amount()).isEqualTo("250.00");
    assertThat(event.expectedBefore()).isEqualTo("100.00");
    assertThat(event.expectedAfter()).isEqualTo("-150.00");
    assertThat(event.aboveExpected()).isEqualTo("true");
  }

  /** Operador de verdade: o {@code created_by_user_id} do movimento tem FK para {@code users}. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de sangria", "hash", "ACTIVE")));
  }

  /** Abre o caixa pelo caso de uso de verdade (passo 606) e devolve o id da sessão comitada. */
  private UUID openCashSession(UUID register, String openingAmount) {
    return openCashSessionUseCase
        .execute(new OpenCashSessionCommand(register, new BigDecimal(openingAmount), userId, null))
        .id();
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "delete from cash_movements where cash_session_id = ?", cashSessionId);
      execute(connection, "delete from cash_sessions where id = ?", cashSessionId);
      execute(connection, "delete from audit_events where entity_id = ?", cashSessionId);
      execute(connection, "delete from users where id = ?", userId);
    }
  }

  private static void execute(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  /** Movimento da sangria como o banco o guardou; a sessão tem exatamente um. */
  private MovementRow withdrawalRow() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, amount::text as amount, reason, created_by_user_id, created_at"
                    + " from cash_movements where cash_session_id = ? and type = 'WITHDRAWAL'")) {
      statement.setObject(1, cashSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento da sangria gravado").isTrue();
        MovementRow movement =
            new MovementRow(
                resultSet.getString("type"),
                resultSet.getString("amount"),
                resultSet.getString("reason"),
                resultSet.getObject("created_by_user_id", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
        assertThat(resultSet.next()).as("a sessão tem uma única sangria").isFalse();
        return movement;
      }
    }
  }

  /** Evento da sangria; {@code details} vem extraído por chave do jsonb. */
  private Event withdrawalEvent() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action, entity_type, source, reason, details->>'amount' as amount,"
                    + " details->>'expectedBefore' as expected_before,"
                    + " details->>'expectedAfter' as expected_after,"
                    + " details->>'aboveExpected' as above_expected"
                    + " from audit_events where entity_id = ? and action = 'CASH_WITHDRAWAL'")) {
      statement.setObject(1, cashSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento CASH_WITHDRAWAL gravado para a sessão").isTrue();
        Event event =
            new Event(
                resultSet.getString("action"),
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("reason"),
                resultSet.getString("amount"),
                resultSet.getString("expected_before"),
                resultSet.getString("expected_after"),
                resultSet.getString("above_expected"));
        assertThat(resultSet.next()).as("um evento por sangria").isFalse();
        return event;
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

  /** Linha de {@code cash_movements} como o banco a guardou. */
  private record MovementRow(
      String type, String amount, String reason, UUID createdByUserId, Instant createdAt) {}

  /** Linha de {@code audit_events} como o banco a guardou. */
  private record Event(
      String action,
      String entityType,
      String source,
      String reason,
      String amount,
      String expectedBefore,
      String expectedAfter,
      String aboveExpected) {}
}
