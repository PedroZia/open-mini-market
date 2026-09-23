package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
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
class AuditEventsMigrationTest extends IntegrationTestBase {

  private record Column(String dataType, String nullable, String defaultValue, boolean identity) {}

  @Test
  @DisplayName("a migration V6 cria a tabela audit_events com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Map<String, Column> columns = columnsOf("audit_events");

    assertThat(columns.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id",
                "occurred_at",
                "store_id",
                "actor_user_id",
                "actor_username",
                "auth_session_id",
                "cash_session_id",
                "cash_register_id",
                "action",
                "entity_type",
                "entity_id",
                "source",
                "request_id",
                "reason",
                "details",
                "ip"));

    assertThat(columns.get("id").dataType())
        .as("id sequencial, exceção ao UUIDv7")
        .isEqualTo("bigint");
    assertThat(columns.get("id").identity()).as("id generated always as identity").isTrue();
    assertThat(columns.get("occurred_at").dataType())
        .as("datas em timestamptz (UTC)")
        .isEqualTo("timestamp with time zone");
    assertThat(columns.get("occurred_at").nullable()).as("occurred_at obrigatório").isEqualTo("NO");
    assertThat(columns.get("action").nullable()).as("action obrigatório").isEqualTo("NO");
    assertThat(columns.get("source").nullable()).as("source obrigatório").isEqualTo("NO");
    assertThat(columns.get("details").dataType()).as("details jsonb").isEqualTo("jsonb");
    assertThat(columns.get("details").defaultValue())
        .as("details nasce vazio")
        .contains("'{}'::jsonb");
    assertThat(columns.get("ip").dataType()).as("ip inet").isEqualTo("inet");
  }

  @Test
  @DisplayName("a migration V6 cria os 5 índices de consulta do log de auditoria")
  void createsIndexes() throws Exception {
    assertThat(indexDefinition("ix_audit_events_occurred_at"))
        .as("consulta por período")
        .contains("(occurred_at DESC)");
    assertThat(indexDefinition("ix_audit_events_entity_occurred_at"))
        .as("histórico de uma entidade")
        .contains("(entity_type, entity_id, occurred_at DESC)");
    assertThat(indexDefinition("ix_audit_events_actor_occurred_at"))
        .as("eventos de um ator")
        .contains("(actor_user_id, occurred_at DESC)");
    assertThat(indexDefinition("ix_audit_events_action_occurred_at"))
        .as("eventos de uma ação")
        .contains("(action, occurred_at DESC)");
    assertThat(indexDefinition("ix_audit_events_cash_session_id"))
        .as("eventos de uma sessão de caixa")
        .contains("(cash_session_id)");
  }

  @Test
  @DisplayName(
      "auditoria é append-only: com minimarket_app o INSERT/SELECT funcionam e UPDATE/DELETE falham por permissão")
  void appRoleCanOnlyAppend() throws Exception {
    assertThat(roleCanLogin())
        .as("minimarket_app é role de aplicação, sem login próprio")
        .isFalse();

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // SET LOCAL dentro da transação: ao dar rollback a sessão volta à role do pool.
        execute(connection, "set local role minimarket_app");

        long id = insertEvent(connection, "LOGIN_SUCCESS");
        assertThat(detailsOf(connection, id))
            .as("details sem valor explícito usa o default")
            .isEqualTo("{}");

        assertPermissionDenied(
            connection, "update audit_events set reason = 'alterado' where id = " + id);
        assertPermissionDenied(connection, "delete from audit_events where id = " + id);
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
                "select column_name, data_type, is_nullable, column_default, is_identity"
                    + " from information_schema.columns"
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
                  "YES".equals(resultSet.getString("is_identity"))));
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

  private long insertEvent(Connection connection, String action) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into audit_events (action, source) values (?, 'API') returning id")) {
      statement.setString(1, action);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong("id");
      }
    }
  }

  private String detailsOf(Connection connection, long id) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("select details::text from audit_events where id = ?")) {
      statement.setLong(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento inserido é visível para a role").isTrue();
        return resultSet.getString(1);
      }
    }
  }

  private void assertPermissionDenied(Connection connection, String sql) throws Exception {
    // Sem savepoint, a primeira falha abortaria a transação e a segunda nem chegaria ao banco.
    Savepoint savepoint = connection.setSavepoint();
    try {
      assertThatThrownBy(() -> execute(connection, sql))
          .as("role da aplicação não pode alterar o log: %s", sql)
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
