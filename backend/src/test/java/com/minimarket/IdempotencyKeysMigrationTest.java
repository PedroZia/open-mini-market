package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class IdempotencyKeysMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V14 cria a tabela idempotency_keys com as colunas do plano")
  void createsIdempotencyKeys() throws Exception {
    assertThat(columnsOf("idempotency_keys"))
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "key",
                "user_id",
                "method",
                "path",
                "request_hash",
                "status_code",
                "response_body",
                "created_at",
                "expires_at"));
  }

  @Test
  @DisplayName("a própria key é a PK: o INSERT duplicado é a corrida do vencedor")
  void usesKeyAsPrimaryKey() throws Exception {
    assertThat(indexDefinition("idempotency_keys_pkey")).contains("UNIQUE").contains("(key)");
  }

  @Test
  @DisplayName("a migration V14 cria o índice de limpeza por expires_at")
  void createsExpiresAtIndex() throws Exception {
    assertThat(indexDefinition("ix_idempotency_keys_expires_at"))
        .as("varredura da limpeza do passo 1004")
        .contains("(expires_at)");
  }

  @Test
  @DisplayName("response_body é jsonb not null: o corpo gravado nunca é SQL NULL")
  void responseBodyIsNotNullJsonb() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select data_type, is_nullable from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'idempotency_keys'"
                    + " and column_name = 'response_body'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("coluna response_body existe").isTrue();
        assertThat(resultSet.getString("data_type")).isEqualTo("jsonb");
        assertThat(resultSet.getString("is_nullable")).isEqualTo("NO");
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
}
