package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garantias da migration V20 (passo 901): a tabela {@code payments} existe com as colunas do §5.3,
 * o índice por venda está no banco e o banco recusa valor não positivo, forma desconhecida e a
 * exclusão da venda que ainda tem pagamento (o teste falha se a garantia não existir).
 *
 * <p>As fixtures (loja MATRIZ e CAIXA-01 dos seeds, usuário, sessão de caixa e venda) são comitadas
 * de propósito: cada {@code INSERT} problemático roda em transação própria e o {@link
 * #removeFixtures()} limpa tudo na ordem das FKs — {@code payments} → {@code sales} → {@code
 * cash_sessions} → {@code users}.
 */
@QuarkusTest
class PaymentsMigrationTest extends IntegrationTestBase {

  /** Usuário das fixtures; fora dos sufixos dos outros testes para não colidir. */
  private static final String USERNAME = "pagamentos.teste";

  private record Column(
      String dataType, String nullable, String defaultValue, Integer precision, Integer scale) {}

  private String userId;
  private String sessionId;
  private String saleId;

  @AfterEach
  void removeFixtures() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      if (saleId != null) {
        execute(connection, "delete from payments where sale_id = cast(? as uuid)", saleId);
        execute(connection, "delete from sales where id = cast(? as uuid)", saleId);
      }
      if (sessionId != null) {
        execute(connection, "delete from cash_sessions where id = cast(? as uuid)", sessionId);
      }
      if (userId != null) {
        execute(connection, "delete from users where id = cast(? as uuid)", userId);
      }
    }
  }

  @Test
  @DisplayName("a migration V20 cria payments com as colunas do plano")
  void createsPayments() throws Exception {
    Map<String, Column> columns = columnsOf("payments");

    assertThat(columns.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "sale_id",
                "method",
                "amount",
                "tendered_amount",
                "change_amount",
                "status",
                "external_ref",
                "authorization_code",
                "created_by_user_id",
                "created_at",
                "cancelled_at",
                "cancel_reason"));

    assertThat(columns.get("id").dataType())
        .as("id em uuid (UUIDv7 da aplicação)")
        .isEqualTo("uuid");
    assertThat(columns.get("sale_id").nullable())
        .as("pagamento sempre pertence a uma venda")
        .isEqualTo("NO");
    assertThat(columns.get("method").nullable())
        .as("forma de pagamento obrigatória")
        .isEqualTo("NO");
    assertThat(columns.get("amount").precision()).as("dinheiro em numeric(14,2)").isEqualTo(14);
    assertThat(columns.get("amount").scale()).as("escala do dinheiro").isEqualTo(2);
    assertThat(columns.get("amount").nullable()).as("valor obrigatório").isEqualTo("NO");
    assertThat(columns.get("tendered_amount").nullable())
        .as("tendered só existe em dinheiro (BR-05)")
        .isEqualTo("YES");
    assertThat(columns.get("change_amount").nullable())
        .as("troco só existe em dinheiro (BR-05)")
        .isEqualTo("YES");
    assertThat(columns.get("status").defaultValue())
        .as("pagamento nasce aprovado")
        .contains("APPROVED");
    assertThat(columns.get("created_at").dataType())
        .as("datas em timestamptz (UTC)")
        .isEqualTo("timestamp with time zone");
    assertThat(columns.get("cancelled_at").dataType())
        .as("cancelamento só preenche cancelled_at, sem updated_at")
        .isEqualTo("timestamp with time zone");
    assertThat(columns.get("created_by_user_id").nullable())
        .as("pagamento sempre tem autor")
        .isEqualTo("NO");
  }

  @Test
  @DisplayName("a migration V20 cria o índice de pagamentos por venda")
  void createsSaleIndex() throws Exception {
    assertThat(indexDefinition("payments_pkey")).as("chave primária de payments").contains("(id)");
    assertThat(indexDefinition("ix_payments_sale"))
        .as("pagamentos de uma venda")
        .contains("(sale_id)");
  }

  @Test
  @DisplayName("valor zero: o banco recusa com SQLState 23514 (amount > 0)")
  void rejectsZeroAmount() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 9901L);

      assertThatThrownBy(
              () -> createPayment(connection, saleId, userId, "CASH", new BigDecimal("0.00")))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação do check amount > 0")
                      .isEqualTo("23514"));
    }
  }

  @Test
  @DisplayName("forma de pagamento desconhecida: o banco recusa com SQLState 23514")
  void rejectsUnknownMethod() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 9901L);

      assertThatThrownBy(
              () -> createPayment(connection, saleId, userId, "BOLETO", new BigDecimal("10.00")))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação do check de method")
                      .isEqualTo("23514"));
    }
  }

  @Test
  @DisplayName("pagamento sem status nasce APPROVED e guarda os valores em numeric(14,2)")
  void defaultsStatusToApproved() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 9901L);

      createPayment(connection, saleId, userId, "CASH", new BigDecimal("50.00"));

      try (PreparedStatement statement =
          connection.prepareStatement(
              "select method, amount, status from payments where sale_id = cast(? as uuid)")) {
        statement.setString(1, saleId);
        try (ResultSet resultSet = statement.executeQuery()) {
          assertThat(resultSet.next()).as("pagamento inserido").isTrue();
          assertThat(resultSet.getString("method")).as("forma gravada").isEqualTo("CASH");
          assertThat(resultSet.getBigDecimal("amount"))
              .as("valor gravado")
              .isEqualByComparingTo("50.00");
          assertThat(resultSet.getString("status"))
              .as("default APPROVED da migration")
              .isEqualTo("APPROVED");
        }
      }
    }
  }

  @Test
  @DisplayName("apagar a venda com pagamento falha com SQLState 23001 (on delete restrict)")
  void restrictsSaleDeletion() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 9901L);
      createPayment(connection, saleId, userId, "PIX", new BigDecimal("10.00"));

      assertThatThrownBy(
              () -> execute(connection, "delete from sales where id = cast(? as uuid)", saleId))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("restrict_violation da FK payments.sale_id (on delete restrict)")
                      .isEqualTo("23001"));
    }
  }

  private Map<String, Column> columnsOf(String table) throws Exception {
    Map<String, Column> columns = new HashMap<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select column_name, data_type, is_nullable, column_default, numeric_precision,"
                    + " numeric_scale from information_schema.columns"
                    + " where table_schema = 'public' and table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          columns.put(
              resultSet.getString("column_name"),
              new Column(
                  resultSet.getString("data_type"),
                  resultSet.getString("is_nullable"),
                  resultSet.getString("column_default"),
                  resultSet.getObject("numeric_precision", Integer.class),
                  resultSet.getObject("numeric_scale", Integer.class)));
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

  private String createUser(Connection connection) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into users (id, username, password_hash, display_name, status)"
                + " values (uuidv7(), ?, 'hash', 'Operador de pagamento', 'ACTIVE') returning id")) {
      statement.setString(1, USERNAME);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private String openCashSession(Connection connection, String operatorId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into cash_sessions"
                + " (id, store_id, cash_register_id, status, opened_by_user_id, opened_at,"
                + " opening_amount)"
                + " values (uuidv7(), (select id from stores where code = 'MATRIZ'),"
                + " (select id from cash_registers where code = 'CAIXA-01'), 'OPEN',"
                + " cast(? as uuid), now(), 100.00)"
                + " returning id")) {
      statement.setString(1, operatorId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private String createSale(
      Connection connection, String cashSessionId, String operatorId, long number)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into sales"
                + " (id, store_id, number, cash_session_id, cash_register_id, operator_user_id,"
                + " status)"
                + " values (uuidv7(), (select id from stores where code = 'MATRIZ'), ?,"
                + " cast(? as uuid), (select cash_register_id from cash_sessions where id ="
                + " cast(? as uuid)), cast(? as uuid), 'OPEN')"
                + " returning id")) {
      statement.setLong(1, number);
      statement.setString(2, cashSessionId);
      statement.setString(3, cashSessionId);
      statement.setString(4, operatorId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private void createPayment(
      Connection connection, String saleId, String userId, String method, BigDecimal amount)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into payments (id, sale_id, method, amount, created_by_user_id)"
                + " values (uuidv7(), cast(? as uuid), ?, ?, cast(? as uuid))")) {
      statement.setString(1, saleId);
      statement.setString(2, method);
      statement.setBigDecimal(3, amount);
      statement.setString(4, userId);
      statement.executeUpdate();
    }
  }

  private void execute(Connection connection, String sql, String parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      statement.executeUpdate();
    }
  }
}
