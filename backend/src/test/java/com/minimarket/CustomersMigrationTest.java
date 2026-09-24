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
class CustomersMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V10 cria a tabela customers com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'customers'")) {
      while (resultSet.next()) {
        columns.add(resultSet.getString("column_name"));
      }
    }

    assertThat(columns)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "store_id",
                "name",
                "tax_id",
                "phone",
                "email",
                "notes",
                "active",
                "created_at",
                "updated_at",
                "deleted_at",
                "version"));
  }

  @Test
  @DisplayName("a migration V10 cria o índice único parcial de tax_id")
  void createsIndexes() throws Exception {
    assertThat(indexDefinition("ux_customers_tax_id"))
        .as("índice único de CPF por loja, restrito aos clientes vivos e com CPF")
        .contains("UNIQUE")
        .contains("(store_id, tax_id)")
        .contains("deleted_at IS NULL")
        .contains("tax_id IS NOT NULL");
  }

  @Test
  @DisplayName("CPF duplicado em cliente ativo da mesma loja falha com SQLState 23505")
  void rejectsDuplicateActiveTaxId() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertCustomer(connection, "11144477735", null);
        assertThatThrownBy(() -> insertCustomer(connection, "11144477735", null))
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
  @DisplayName("CPF de cliente com deleted_at preenchido é liberado para outro cliente")
  void allowsTaxIdOfSoftDeletedCustomer() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertCustomer(connection, "52998224725", "2026-01-01T00:00:00Z");
        assertThatCode(() -> insertCustomer(connection, "52998224725", null))
            .doesNotThrowAnyException();
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("clientes sem CPF não disputam o índice parcial: vários nulos convivem")
  void allowsMultipleCustomersWithoutTaxId() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertCustomer(connection, null, null);
        assertThatCode(() -> insertCustomer(connection, null, null)).doesNotThrowAnyException();
      } finally {
        connection.rollback();
      }
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

  private void insertCustomer(Connection connection, String taxId, String deletedAt)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into customers (id, store_id, name, tax_id, deleted_at)"
                + " values (uuidv7(), (select id from stores limit 1), 'Cliente teste', ?,"
                + " cast(? as timestamptz))")) {
      statement.setString(1, taxId);
      statement.setString(2, deletedAt);
      statement.executeUpdate();
    }
  }
}
