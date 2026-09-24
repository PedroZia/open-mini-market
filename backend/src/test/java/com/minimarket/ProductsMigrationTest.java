package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProductsMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V8 cria a tabela products com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'products'")) {
      while (resultSet.next()) {
        columns.add(resultSet.getString("column_name"));
      }
    }

    assertThat(columns)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "store_id",
                "barcode",
                "internal_code",
                "sku",
                "name",
                "description",
                "category_id",
                "unit",
                "price",
                "cost_price",
                "min_quantity",
                "active",
                "created_at",
                "updated_at",
                "deleted_at",
                "version"));
  }

  @Test
  @DisplayName("a migration V8 cria o índice único parcial de barcode e os índices de consulta")
  void createsIndexes() throws Exception {
    assertThat(indexDefinition("ux_products_barcode"))
        .as("índice único de barcode por loja, restrito aos produtos vivos e com código")
        .contains("UNIQUE")
        .contains("(store_id, barcode)")
        .contains("deleted_at IS NULL")
        .contains("barcode IS NOT NULL");
    assertThat(indexDefinition("ux_products_internal_code"))
        .as("índice único do código interno (V21), espelho do de barcode")
        .contains("UNIQUE")
        .contains("(store_id, internal_code)")
        .contains("deleted_at IS NULL")
        .contains("internal_code IS NOT NULL");
    assertThat(indexDefinition("ix_products_store_active"))
        .as("índice de listagem por loja/ativo")
        .contains("(store_id, active)");
    assertThat(indexDefinition("ix_products_category_id"))
        .as("índice de filtro por categoria")
        .contains("(category_id)");
  }

  @Test
  @DisplayName("barcode duplicado em produto ativo da mesma loja falha com SQLState 23505")
  void rejectsDuplicateActiveBarcode() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertProduct(connection, "7891000000017", "UN", "9.90", null);
        assertThatThrownBy(() -> insertProduct(connection, "7891000000017", "UN", "9.90", null))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação de unicidade")
                        .isEqualTo("23505"));
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("barcode de produto com deleted_at preenchido é liberado para outro produto")
  void allowsBarcodeOfSoftDeletedProduct() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertProduct(connection, "7891000000024", "UN", "9.90", "2026-01-01T00:00:00Z");
        assertThatCode(() -> insertProduct(connection, "7891000000024", "UN", "12.50", null))
            .doesNotThrowAnyException();
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("produtos sem barcode não disputam o índice parcial: vários nulos convivem")
  void allowsMultipleProductsWithoutBarcode() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertProduct(connection, null, "UN", "5.00", null);
        assertThatCode(() -> insertProduct(connection, null, "KG", "19.90", null))
            .doesNotThrowAnyException();
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("preço negativo é rejeitado com SQLState 23514")
  void rejectsNegativePrice() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "insert into products (id, store_id, name, unit, price)"
                          + " values (uuidv7(), (select id from stores limit 1),"
                          + " 'Preço inválido', 'UN', -0.01)"))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação de check constraint")
                      .isEqualTo("23514"));
    }
  }

  @Test
  @DisplayName("unit fora de UN/KG é rejeitado com SQLState 23514")
  void rejectsInvalidUnit() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "insert into products (id, store_id, name, unit, price)"
                          + " values (uuidv7(), (select id from stores limit 1),"
                          + " 'Unidade inválida', 'CX', 1.00)"))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação de check constraint")
                      .isEqualTo("23514"));
    }
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

  private void insertProduct(
      Connection connection, String barcode, String unit, String price, String deletedAt)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into products (id, store_id, name, barcode, unit, price, deleted_at)"
                + " values (uuidv7(), (select id from stores limit 1), 'Produto teste', ?, ?,"
                + " cast(? as numeric), cast(? as timestamptz))")) {
      statement.setString(1, barcode);
      statement.setString(2, unit);
      statement.setString(3, price);
      statement.setString(4, deletedAt);
      statement.executeUpdate();
    }
  }
}
