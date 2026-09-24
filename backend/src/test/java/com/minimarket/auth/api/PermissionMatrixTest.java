package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Matriz de permissões (passo 307b) contra PostgreSQL real (Dev Services): cada papel autenticado
 * de verdade — OPERADOR, GERENTE e o ADMIN da fixture — bate nas rotas reais de usuários, papéis e
 * sessões, e o teste confere o 401/403/200/204 de cada célula. Não há recurso de teste aqui: são as
 * rotas de produção com o RBAC do seed de {@code V3__rbac.sql}.
 *
 * <p>Os usuários nascem pelo caso de uso (a API de criar usuário exige token desde o passo 307a e
 * aqui eles são fixture) e o login é o de verdade, para a identidade ter as permissões efetivas do
 * papel. O request HTTP commita: o {@link #removeUsersCreatedByThisRun()} apaga eventos, sessões,
 * papéis e usuários ao fim de cada teste, porque os testes de repositório assumem as tabelas como
 * as encontraram.
 */
@QuarkusTest
class PermissionMatrixTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String USERS_PATH = "/api/v1/users";
  private static final String SESSIONS_PATH = "/api/v1/auth/sessions";
  private static final String ME_PATH = "/api/v1/auth/me";

  /** Caso de uso da criação de usuário (passo 107): a fixture nasce por aqui, não pela API. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName("sem token GET /api/v1/users responde 401 INVALID_CREDENTIALS em problem+json")
  void rejectsRequestWithoutToken() {
    Response response = given().when().get(USERS_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(USERS_PATH);
    assertThat(response.jsonPath().getString("traceId")).isNotBlank();
  }

  @Test
  @DisplayName(
      "OPERADOR autenticado recebe 403 ACCESS_DENIED em GET /api/v1/users citando user.read")
  void deniesOperatorOnUsers() {
    String username = "matriz.operador." + SUFFIX;
    createUser(username, "OPERADOR");

    Response response = get(USERS_PATH, login(username));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Acesso negado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(403);
    assertThat(response.jsonPath().getString("instance")).isEqualTo(USERS_PATH);
    assertThat(response.jsonPath().getString("detail")).contains("user.read");
  }

  @Test
  @DisplayName("ADMIN autenticado recebe 200 em GET /api/v1/users")
  void allowsAdminOnUsers() {
    Response response =
        asAdmin().when().get(USERS_PATH).then().statusCode(200).extract().response();

    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getInt("page")).isZero();
    assertThat(response.jsonPath().getList("items")).isNotNull();
  }

  @Test
  @DisplayName(
      "GERENTE recebe 403 em PUT de permissões citando role.write; ADMIN no mesmo PUT recebe 200")
  void deniesManagerOnRoleWrite() {
    String username = "matriz.gerente." + SUFFIX;
    createUser(username, "GERENTE");
    // O corpo devolve o mapa que já está lá: o PUT do ADMIN troca o conjunto por ele mesmo e os
    // testes seguintes encontram o RBAC como a migration o deixou.
    List<String> current = operatorPermissionsFromList();

    Response denied = putPermissions(login(username), bodyOf(current));

    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(denied.contentType()).contains("application/problem+json");
    assertThat(denied.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(denied.jsonPath().getString("detail")).contains("role.write");
    assertThat(operatorPermissionsFromList()).containsExactlyElementsOf(current);

    Response allowed = putPermissions(adminToken(), bodyOf(current));

    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(allowed.contentType()).contains("application/json");
    assertThat(allowed.jsonPath().getString("code")).isEqualTo("OPERADOR");
    assertThat(allowed.jsonPath().getList("permissions", String.class))
        .containsExactlyElementsOf(current);
    assertThat(operatorPermissionsFromList()).containsExactlyElementsOf(current);
  }

  @Test
  @DisplayName(
      "OPERADOR sem user.session.revoke recebe 404 ao revogar a sessão de outro usuário, que segue viva")
  void hidesOtherUsersSessionFromOperator() throws SQLException {
    String usernameA = "matriz.dono-a." + SUFFIX;
    String usernameB = "matriz.dono-b." + SUFFIX;
    createUser(usernameA, "OPERADOR");
    String idB = createUser(usernameB, "OPERADOR");
    String tokenA = login(usernameA);
    String tokenB = login(usernameB);
    String sessionB = sessionIdOf(UUID.fromString(idB));

    Response response = delete(SESSIONS_PATH + "/" + sessionB, tokenA);

    // 404 e não 403: a existência da sessão alheia não é revelada (§6.3.4).
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("NOT_FOUND");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Recurso não encontrado");

    assertThat(sessionColumn(sessionB, "revoked_at")).isNull();
    assertThat(me(tokenB).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "ADMIN com user.session.revoke revoga a sessão de outro usuário: 204, token alvo cai e evento grava")
  void adminRevokesOtherUsersSession() throws SQLException {
    String username = "matriz.alvo." + SUFFIX;
    String id = createUser(username, "OPERADOR");
    String token = login(username);
    String sessionId = sessionIdOf(UUID.fromString(id));

    Response response = delete(SESSIONS_PATH + "/" + sessionId, adminToken());

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.asString()).isEmpty();
    assertThat(sessionColumn(sessionId, "revoked_reason")).isEqualTo("SESSION_REVOKED");
    assertThat(me(token).statusCode()).isEqualTo(401);
    assertThat(me(adminToken()).statusCode()).as("o ADMIN não cai junto").isEqualTo(200);

    // A revogação alheia audita como qualquer outra (passo 304): o ator é quem pediu, não o dono.
    assertThat(auditActorOf("SESSION_REVOKED", UUID.fromString(sessionId)))
        .isEqualTo(TestAdmin.USERNAME);
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
              + " (select id from users where username like ?) or entity_id in"
              + " (select id from users where username like ?)",
          "%" + SUFFIX,
          "%" + SUFFIX);
      delete(
          connection,
          "delete from audit_events where entity_id in (select id from auth_sessions where user_id"
              + " in (select id from users where username like ?))",
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

  /** Cria o usuário pelo caso de uso (passo 107) com o papel pedido e devolve o id. */
  private String createUser(String username, String roleCode) {
    return createUserUseCase
        .execute(new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)))
        .id()
        .toString();
  }

  /** Login pela API (passo 205) e devolve o token em claro da sessão nova. */
  private static String login(String username) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(username, PASSWORD))
        .when()
        .post("/api/v1/auth/login")
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

  private static Response delete(String path, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(path)
        .then()
        .extract()
        .response();
  }

  /** GET /auth/me com o token informado; cada teste confere o status esperado. */
  private static Response me(String token) {
    return get(ME_PATH, token);
  }

  /** PUT das permissões de OPERADOR com o token informado; cada teste confere o status esperado. */
  private static Response putPermissions(String token, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .put("/api/v1/roles/OPERADOR/permissions")
        .then()
        .extract()
        .response();
  }

  /** Corpo do PUT com o conjunto informado, na ordem em que ele veio do GET. */
  private static String bodyOf(List<String> permissions) {
    return "{\"permissions\": [%s]}"
        .formatted(
            String.join(", ", permissions.stream().map(code -> "\"%s\"".formatted(code)).toList()));
  }

  /** Permissões de OPERADOR segundo o GET /api/v1/roles, para conferir que o mapa não mudou. */
  private List<String> operatorPermissionsFromList() {
    Response roles =
        asAdmin().when().get("/api/v1/roles").then().statusCode(200).extract().response();
    return roles.jsonPath().getList("[2].permissions", String.class);
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

  /** Coluna da sessão pelo id; {@code null} quando não existe ou é nula. */
  private String sessionColumn(String sessionId, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select " + column + " from auth_sessions where id = ?::uuid")) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  /** Ator do evento da ação e do alvo informados; {@code null} se o evento não foi gravado. */
  private String auditActorOf(String action, UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select actor_username from audit_events where action = ? and entity_id = ?::uuid")) {
      statement.setString(1, action);
      statement.setString(2, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s para %s", action, entityId).isTrue();
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
}
