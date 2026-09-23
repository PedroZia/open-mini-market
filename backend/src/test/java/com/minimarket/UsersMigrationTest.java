package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class UsersMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V2 cria a tabela users com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'users'")) {
      while (resultSet.next()) {
        columns.add(resultSet.getString("column_name"));
      }
    }

    assertThat(columns)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "username",
                "password_hash",
                "display_name",
                "status",
                "failed_login_attempts",
                "locked_until",
                "password_changed_at",
                "last_login_at",
                "created_at",
                "updated_at",
                "deleted_at",
                "version"));
  }

  @Test
  @DisplayName("a constraint de status rejeita valor fora de ACTIVE/DISABLED")
  void rejectsInvalidStatus() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "insert into users (id, username, password_hash, display_name, status)"
                            + " values (uuidv7(), 'status.invalido', 'hash', 'Teste', 'PENDING')"))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação de check constraint")
                        .isEqualTo("23514"));
      } finally {
        connection.rollback();
      }
    }
  }
}
