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
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StockMigrationTest extends IntegrationTestBase {

  private record Column(
      String dataType, String nullable, String defaultValue, Integer precision, Integer scale) {}

  @Test
  @DisplayName("a migration V16 cria a tabela product_stocks com as colunas do plano")
  void createsProductStocks() throws Exception {
    Map<String, Column> columns = columnsOf("product_stocks");

    assertThat(columns.keySet())
        .as("saldo sem created_at: o §5.3 define só updated_at")
        .containsExactlyInAnyOrderElementsOf(
            Set.of("id", "store_id", "product_id", "quantity", "updated_at", "version"));

    assertThat(columns.get("id").dataType()).as("id UUIDv7 gerado na aplicação").isEqualTo("uuid");
    assertThat(columns.get("store_id").nullable()).as("store_id obrigatório").isEqualTo("NO");
    assertThat(columns.get("product_id").nullable()).as("product_id obrigatório").isEqualTo("NO");
    assertThat(columns.get("quantity").dataType())
        .as("quantidade em numeric(14,3)")
        .isEqualTo("numeric");
    assertThat(columns.get("quantity").precision()).as("precisão de quantity").isEqualTo(14);
    assertThat(columns.get("quantity").scale()).as("escala de quantity").isEqualTo(3);
    assertThat(columns.get("quantity").nullable()).as("quantity obrigatório").isEqualTo("NO");
    assertThat(columns.get("quantity").defaultValue()).as("quantity nasce zerada").contains("0");
    assertThat(columns.get("updated_at").dataType())
        .as("datas em timestamptz (UTC)")
        .isEqualTo("timestamp with time zone");
    assertThat(columns.get("version").dataType())
        .as("version do lock otimista")
        .isEqualTo("bigint");
  }

  @Test
  @DisplayName("a migration V16 cria a tabela stock_movements com as colunas do plano")
  void createsStockMovements() throws Exception {
    Map<String, Column> columns = columnsOf("stock_movements");

    assertThat(columns.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "store_id",
                "product_id",
                "type",
                "quantity_delta",
                "balance_after",
                "unit_cost",
                "reference_type",
                "reference_id",
                "reason",
                "created_by_user_id",
                "created_at"));

    assertThat(columns.get("type").nullable()).as("type obrigatório").isEqualTo("NO");
    assertThat(columns.get("quantity_delta").precision())
        .as("delta com sinal em numeric(14,3)")
        .isEqualTo(14);
    assertThat(columns.get("quantity_delta").scale()).as("escala do delta").isEqualTo(3);
    assertThat(columns.get("quantity_delta").nullable()).as("delta obrigatório").isEqualTo("NO");
    assertThat(columns.get("balance_after").precision())
        .as("saldo após o movimento em numeric(14,3)")
        .isEqualTo(14);
    assertThat(columns.get("balance_after").scale()).as("escala do saldo").isEqualTo(3);
    assertThat(columns.get("balance_after").nullable())
        .as("balance_after obrigatório")
        .isEqualTo("NO");
    assertThat(columns.get("unit_cost").precision()).as("custo em numeric(14,2)").isEqualTo(14);
    assertThat(columns.get("unit_cost").scale()).as("escala do custo").isEqualTo(2);
    assertThat(columns.get("unit_cost").nullable())
        .as("custo só existe na entrada com custo informado")
        .isEqualTo("YES");
    assertThat(columns.get("reference_id").dataType()).as("reference_id uuid").isEqualTo("uuid");
    assertThat(columns.get("created_by_user_id").nullable())
        .as("created_by_user_id obrigatório")
        .isEqualTo("NO");
    assertThat(columns.get("created_at").dataType())
        .as("datas em timestamptz (UTC)")
        .isEqualTo("timestamp with time zone");
  }

  @Test
  @DisplayName("a migration V16 cria a unique do saldo e os 3 índices de consulta do ledger")
  void createsUniqueAndIndexes() throws Exception {
    assertThat(indexDefinition("ux_product_stocks_store_product"))
        .as("um saldo por produto/loja")
        .contains("UNIQUE")
        .contains("(store_id, product_id)");
    assertThat(indexDefinition("ix_stock_movements_product_created_at"))
        .as("histórico de um produto em ordem cronológica")
        .contains("(product_id, created_at DESC)");
    assertThat(indexDefinition("ix_stock_movements_reference"))
        .as("rastro do documento que originou o movimento")
        .contains("(reference_type, reference_id)");
    assertThat(indexDefinition("ix_stock_movements_store_created_at"))
        .as("movimentos da loja por período")
        .contains("(store_id, created_at DESC)");
  }

  @Test
  @DisplayName("dois saldos para o mesmo produto/loja: o segundo falha com SQLState 23505")
  void rejectsDuplicateStockRow() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String storeId = storeId(connection);
        String productId = insertProduct(connection, storeId);
        assertThatCode(() -> insertStock(connection, storeId, productId))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> insertStock(connection, storeId, productId))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação da unique (store_id, product_id)")
                        .isEqualTo("23505"));
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName(
      "o ledger é append-only: com minimarket_app o INSERT/SELECT funcionam e UPDATE/DELETE falham por permissão")
  void appRoleCanOnlyAppend() throws Exception {
    assertThat(roleCanLogin())
        .as("minimarket_app é role de aplicação, sem login próprio")
        .isFalse();

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String storeId = storeId(connection);
        String productId = insertProduct(connection, storeId);
        String userId = insertUser(connection);

        // SET LOCAL dentro da transação: ao dar rollback a sessão volta à role do pool.
        execute(connection, "set local role minimarket_app");

        String movementId = insertMovement(connection, storeId, productId, userId);
        assertThat(balanceAfterOf(connection, movementId))
            .as("movimento inserido é visível para a role")
            .isEqualByComparingTo("5.000");

        assertPermissionDenied(
            connection,
            "update stock_movements set reason = 'alterado' where id = cast('"
                + movementId
                + "' as uuid)");
        assertPermissionDenied(
            connection,
            "delete from stock_movements where id = cast('" + movementId + "' as uuid)");
      } finally {
        connection.rollback();
      }
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

  private boolean roleCanLogin() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select rolcanlogin from pg_roles where rolname = 'minimarket_app'");
        ResultSet resultSet = statement.executeQuery()) {
      assertThat(resultSet.next()).as("role minimarket_app existe").isTrue();
      return resultSet.getBoolean("rolcanlogin");
    }
  }

  private String storeId(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("select id from stores where code = 'MATRIZ'")) {
      assertThat(resultSet.next()).as("loja MATRIZ do seed da V1 presente").isTrue();
      return resultSet.getString("id");
    }
  }

  private String insertProduct(Connection connection, String storeId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into products (id, store_id, name, unit, price)"
                + " values (uuidv7(), cast(? as uuid), 'Produto de estoque', 'UN', 9.90)"
                + " returning id")) {
      statement.setString(1, storeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private String insertUser(Connection connection) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into users (id, username, password_hash, display_name, status)"
                + " values (uuidv7(), 'estoque.teste', 'hash', 'Operador de teste', 'ACTIVE')"
                + " returning id")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private void insertStock(Connection connection, String storeId, String productId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into product_stocks (id, store_id, product_id, quantity)"
                + " values (uuidv7(), cast(? as uuid), cast(? as uuid), 5.000)")) {
      statement.setString(1, storeId);
      statement.setString(2, productId);
      statement.executeUpdate();
    }
  }

  private String insertMovement(
      Connection connection, String storeId, String productId, String userId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into stock_movements"
                + " (id, store_id, product_id, type, quantity_delta, balance_after, unit_cost,"
                + " reason, created_by_user_id)"
                + " values (uuidv7(), cast(? as uuid), cast(? as uuid), 'PURCHASE_IN', 5.000,"
                + " 5.000, 9.90, 'Entrada de teste', cast(? as uuid))"
                + " returning id")) {
      statement.setString(1, storeId);
      statement.setString(2, productId);
      statement.setString(3, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private BigDecimal balanceAfterOf(Connection connection, String movementId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select balance_after from stock_movements where id = cast(? as uuid)")) {
      statement.setString(1, movementId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento inserido é visível para a role").isTrue();
        return resultSet.getBigDecimal(1);
      }
    }
  }

  private void assertPermissionDenied(Connection connection, String sql) throws Exception {
    // Sem savepoint, a primeira falha abortaria a transação e a segunda nem chegaria ao banco.
    Savepoint savepoint = connection.setSavepoint();
    try {
      assertThatThrownBy(() -> execute(connection, sql))
          .as("role da aplicação não pode alterar o ledger: %s", sql)
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("permission denied for table")
                      .isEqualTo("42501"));
    } finally {
      connection.rollback(savepoint);
    }
  }

  private void execute(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
