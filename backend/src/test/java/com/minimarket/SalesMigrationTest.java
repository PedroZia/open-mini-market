package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garantias das migrations de venda (V17–V19, passo 801): as três tabelas existem com as colunas do
 * §5.3, os índices e uniques do plano estão no banco e o banco recusa número de venda e posição de
 * item duplicados (o teste falha se a garantia não existir).
 *
 * <p>As fixtures (loja MATRIZ e CAIXA-01 dos seeds, usuário e sessão de caixa) são comitadas de
 * propósito: cada {@code INSERT} problemático roda em transação própria e o {@link
 * #removeFixtures()} limpa tudo na ordem das FKs — {@code sale_items} → {@code sales} → {@code
 * products} → {@code cash_sessions} → {@code users}.
 */
@QuarkusTest
class SalesMigrationTest extends IntegrationTestBase {

  /** Usuário das fixtures; fora dos sufixos dos outros testes para não colidir. */
  private static final String USERNAME = "vendas.teste";

  private record Column(
      String dataType, String nullable, String defaultValue, Integer precision, Integer scale) {}

  private String userId;
  private String sessionId;
  private String saleId;
  private String productId;

  @AfterEach
  void removeFixtures() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      if (saleId != null) {
        execute(connection, "delete from sale_items where sale_id = cast(? as uuid)", saleId);
        execute(connection, "delete from sales where id = cast(? as uuid)", saleId);
      }
      if (productId != null) {
        execute(connection, "delete from products where id = cast(? as uuid)", productId);
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
  @DisplayName("a migration V17 cria document_sequences com as colunas do plano")
  void createsDocumentSequences() throws Exception {
    Map<String, Column> columns = columnsOf("document_sequences");

    assertThat(columns.keySet())
        .as("sequência sem created_at: o §5.3 define só updated_at")
        .containsExactlyInAnyOrderElementsOf(
            Set.of("store_id", "doc_type", "next_value", "updated_at"));

    assertThat(columns.get("store_id").nullable()).as("store_id obrigatório").isEqualTo("NO");
    assertThat(columns.get("doc_type").nullable()).as("doc_type obrigatório").isEqualTo("NO");
    assertThat(columns.get("next_value").dataType()).as("contador em bigint").isEqualTo("bigint");
    assertThat(columns.get("next_value").defaultValue()).as("primeiro número é 1").contains("1");
    assertThat(columns.get("updated_at").dataType())
        .as("datas em timestamptz (UTC)")
        .isEqualTo("timestamp with time zone");

    assertThat(indexDefinition("document_sequences_pkey"))
        .as("uma sequência por loja e tipo de documento")
        .contains("(store_id, doc_type)");
  }

  @Test
  @DisplayName("a migration V18 cria sales com as colunas do plano")
  void createsSales() throws Exception {
    Map<String, Column> columns = columnsOf("sales");

    assertThat(columns.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "store_id",
                "number",
                "cash_session_id",
                "cash_register_id",
                "customer_id",
                "operator_user_id",
                "status",
                "subtotal",
                "discount_type",
                "discount_value",
                "discount_amount",
                "discount_reason",
                "discount_authorized_by_user_id",
                "total",
                "paid_amount",
                "change_amount",
                "item_count",
                "notes",
                "created_at",
                "updated_at",
                "completed_at",
                "cancelled_at",
                "cancelled_by_user_id",
                "cancel_reason",
                "version"));

    assertThat(columns.get("number").dataType())
        .as("número sequencial em bigint")
        .isEqualTo("bigint");
    assertThat(columns.get("number").nullable()).as("number obrigatório").isEqualTo("NO");
    assertThat(columns.get("customer_id").nullable())
        .as("venda sem cliente é válida")
        .isEqualTo("YES");
    assertThat(columns.get("subtotal").precision()).as("dinheiro em numeric(14,2)").isEqualTo(14);
    assertThat(columns.get("subtotal").scale()).as("escala do dinheiro").isEqualTo(2);
    assertThat(columns.get("total").defaultValue()).as("total nasce zerado").contains("0");
    assertThat(columns.get("completed_at").dataType())
        .as("datas em timestamptz (UTC)")
        .isEqualTo("timestamp with time zone");
    assertThat(columns.get("version").dataType())
        .as("version do lock otimista")
        .isEqualTo("bigint");
  }

  @Test
  @DisplayName("a migration V19 cria sale_items com as colunas do plano")
  void createsSaleItems() throws Exception {
    Map<String, Column> columns = columnsOf("sale_items");

    assertThat(columns.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "sale_id",
                "line_number",
                "product_id",
                "barcode_snapshot",
                "name_snapshot",
                "unit_snapshot",
                "unit_price",
                "quantity",
                "discount_amount",
                "line_total",
                "created_at",
                "updated_at"));

    assertThat(columns.get("line_number").dataType())
        .as("posição do item na venda")
        .isEqualTo("integer");
    assertThat(columns.get("name_snapshot").nullable())
        .as("snapshot do nome é obrigatório (BR-01)")
        .isEqualTo("NO");
    assertThat(columns.get("barcode_snapshot").nullable())
        .as("produto sem código de barras tem snapshot nulo")
        .isEqualTo("YES");
    assertThat(columns.get("unit_price").precision()).as("preço em numeric(14,2)").isEqualTo(14);
    assertThat(columns.get("quantity").precision()).as("quantidade em numeric(14,3)").isEqualTo(14);
    assertThat(columns.get("quantity").scale()).as("escala da quantidade").isEqualTo(3);
  }

  @Test
  @DisplayName("as migrations V18/V19 criam as uniques e os índices de consulta do plano")
  void createsUniquesAndIndexes() throws Exception {
    assertThat(indexDefinition("ux_sales_store_number"))
        .as("número da venda único por loja")
        .contains("UNIQUE")
        .contains("(store_id, number)");
    assertThat(indexDefinition("ix_sales_cash_session"))
        .as("vendas por sessão de caixa")
        .contains("(cash_session_id)");
    assertThat(indexDefinition("ix_sales_store_created_at"))
        .as("histórico da loja em ordem cronológica")
        .contains("(store_id, created_at DESC)");
    assertThat(indexDefinition("ix_sales_operator_created_at"))
        .as("vendas por operador em ordem cronológica")
        .contains("(operator_user_id, created_at DESC)");
    assertThat(indexDefinition("ix_sales_store_open"))
        .as("índice parcial das vendas abertas")
        .contains("(store_id)")
        .contains("status = 'OPEN'");
    assertThat(indexDefinition("ux_sale_items_sale_line_number"))
        .as("posição do item única dentro da venda")
        .contains("UNIQUE")
        .contains("(sale_id, line_number)");
    assertThat(indexDefinition("ix_sale_items_product"))
        .as("rastro do produto no histórico de vendas")
        .contains("(product_id)");
  }

  @Test
  @DisplayName("duas vendas com o mesmo número na mesma loja: a segunda falha com SQLState 23505")
  void rejectsDuplicateSaleNumber() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 1L);

      assertThatThrownBy(() -> createSale(connection, sessionId, userId, 1L))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação da unique (store_id, number)")
                      .isEqualTo("23505"));
    }
  }

  @Test
  @DisplayName("dois itens na mesma posição da venda: o segundo falha com SQLState 23505")
  void rejectsDuplicateLineNumberInSale() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 1L);
      productId = createProduct(connection, storeId(connection));

      createItem(connection, saleId, productId, 1);

      assertThatThrownBy(() -> createItem(connection, saleId, productId, 1))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação da unique (sale_id, line_number)")
                      .isEqualTo("23505"));
    }
  }

  @Test
  @DisplayName("apagar a venda apaga os itens: a única cascata do projeto")
  void deletesItemsWithSale() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      userId = createUser(connection);
      sessionId = openCashSession(connection, userId);
      saleId = createSale(connection, sessionId, userId, 1L);
      productId = createProduct(connection, storeId(connection));
      createItem(connection, saleId, productId, 1);

      execute(connection, "delete from sales where id = cast(? as uuid)", saleId);

      assertThat(countItemsOfSale(connection, saleId))
          .as("sale_items.sale_id é on delete cascade")
          .isZero();
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

  private String storeId(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("select id from stores where code = 'MATRIZ'")) {
      assertThat(resultSet.next()).as("loja MATRIZ do seed da V1 presente").isTrue();
      return resultSet.getString("id");
    }
  }

  private String createUser(Connection connection) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into users (id, username, password_hash, display_name, status)"
                + " values (uuidv7(), ?, 'hash', 'Operador de venda', 'ACTIVE') returning id")) {
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

  private String createProduct(Connection connection, String storeId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into products (id, store_id, name, unit, price)"
                + " values (uuidv7(), cast(? as uuid), 'Produto de venda', 'UN', 9.90)"
                + " returning id")) {
      statement.setString(1, storeId);
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

  private void createItem(Connection connection, String saleId, String productId, int lineNumber)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into sale_items"
                + " (id, sale_id, line_number, product_id, barcode_snapshot, name_snapshot,"
                + " unit_snapshot, unit_price, quantity, line_total)"
                + " values (uuidv7(), cast(? as uuid), ?, cast(? as uuid), '7891000053508',"
                + " 'Produto de venda', 'UN', 9.90, 2.000, 19.80)")) {
      statement.setString(1, saleId);
      statement.setInt(2, lineNumber);
      statement.setString(3, productId);
      statement.executeUpdate();
    }
  }

  private int countItemsOfSale(Connection connection, String saleId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select count(*) from sale_items where sale_id = cast(? as uuid)")) {
      statement.setString(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private void execute(Connection connection, String sql, String parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      statement.executeUpdate();
    }
  }
}
