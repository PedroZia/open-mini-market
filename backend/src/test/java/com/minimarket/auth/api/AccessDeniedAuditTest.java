package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
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
 * Auditoria do acesso negado (passo 309) contra PostgreSQL real (Dev Services): o 403 de permissão
 * de um OPERADOR vira <em>exatamente um</em> evento {@code ACCESS_DENIED}, com rota, método e
 * permissão exigida em {@code details} e ator, sessão, loja e IP do {@code OperationContext} da
 * requisição; requisição autorizada e requisição sem token não geram evento nenhum.
 *
 * <p>A conferência é por SQL — o banco é a fonte de verdade do que comitou —, sempre filtrando por
 * {@code action = 'ACCESS_DENIED'}: o login da fixture grava {@code LOGIN_SUCCESS} e não pode
 * entrar na conta. O request HTTP commita de verdade e o log é append-only para a aplicação; o
 * teste, conectado como dono das tabelas, remove ao fim de cada teste o que ele mesmo criou.
 */
@QuarkusTest
class AccessDeniedAuditTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String USERS_PATH = "/api/v1/users";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String AUTHORIZATION = "Authorization";

  /** Caso de uso da criação de usuário (passo 107): a fixture nasce por aqui, não pela API. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName(
      "403 de OPERADOR grava exatamente um ACCESS_DENIED com rota, método, permissão e ator")
  void auditsDeniedAccess() throws SQLException {
    String username = "negado.operador." + SUFFIX;
    String id = createUser(username);
    UUID userId = UUID.fromString(id);
    String token = login(username);

    Response response = get(USERS_PATH, token);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(USERS_PATH);

    List<Event> events = accessDeniedBy(userId);
    assertThat(events).as("uma tentativa, um evento").hasSize(1);
    Event event = events.getFirst();
    assertThat(event.entityType()).as("o alvo é a requisição recusada, não uma entidade").isNull();
    assertThat(event.entityId()).isNull();
    assertThat(event.actorUserId()).isEqualTo(id);
    assertThat(event.actorUsername()).isEqualTo(username);
    assertThat(event.authSessionId())
        .as("a sessão que tentou, não a que autenticou outro ator")
        .isEqualTo(sessionIdOf(userId));
    assertThat(event.storeId()).as("loja da sessão").isNotNull();
    assertThat(event.source()).as("login sem X-Client é TUI neste teste").isEqualTo("TUI");
    assertThat(event.requestId()).isNotBlank();
    assertThat(event.ip()).as("IP de origem da conexão").isNotNull();
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .contains("\"route\": \"" + USERS_PATH + "\"")
        .contains("\"method\": \"GET\"")
        .contains("\"permission\": \"user.read\"");
  }

  @Test
  @DisplayName("requisição autorizada (ADMIN em GET /users) não grava ACCESS_DENIED")
  void doesNotAuditAuthorizedAccess() throws SQLException {
    long before = countAccessDenied();

    Response response = asAdmin().when().get(USERS_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(countAccessDenied()).as("acesso autorizado não é acesso negado").isEqualTo(before);
  }

  @Test
  @DisplayName("requisição sem token recebe 401 do challenge e não grava ACCESS_DENIED")
  void doesNotAuditMissingToken() throws SQLException {
    long before = countAccessDenied();

    Response response = given().when().get(USERS_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(countAccessDenied())
        .as("o challenge não passa pelo mapper de erro, então não há 403 a auditar")
        .isEqualTo(before);
  }

  /**
   * O request HTTP commita, então o que este teste cria é removido ao fim de cada um: os eventos de
   * auditoria saem antes das sessões e usuários que eles referenciam (não há FK, mas a linha
   * ficaria órfã), e as FKs de {@code auth_sessions} e {@code user_roles} são {@code on delete
   * restrict}.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from audit_events where actor_user_id in"
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

  /** Cria o usuário pelo caso de uso (passo 107) com o papel OPERADOR e devolve o id. */
  private String createUser(String username) {
    return createUserUseCase
        .execute(new CreateUserCommand(username, username, PASSWORD, List.of("OPERADOR")))
        .id()
        .toString();
  }

  /** Login real (passo 205) com o cliente TUI explícito, para a origem do evento ser conhecida. */
  private static String login(String username) {
    return given()
        .contentType("application/json")
        .header(AuthResource.CLIENT_HEADER, "TUI")
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(username, PASSWORD))
        .when()
        .post(LOGIN_PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  private static Response get(String path, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(path)
        .then()
        .extract()
        .response();
  }

  /** Eventos {@code ACCESS_DENIED} do ator, na ordem de gravação. */
  private List<Event> accessDeniedBy(UUID actorUserId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id, actor_user_id, actor_username, auth_session_id,"
                    + " store_id, source, request_id, reason, details::text as details,"
                    + " host(ip) as ip from audit_events where action = 'ACCESS_DENIED'"
                    + " and actor_user_id = ?::uuid order by id")) {
      statement.setString(1, actorUserId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Event> events = new ArrayList<>();
        while (resultSet.next()) {
          events.add(
              new Event(
                  resultSet.getString("entity_type"),
                  resultSet.getString("entity_id"),
                  resultSet.getString("actor_user_id"),
                  resultSet.getString("actor_username"),
                  resultSet.getString("auth_session_id"),
                  resultSet.getString("store_id"),
                  resultSet.getString("source"),
                  resultSet.getString("request_id"),
                  resultSet.getString("reason"),
                  resultSet.getString("details"),
                  resultSet.getString("ip")));
        }
        return events;
      }
    }
  }

  /** Quantos {@code ACCESS_DENIED} o log inteiro guarda; o teste compara antes e depois. */
  private long countAccessDenied() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where action = 'ACCESS_DENIED'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  /** Id da sessão mais recente do usuário — o login do teste é a única dele. */
  private String sessionIdOf(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id from auth_sessions where user_id = ?::uuid"
                    + " order by created_at desc limit 1")) {
      statement.setString(1, userId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão do usuário %s", userId).isTrue();
        return resultSet.getString(1);
      }
    }
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

  /** Evento como o banco o guardou; {@code details} e {@code ip} vêm no formato textual do PG. */
  private record Event(
      String entityType,
      String entityId,
      String actorUserId,
      String actorUsername,
      String authSessionId,
      String storeId,
      String source,
      String requestId,
      String reason,
      String details,
      String ip) {}
}
