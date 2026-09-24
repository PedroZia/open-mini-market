package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
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
class CategoriesMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V7 cria a tabela categories com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'categories'")) {
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
                "parent_id",
                "active",
                "sort_order",
                "created_at",
                "updated_at",
                "version"));
  }

  @Test
  @DisplayName("nome duplicado na mesma loja falha com SQLState 23505")
  void rejectsDuplicateNameInSameStore() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertCategory(connection, "Bebidas", null);
        assertThatThrownBy(() -> insertCategory(connection, "Bebidas", null))
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

  // Os inserts de FK inválida não gravam linha nenhuma: falham sob autocommit, sem transação
  // para reverter (diferente do duplicado, que insere uma linha válida antes).
  @Test
  @DisplayName("store_id inexistente é rejeitado com SQLState 23503")
  void rejectsUnknownStore() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(
              () ->
                  insertCategoryInStore(
                      connection, "00000000-0000-7000-8000-000000000000", "Fantasma"))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação de chave estrangeira")
                      .isEqualTo("23503"));
    }
  }

  @Test
  @DisplayName("parent_id inexistente é rejeitado com SQLState 23503")
  void rejectsUnknownParent() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(
              () -> insertCategory(connection, "Filha", "00000000-0000-7000-8000-000000000000"))
          .isInstanceOf(SQLException.class)
          .satisfies(
              error ->
                  assertThat(((SQLException) error).getSQLState())
                      .as("violação de chave estrangeira")
                      .isEqualTo("23503"));
    }
  }

  private void insertCategory(Connection connection, String name, String parentId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into categories (id, store_id, name, parent_id)"
                + " values (uuidv7(), (select id from stores limit 1), ?, cast(? as uuid))")) {
      statement.setString(1, name);
      statement.setString(2, parentId);
      statement.executeUpdate();
    }
  }

  private void insertCategoryInStore(Connection connection, String storeId, String name)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into categories (id, store_id, name)"
                + " values (uuidv7(), cast(? as uuid), ?)")) {
      statement.setString(1, storeId);
      statement.setString(2, name);
      statement.executeUpdate();
    }
  }
}
