package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
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
 * Integração do {@link CloseCashSessionUseCase} contra PostgreSQL real (Dev Services): abre o
 * {@code CAIXA-01} do seed da V11, sangra e fecha pelo caminho completo — o esperado lido dos
 * movimentos que o banco somou, a conferência gravada na linha da sessão e o evento de auditoria na
 * mesma transação.
 *
 * <p>Sem {@code @TestTransaction}: os casos de uso comitam de verdade, como na API — o teste
 * confere por SQL o que ficou no banco (status, contado, esperado, diferença, quem fechou, quando,
 * e o evento) e limpa tudo o que comitou ao final, na ordem que as FKs exigem: movimentos → sessões
 * → eventos → usuário. A fixture (operador) sai da porta, em transação própria.
 */
@QuarkusTest
class CloseCashSessionIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "caixa.fechamento.611";

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject RecordWithdrawalUseCase recordWithdrawalUseCase;

  @Inject CloseCashSessionUseCase closeCashSessionUseCase;

  @Inject UserStore userStore;

  private UUID userId;

  private UUID cashSessionId;

  @Test
  @DisplayName(
      "fecha o CAIXA-01: esperado 70 com sangria, contado 60, diferença −10 e auditoria do fechamento")
  void closesSessionWithShortage() throws SQLException {
    userId = newOperator();
    UUID register = cashRegisterId("CAIXA-01");
    cashSessionId = openCashSession(register, "100.00");
    withdraw(register, "30.00");

    CashSessionSummary closed =
        closeCashSessionUseCase.execute(
            new CloseCashSessionCommand(
                register, new BigDecimal("60.00"), "conferência do turno", userId));

    assertThat(closed.id()).isEqualTo(cashSessionId);
    assertThat(closed.status()).isEqualTo(CashSessionStatus.CLOSED);
    assertThat(closed.countedAmount()).isEqualByComparingTo("60.00");
    assertThat(closed.expectedAmount())
        .as("abertura 100 − sangria 30; o OPENING do ledger não conta duas vezes")
        .isEqualByComparingTo("70.00");
    assertThat(closed.differenceAmount()).isEqualByComparingTo("-10.00");
    assertThat(closed.closedByUserId()).isEqualTo(userId);
    assertThat(closed.closedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    assertThat(closed.closingNotes()).isEqualTo("conferência do turno");
    assertThat(closed.version())
        .as("o flush do adaptador já grava a conferência antes de devolver a projeção")
        .isEqualTo(1L);

    SessionRow stored = closedSessionRow();
    assertThat(stored.status()).isEqualTo("CLOSED");
    assertThat(stored.countedAmount()).isEqualTo("60.00");
    assertThat(stored.expectedAmount()).isEqualTo("70.00");
    assertThat(stored.differenceAmount()).isEqualTo("-10.00");
    assertThat(stored.closingNotes()).isEqualTo("conferência do turno");
    assertThat(stored.closedByUserId()).isEqualTo(userId);
    assertThat(stored.closedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    assertThat(stored.version()).isEqualTo(1L);

    Event event = closeEvent();
    assertThat(event.action()).isEqualTo("CASH_SESSION_CLOSED");
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.countedAmount()).isEqualTo("60.00");
    assertThat(event.expectedAmount()).isEqualTo("70.00");
    assertThat(event.differenceAmount()).isEqualTo("-10.00");

    assertThatThrownBy(
            () ->
                closeCashSessionUseCase.execute(
                    new CloseCashSessionCommand(register, new BigDecimal("60.00"), null, userId)))
        .as("fechar duas vezes é conflito de estado, não um segundo fechamento")
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_ALREADY_CLOSED));
  }

  /** Operador de verdade: o {@code closed_by_user_id} da sessão tem FK para {@code users}. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de fechamento", "hash", "ACTIVE")));
  }

  /** Abre o caixa pelo caso de uso de verdade (passo 606) e devolve o id da sessão comitada. */
  private UUID openCashSession(UUID register, String openingAmount) {
    return openCashSessionUseCase
        .execute(new OpenCashSessionCommand(register, new BigDecimal(openingAmount), userId, null))
        .id();
  }

  /**
   * Sangra pelo caso de uso de verdade (passo 609): o esperado do fechamento reflete o movimento.
   */
  private void withdraw(UUID register, String amount) {
    recordWithdrawalUseCase.execute(
        new RecordWithdrawalCommand(register, new BigDecimal(amount), "depósito bancário", userId));
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

  /** Sessão fechada como o banco a guardou; a linha é a única do cenário. */
  private SessionRow closedSessionRow() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, counted_amount::text as counted_amount,"
                    + " expected_amount::text as expected_amount,"
                    + " difference_amount::text as difference_amount, closing_notes,"
                    + " closed_by_user_id, closed_at, version"
                    + " from cash_sessions where id = ?")) {
      statement.setObject(1, cashSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão de caixa %s gravada", cashSessionId).isTrue();
        return new SessionRow(
            resultSet.getString("status"),
            resultSet.getString("counted_amount"),
            resultSet.getString("expected_amount"),
            resultSet.getString("difference_amount"),
            resultSet.getString("closing_notes"),
            resultSet.getObject("closed_by_user_id", UUID.class),
            resultSet.getTimestamp("closed_at").toInstant(),
            resultSet.getLong("version"));
      }
    }
  }

  /** Evento do fechamento; {@code details} vem extraído por chave do jsonb. */
  private Event closeEvent() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action, entity_type, source, details->>'countedAmount' as counted_amount,"
                    + " details->>'expectedAmount' as expected_amount,"
                    + " details->>'differenceAmount' as difference_amount"
                    + " from audit_events where entity_id = ? and action = 'CASH_SESSION_CLOSED'")) {
      statement.setObject(1, cashSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("evento CASH_SESSION_CLOSED gravado para a sessão")
            .isTrue();
        Event event =
            new Event(
                resultSet.getString("action"),
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("counted_amount"),
                resultSet.getString("expected_amount"),
                resultSet.getString("difference_amount"));
        assertThat(resultSet.next()).as("um evento por fechamento").isFalse();
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

  /** Linha de {@code cash_sessions} como o banco a guardou depois do fechamento. */
  private record SessionRow(
      String status,
      String countedAmount,
      String expectedAmount,
      String differenceAmount,
      String closingNotes,
      UUID closedByUserId,
      Instant closedAt,
      long version) {}

  /** Linha de {@code audit_events} como o banco a guardou. */
  private record Event(
      String action,
      String entityType,
      String source,
      String countedAmount,
      String expectedAmount,
      String differenceAmount) {}
}
