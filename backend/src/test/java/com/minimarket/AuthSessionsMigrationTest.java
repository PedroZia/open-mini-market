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
class AuthSessionsMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V5 cria a tabela auth_sessions com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'auth_sessions'")) {
      while (resultSet.next()) {
        columns.add(resultSet.getString("column_name"));
      }
    }

    assertThat(columns)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "user_id",
                "token_hash",
                "client",
                "store_id",
                "cash_register_id",
                "ip",
                "user_agent",
                "created_at",
                "last_seen_at",
                "expires_at",
                "revoked_at",
                "revoked_reason",
                "version"));
  }

  @Test
  @DisplayName("a migration V5 cria o índice parcial de sessões ativas e o de expiração")
  void createsIndexes() throws Exception {
    assertThat(indexDefinition("ix_auth_sessions_user_active"))
        .as("índice parcial de sessões não revogadas")
        .contains("(user_id)")
        .contains("WHERE (revoked_at IS NULL)");
    assertThat(indexDefinition("ix_auth_sessions_expires_at"))
        .as("índice de expiração")
        .contains("(expires_at)");
  }

  @Test
  @DisplayName("token_hash é único: inserir o mesmo hash duas vezes falha com SQLState 23505")
  void rejectsDuplicateTokenHash() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String userId = insertUser(connection);
        insertSession(connection, userId, "hash-repetido");
        assertThatThrownBy(() -> insertSession(connection, userId, "hash-repetido"))
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

  private String insertUser(Connection connection) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into users (id, username, password_hash, display_name, status)"
                + " values (uuidv7(), 'sessao.teste', 'hash', 'Teste', 'ACTIVE') returning id")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("id");
      }
    }
  }

  private void insertSession(Connection connection, String userId, String tokenHash)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into auth_sessions"
                + " (id, user_id, token_hash, client, store_id, last_seen_at, expires_at)"
                + " values (uuidv7(), cast(? as uuid), ?, 'TUI', (select id from stores limit 1),"
                + " now(), now() + interval '12 hours')")) {
      statement.setString(1, userId);
      statement.setString(2, tokenHash);
      statement.executeUpdate();
    }
  }
}
