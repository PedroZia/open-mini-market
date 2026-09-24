package com.minimarket.users.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimarket.IntegrationTestBase;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Eventos de auditoria da administração de usuários (passo 310a) contra PostgreSQL real (Dev
 * Services): criar, editar, desativar e reativar gravam o evento esperado em {@code audit_events},
 * na mesma transação, com o ator que executou a operação e o antes/depois mínimo em {@code details}
 * (§7.2). A conferência é por SQL — o banco é a fonte de verdade do que comitou junto com o caso de
 * uso —, sempre filtrando por {@code action} e {@code entity_id}: a fixture do ADMIN gera eventos
 * ({@code USER_CREATED} do próprio admin e {@code LOGIN_SUCCESS}) e a desativação ainda gera {@code
 * SESSION_REVOKED} para o mesmo usuário (passo 213), que não podem entrar na conta.
 *
 * <p>O request HTTP commita de verdade e o log é append-only para a aplicação; o teste, conectado
 * como dono das tabelas, remove ao fim de cada teste o que ele mesmo criou — inclusive os usuários,
 * que não podem sobrar para os testes de repositório.
 */
@QuarkusTest
class UsersAuditEventsTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String USERS_PATH = "/api/v1/users";

  @Test
  @DisplayName("POST /users grava USER_CREATED com o id criado e username/displayName/roles")
  void auditsUserCreated() throws SQLException {
    String username = "audita.cria." + SUFFIX;
    String id = createUser(username, "Audita Cria", "OPERADOR");

    Event event = singleEvent("USER_CREATED", UUID.fromString(id));
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.entityId()).isEqualTo(id);
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);

    JsonNode details = detailsOf(event);
    assertThat(details.path("username").asText()).isEqualTo(username);
    assertThat(details.path("displayName").asText()).isEqualTo("Audita Cria");
    assertThat(rolesIn(details, "/roles")).containsExactly("OPERADOR");
    assertThat(event.details())
        .as("senha e hash nunca entram no evento")
        .doesNotContain(PASSWORD)
        .doesNotContain("$argon2");
  }

  @Test
  @DisplayName("PUT /users grava USER_UPDATED com before/after de displayName e roles")
  void auditsUserUpdated() throws SQLException {
    String id = createUser("audita.edita." + SUFFIX, "Nome Antigo", "OPERADOR");

    asAdmin()
        .contentType("application/json")
        .body(
            """
            {"displayName": "Nome Novo", "roleCodes": ["ADMIN", "GERENTE"]}
            """)
        .when()
        .put(USERS_PATH + "/{id}", id)
        .then()
        .statusCode(200);

    Event event = singleEvent("USER_UPDATED", UUID.fromString(id));
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.entityId()).isEqualTo(id);
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);

    JsonNode details = detailsOf(event);
    assertThat(details.at("/before/displayName").asText()).isEqualTo("Nome Antigo");
    assertThat(rolesIn(details, "/before/roles")).containsExactly("OPERADOR");
    assertThat(details.at("/after/displayName").asText()).isEqualTo("Nome Novo");
    assertThat(rolesIn(details, "/after/roles")).containsExactly("ADMIN", "GERENTE");
  }

  @Test
  @DisplayName("POST /users/{id}/disable grava USER_DISABLED com before/after de status")
  void auditsUserDisabled() throws SQLException {
    String id = createUser("audita.desativa." + SUFFIX, "Audita Desativa", "OPERADOR");

    asAdmin().when().post(USERS_PATH + "/{id}/disable", id).then().statusCode(200);

    Event event = singleEvent("USER_DISABLED", UUID.fromString(id));
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.entityId()).isEqualTo(id);
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);

    JsonNode details = detailsOf(event);
    assertThat(details.at("/before/status").asText()).isEqualTo("ACTIVE");
    assertThat(details.at("/after/status").asText()).isEqualTo("DISABLED");
  }

  @Test
  @DisplayName("POST /users/{id}/enable grava USER_ENABLED na reativação; ativo é no-op sem evento")
  void auditsUserEnabled() throws SQLException {
    String id = createUser("audita.reativa." + SUFFIX, "Audita Reativa", "OPERADOR");
    UUID userId = UUID.fromString(id);
    asAdmin().when().post(USERS_PATH + "/{id}/disable", id).then().statusCode(200);

    asAdmin().when().post(USERS_PATH + "/{id}/enable", id).then().statusCode(200);

    Event event = singleEvent("USER_ENABLED", userId);
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.entityId()).isEqualTo(id);
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);

    JsonNode details = detailsOf(event);
    assertThat(details.at("/before/status").asText()).isEqualTo("DISABLED");
    assertThat(details.at("/after/status").asText()).isEqualTo("ACTIVE");

    // Reativar quem já está ativo é no-op: repete o 200 e não inventa evento, como no
    // RevokeSessionUseCase (passo 304).
    asAdmin().when().post(USERS_PATH + "/{id}/enable", id).then().statusCode(200);
    assertThat(events("USER_ENABLED", userId)).as("o no-op não inventa evento").hasSize(1);
  }

  /**
   * O request HTTP commita, então o que este teste criou é removido ao fim de cada um: os eventos
   * de auditoria saem antes dos usuários que eles apontam (não há FK, mas a linha ficaria órfã) e a
   * FK de {@code user_roles} é {@code on delete restrict}.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from audit_events where entity_id in"
              + " (select id from users where username like ?)",
          "%" + SUFFIX);
      delete(
          connection,
          "delete from auth_sessions where user_id in"
              + " (select id from users where username like ?)",
          "%" + SUFFIX);
      delete(
          connection,
          "delete from user_roles where user_id in (select id from users where username like ?)",
          "%" + SUFFIX);
      delete(connection, "delete from users where username like ?", "%" + SUFFIX);
    }
  }

  /** Cria o usuário pela API com o token do ADMIN da fixture (passo 307a) e devolve o id. */
  private String createUser(String username, String displayName, String roleCode) {
    return asAdmin()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "%s", "roleCodes": ["%s"]}
            """
                .formatted(username, displayName, PASSWORD, roleCode))
        .when()
        .post(USERS_PATH)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  /** Eventos da ação para o alvo informado, na ordem de gravação. */
  private List<Event> events(String action, UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id, actor_username, details::text as details"
                    + " from audit_events where action = ? and entity_id = ?::uuid order by id")) {
      statement.setString(1, action);
      statement.setString(2, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Event> events = new ArrayList<>();
        while (resultSet.next()) {
          events.add(
              new Event(
                  resultSet.getString("entity_type"),
                  resultSet.getString("entity_id"),
                  resultSet.getString("actor_username"),
                  resultSet.getString("details")));
        }
        return events;
      }
    }
  }

  /** Evento único do cenário; o teste falha se o caso de uso gravou zero ou mais de um. */
  private Event singleEvent(String action, UUID entityId) throws SQLException {
    List<Event> events = events(action, entityId);
    assertThat(events).as("eventos %s para a entidade %s", action, entityId).hasSize(1);
    return events.getFirst();
  }

  /** {@code details} jsonb como JSON: o antes/depois aninhado é conferido campo a campo. */
  private static JsonNode detailsOf(Event event) {
    try {
      return new ObjectMapper().readTree(event.details());
    } catch (JsonProcessingException invalidJson) {
      throw new AssertionError("details não é JSON válido: " + event.details(), invalidJson);
    }
  }

  /** Códigos do array JSON no caminho informado (ex.: {@code /after/roles}). */
  private static List<String> rolesIn(JsonNode details, String path) {
    List<String> roles = new ArrayList<>();
    details.at(path).forEach(node -> roles.add(node.asText()));
    return roles;
  }

  private static void delete(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  /** Evento como o banco o guardou; {@code details} vem no formato textual do jsonb do PG. */
  private record Event(String entityType, String entityId, String actorUsername, String details) {}
}
