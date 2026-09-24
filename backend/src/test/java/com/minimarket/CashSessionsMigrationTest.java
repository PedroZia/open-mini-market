package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CashSessionsMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V12 cria a tabela cash_sessions com as colunas do plano")
  void createsCashSessions() throws Exception {
    assertThat(columnsOf("cash_sessions"))
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "store_id",
                "cash_register_id",
                "status",
                "opened_by_user_id",
                "opened_at",
                "opening_amount",
                "closed_by_user_id",
                "closed_at",
                "counted_amount",
                "expected_amount",
                "difference_amount",
                "closing_notes",
                "created_at",
                "updated_at",
                "version"));
  }

  @Test
  @DisplayName("a migration V13 cria a tabela cash_movements com as colunas do plano")
  void createsCashMovements() throws Exception {
    assertThat(columnsOf("cash_movements"))
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "store_id",
                "cash_session_id",
                "type",
                "amount",
                "payment_method",
                "reference_type",
                "reference_id",
                "reason",
                "created_by_user_id",
                "created_at"));
  }

  @Test
  @DisplayName("a migration V12 cria o índice único parcial de sessão aberta por caixa")
  void createsOpenSessionIndex() throws Exception {
    assertThat(indexDefinition("ux_cash_session_open"))
        .as("índice único restrito às sessões abertas")
        .contains("UNIQUE")
        .contains("(cash_register_id)")
        .contains("status = 'OPEN'");
  }

  @Test
  @DisplayName("a migration V13 cria o índice de movimentos por sessão e criação")
  void createsMovementsIndex() throws Exception {
    assertThat(indexDefinition("ix_cash_movements_session_created_at"))
        .as("índice de leitura cronológica dos movimentos")
        .contains("(cash_session_id, created_at)");
  }

  @Test
  @DisplayName("duas sessões abertas no mesmo caixa: a segunda falha com SQLState 23505")
  void rejectsSecondOpenSessionOnTheSameRegister() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String userId = insertTestUser(connection);
        insertSession(connection, userId, new BigDecimal("100.00"));
        assertThatThrownBy(() -> insertSession(connection, userId, new BigDecimal("50.00")))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação do índice único parcial de sessão aberta")
                        .isEqualTo("23505"));
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("sessão fechada libera o caixa: a próxima abertura é aceita")
  void allowsNewOpenAfterClosing() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String userId = insertTestUser(connection);
        String firstSessionId = insertSession(connection, userId, new BigDecimal("100.00"));
        closeSession(connection, firstSessionId);
        assertThatCode(() -> insertSession(connection, userId, new BigDecimal("80.00")))
            .doesNotThrowAnyException();
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("opening_amount negativo é rejeitado com SQLState 23514")
  void rejectsNegativeOpeningAmount() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String userId = insertTestUser(connection);
        assertThatThrownBy(() -> insertSession(connection, userId, new BigDecimal("-0.01")))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação do check de opening_amount")
                        .isEqualTo("23514"));
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("type inválido em cash_movements é rejeitado com SQLState 23514")
  void rejectsInvalidMovementType() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String userId = insertTestUser(connection);
        String sessionId = insertSession(connection, userId, new BigDecimal("100.00"));
        assertThatThrownBy(() -> insertMovement(connection, sessionId, userId, "TRANSFER"))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação do check de type")
                        .isEqualTo("23514"));
      } finally {
        connection.rollback();
      }
    }
  }

  private Set<String> columnsOf(String tableName) throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = ?")) {
      statement.setString(1, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          columns.add(resultSet.getString("column_name"));
        }
      }
    }
    return columns;
  }

  private String indexDefinition(String indexName) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?")) {
      statement.setString(1, indexName);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("índice %s existe", indexName).isTrue();
        return resultSet.getString("indexdef");
      }
    }
  }

  private String insertTestUser(Connection connection) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into users (id, username, password_hash, display_name, status)"
                + " values (uuidv7(), 'caixa.teste', 'hash', 'Operador de teste', 'ACTIVE')"
                + " returning id")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private String insertSession(Connection connection, String userId, BigDecimal openingAmount)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into cash_sessions"
                + " (id, store_id, cash_register_id, status, opened_by_user_id, opened_at,"
                + " opening_amount)"
                + " values (uuidv7(), (select id from stores where code = 'MATRIZ'),"
                + " (select id from cash_registers where code = 'CAIXA-01'), 'OPEN',"
                + " cast(? as uuid), now(), ?)"
                + " returning id")) {
      statement.setString(1, userId);
      statement.setBigDecimal(2, openingAmount);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private void closeSession(Connection connection, String sessionId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "update cash_sessions set status = 'CLOSED', closed_by_user_id = opened_by_user_id,"
                + " closed_at = now(), counted_amount = 100.00, expected_amount = 100.00,"
                + " difference_amount = 0.00 where id = cast(? as uuid)")) {
      statement.setString(1, sessionId);
      assertThat(statement.executeUpdate()).as("a sessão foi fechada").isEqualTo(1);
    }
  }

  private void insertMovement(Connection connection, String sessionId, String userId, String type)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into cash_movements"
                + " (id, store_id, cash_session_id, type, amount, created_by_user_id)"
                + " values (uuidv7(), (select id from stores where code = 'MATRIZ'),"
                + " cast(? as uuid), ?, 10.00, cast(? as uuid))")) {
      statement.setString(1, sessionId);
      statement.setString(2, type);
      statement.setString(3, userId);
      statement.executeUpdate();
    }
  }
}
