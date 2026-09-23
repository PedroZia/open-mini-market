package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.domain.TokenHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API do {@code POST /api/v1/auth/login}, do {@code GET /api/v1/auth/me}, do {@code POST
 * /api/v1/auth/logout} e das sessões do usuário ({@code GET /api/v1/auth/sessions} e {@code DELETE
 * /api/v1/auth/sessions/{id}}) contra PostgreSQL real (Dev Services). O request HTTP commita de
 * verdade: cada teste usa um sufixo único da execução e o {@link #removeUsersCreatedByThisRun()}
 * apaga sessões, papéis e usuários ao fim de cada um — as FKs de {@code auth_sessions} e {@code
 * user_roles} são {@code on delete restrict}.
 */
@QuarkusTest
class AuthResourceTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String USER_AGENT = "pdv-test/1.0";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String LOGOUT_PATH = "/api/v1/auth/logout";
  private static final String ME_PATH = "/api/v1/auth/me";
  private static final String SESSIONS_PATH = "/api/v1/auth/sessions";
  private static final String META_PATH = "/api/v1/meta";
  private static final String AUTHORIZATION = "Authorization";

  /** Domínio puro, sem estado e sem CDI (passo 203): o teste instancia o hash do token. */
  private final TokenHasher tokenHasher = new TokenHasher();

  @Test
  @DisplayName(
      "POST /api/v1/auth/login responde 200 com token, expiração, usuário e RBAC, sem vazar credencial")
  void logsIn() throws SQLException {
    String username = "login.ok." + SUFFIX;
    String id = createUser(username, "Login Ok", "OPERADOR");
    UUID cashRegisterId = UUID.randomUUID();

    Response response = login(username, PASSWORD, "TUI", cashRegisterId.toString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    String token = response.jsonPath().getString("token");
    assertThat(token).hasSize(43);
    assertThat(response.jsonPath().getString("user.id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("user.username")).isEqualTo(username);
    assertThat(response.jsonPath().getString("user.displayName")).isEqualTo("Login Ok");
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.jsonPath().getList("permissions", String.class))
        .contains("sale.create", "payment.add", "cash.open");
    assertThat(response.jsonPath().getBoolean("mustChangePassword")).isFalse();
    assertThat(response.asString()).doesNotContain(PASSWORD).doesNotContain("$argon2");

    Instant expiresAt = Instant.parse(response.jsonPath().getString("expiresAt"));
    assertThat(expiresAt)
        .isBetween(
            Instant.now().plus(11, ChronoUnit.HOURS), Instant.now().plus(13, ChronoUnit.HOURS));

    // A sessão guarda só o hash do token, com o cliente, o caixa e a origem da requisição.
    UUID userId = UUID.fromString(id);
    assertThat(sessionColumn(userId, "token_hash"))
        .isEqualTo(tokenHasher.hash(token))
        .isNotEqualTo(token);
    assertThat(sessionColumn(userId, "client")).isEqualTo("TUI");
    assertThat(sessionColumn(userId, "cash_register_id")).isEqualTo(cashRegisterId.toString());
    assertThat(sessionColumn(userId, "ip")).isNotNull();
    assertThat(sessionColumn(userId, "user_agent")).isEqualTo(USER_AGENT);
  }

  @Test
  @DisplayName(
      "GET /api/v1/auth/me responde 200 com usuário, RBAC, loja, caixa, cliente e expiração")
  void returnsCurrentSession() {
    String username = "me.ok." + SUFFIX;
    String id = createUser(username, "Me Ok", "OPERADOR");
    UUID cashRegisterId = UUID.randomUUID();
    String token =
        login(username, PASSWORD, "TUI", cashRegisterId.toString()).jsonPath().getString("token");

    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .get(ME_PATH)
            .then()
            .extract()
            .response();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getString("user.id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("user.username")).isEqualTo(username);
    assertThat(response.jsonPath().getString("user.displayName")).isEqualTo("Me Ok");
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.jsonPath().getList("permissions", String.class))
        .contains("sale.create", "payment.add", "cash.open");
    assertThat(response.jsonPath().getString("store.code")).isEqualTo("MATRIZ");
    assertThat(response.jsonPath().getString("store.name")).isEqualTo("Matriz");
    assertThat(response.jsonPath().getString("cashRegisterId"))
        .isEqualTo(cashRegisterId.toString());
    assertThat(response.jsonPath().getString("client")).isEqualTo("TUI");

    Instant expiresAt = Instant.parse(response.jsonPath().getString("expiresAt"));
    assertThat(expiresAt)
        .isBetween(
            Instant.now().plus(11, ChronoUnit.HOURS), Instant.now().plus(13, ChronoUnit.HOURS));
    Instant lastSeenAt = Instant.parse(response.jsonPath().getString("lastSeenAt"));
    assertThat(lastSeenAt).isNotNull().isBeforeOrEqualTo(Instant.now());

    // A sessão atual não devolve credencial nem hash de nada.
    assertThat(response.asString()).doesNotContain(PASSWORD).doesNotContain("$argon2");
  }

  @Test
  @DisplayName("GET /api/v1/auth/me sem token responde 401 problem+json")
  void rejectsCurrentSessionWithoutToken() {
    Response response = given().when().get(ME_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Credenciais inválidas");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(ME_PATH);
    assertThat(response.jsonPath().getString("traceId")).isNotBlank();
  }

  @Test
  @DisplayName("GET /api/v1/auth/me com token desconhecido responde 401 sem enumerar o motivo")
  void rejectsCurrentSessionWithUnknownToken() {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer token-que-nunca-existiu")
            .when()
            .get(ME_PATH)
            .then()
            .extract()
            .response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("POST /api/v1/auth/logout revoga a sessão atual e o token deixa de autenticar")
  void logsOut() throws SQLException {
    String username = "logout.ok." + SUFFIX;
    String id = createUser(username, "Logout Ok", "OPERADOR");
    String token = login(username, PASSWORD, null, null).jsonPath().getString("token");

    Response logout =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .post(LOGOUT_PATH)
            .then()
            .extract()
            .response();

    assertThat(logout.statusCode()).isEqualTo(204);
    assertThat(logout.asString()).isEmpty();

    // A sessão fica revogada com o motivo LOGOUT — não é apagada nem vira erro.
    UUID userId = UUID.fromString(id);
    assertThat(sessionColumn(userId, "revoked_reason")).isEqualTo("LOGOUT");
    assertThat(sessionColumn(userId, "revoked_at")).isNotNull();

    // O mesmo token não autentica mais: 401 problem+json do mecanismo bearer (passo 206).
    Response me =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .get(ME_PATH)
            .then()
            .extract()
            .response();
    assertThat(me.statusCode()).isEqualTo(401);
    assertThat(me.contentType()).contains("application/problem+json");
    assertThat(me.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(me.jsonPath().getString("instance")).isEqualTo(ME_PATH);
  }

  @Test
  @DisplayName("POST /api/v1/auth/logout sem token responde 401 problem+json")
  void rejectsLogoutWithoutToken() {
    Response response = given().when().post(LOGOUT_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(LOGOUT_PATH);
  }

  @Test
  @DisplayName("GET /api/v1/meta continua 200 sem token: só as rotas de sessão foram protegidas")
  void keepsMetaPublic() {
    Response response = given().when().get(META_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("storeCode")).isEqualTo("MATRIZ");
  }

  @Test
  @DisplayName("sem o header X-Client a sessão nasce como WEB")
  void defaultsToWebClient() throws SQLException {
    String username = "login.web." + SUFFIX;
    String id = createUser(username, "Login Web", null);

    Response response = login(username, PASSWORD, null, null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("roles")).isEmpty();
    assertThat(sessionColumn(UUID.fromString(id), "client")).isEqualTo("WEB");
  }

  @Test
  @DisplayName(
      "senha errada e usuário inexistente respondem o mesmo 401 INVALID_CREDENTIALS, sem enumerar")
  void rejectsInvalidCredentialsWithoutUserEnumeration() {
    String username = "login.errado." + SUFFIX;
    createUser(username, "Login Errado", null);

    Response wrongPassword = login(username, "senha-errada", null, null);
    Response unknownUser = login("fantasma." + SUFFIX, PASSWORD, null, null);

    assertThat(wrongPassword.statusCode()).isEqualTo(401);
    assertThat(wrongPassword.contentType()).contains("application/problem+json");
    assertThat(wrongPassword.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(unknownUser.statusCode()).isEqualTo(401);
    assertThat(unknownUser.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(wrongPassword.jsonPath().getString("detail"))
        .isEqualTo(unknownUser.jsonPath().getString("detail"))
        .doesNotContain(SUFFIX);
  }

  @Test
  @DisplayName(
      "5 falhas bloqueiam a conta: a 6ª tentativa responde 423 ACCOUNT_LOCKED com senha certa")
  void locksAfterFiveFailures() {
    String username = "login.lock." + SUFFIX;
    createUser(username, "Login Lock", null);

    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(login(username, "senha-errada", null, null).jsonPath().getString("code"))
          .isEqualTo("INVALID_CREDENTIALS");
    }

    Response locked = login(username, PASSWORD, null, null);

    assertThat(locked.statusCode()).isEqualTo(423);
    assertThat(locked.contentType()).contains("application/problem+json");
    assertThat(locked.jsonPath().getString("code")).isEqualTo("ACCOUNT_LOCKED");
    assertThat(locked.jsonPath().getString("title")).isEqualTo("Conta bloqueada");
  }

  @Test
  @DisplayName("X-Client fora de TUI/WEB responde 400 e não abre sessão")
  void rejectsInvalidClientHeader() throws SQLException {
    String username = "login.client." + SUFFIX;
    String id = createUser(username, "Login Client", null);

    Response response = login(username, PASSWORD, "MOBILE", null);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getString("detail")).contains("X-Client");
    assertThat(sessionCount(UUID.fromString(id))).isZero();
  }

  @Test
  @DisplayName("login sem corpo ou sem os campos obrigatórios responde 400 de validação")
  void rejectsEmptyBody() {
    Response missingBody =
        given()
            .contentType("application/json")
            .when()
            .post(LOGIN_PATH)
            .then()
            .statusCode(400)
            .extract()
            .response();
    assertThat(missingBody.contentType()).contains("application/problem+json");
    assertThat(missingBody.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(missingBody.jsonPath().getString("detail")).contains("corpo");

    Response blankFields =
        given()
            .contentType("application/json")
            .body("{}")
            .when()
            .post(LOGIN_PATH)
            .then()
            .statusCode(400)
            .extract()
            .response();

    assertThat(blankFields.contentType()).contains("application/problem+json");
    assertThat(blankFields.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(blankFields.jsonPath().getList("errors.field", String.class))
        .containsExactlyInAnyOrder("username", "password");
  }

  @Test
  @DisplayName("login com a senha temporária do reset responde mustChangePassword=true")
  void reportsMustChangePassword() {
    String username = "login.temp." + SUFFIX;
    String id = createUser(username, "Login Temp", null);
    given()
        .contentType("application/json")
        .body(
            """
            {"newPassword": "senha-temporaria"}
            """)
        .when()
        .post("/api/v1/users/{id}/password-reset", id)
        .then()
        .statusCode(200)
        .body("mustChangePassword", equalTo(true));

    Response response = login(username, "senha-temporaria", null, null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getBoolean("mustChangePassword")).isTrue();
  }

  @Test
  @DisplayName(
      "GET /api/v1/auth/sessions lista as sessões ativas do usuário e marca a atual (passo 210)")
  void listsUserSessions() throws SQLException {
    String username = "sessoes.lista." + SUFFIX;
    String id = createUser(username, "Sessoes Lista", "OPERADOR");
    String tuiToken = login(username, PASSWORD, "TUI", null).jsonPath().getString("token");
    String webToken = login(username, PASSWORD, "WEB", null).jsonPath().getString("token");
    UUID userId = UUID.fromString(id);

    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + webToken)
            .when()
            .get(SESSIONS_PATH)
            .then()
            .extract()
            .response();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    List<Map<String, Object>> sessions = response.jsonPath().getList("$");
    assertThat(sessions).hasSize(2);

    // Ordenação por último uso: o login da WEB veio depois, então a sessão atual vem primeiro.
    Map<String, Object> current = sessions.getFirst();
    assertThat(current.get("id")).isEqualTo(sessionId(userId, "WEB"));
    assertThat(current.get("client")).isEqualTo("WEB");
    assertThat(current.get("current")).isEqualTo(true);

    // A outra sessão é a da TUI, com origem e ciclo de vida completos.
    Map<String, Object> other = sessions.get(1);
    assertThat(other.get("id")).isEqualTo(sessionId(userId, "TUI"));
    assertThat(other.get("client")).isEqualTo("TUI");
    assertThat(other.get("current")).isEqualTo(false);
    assertThat(other.get("ip")).isNotNull();
    assertThat(other.get("userAgent")).isEqualTo(USER_AGENT);
    assertThat(other.get("createdAt")).isNotNull();
    assertThat(other.get("lastSeenAt")).isNotNull();
    assertThat(other.get("expiresAt")).isNotNull();

    // Nem o token em claro nem hash de senha/token aparecem na listagem.
    assertThat(response.asString())
        .doesNotContain(tuiToken)
        .doesNotContain(webToken)
        .doesNotContain("$argon2");
  }

  @Test
  @DisplayName(
      "DELETE /api/v1/auth/sessions/{id} revoga a sessão de outro dispositivo e o token dela cai")
  void revokesOtherSession() throws SQLException {
    String username = "sessoes.revoga." + SUFFIX;
    String id = createUser(username, "Sessoes Revoga", null);
    String tuiToken = login(username, PASSWORD, "TUI", null).jsonPath().getString("token");
    String webToken = login(username, PASSWORD, "WEB", null).jsonPath().getString("token");
    UUID userId = UUID.fromString(id);
    String tuiSessionId = sessionId(userId, "TUI");

    Response revoke =
        given()
            .header(AUTHORIZATION, "Bearer " + webToken)
            .when()
            .delete(SESSIONS_PATH + "/" + tuiSessionId)
            .then()
            .extract()
            .response();

    assertThat(revoke.statusCode()).isEqualTo(204);
    assertThat(revoke.asString()).isEmpty();

    // A sessão alvo fica revogada com o motivo SESSION_REVOKED — não é apagada nem vira erro.
    UUID target = UUID.fromString(tuiSessionId);
    assertThat(sessionValue(target, "revoked_reason")).isEqualTo("SESSION_REVOKED");
    assertThat(sessionValue(target, "revoked_at")).isNotNull();

    // O token derrubado deixa de autenticar; o que pediu a revogação segue vivo.
    assertThat(me(tuiToken).statusCode()).isEqualTo(401);
    assertThat(me(webToken).statusCode()).isEqualTo(200);

    // A lista já não mostra a sessão revogada.
    Response list =
        given()
            .header(AUTHORIZATION, "Bearer " + webToken)
            .when()
            .get(SESSIONS_PATH)
            .then()
            .extract()
            .response();
    assertThat(list.jsonPath().getList("$")).hasSize(1);
    assertThat(list.jsonPath().getString("[0].client")).isEqualTo("WEB");
  }

  @Test
  @DisplayName(
      "revogar a própria sessão atual derruba o token junto; repetir ou revogar id desconhecido é"
          + " 204 idempotente")
  void revokesCurrentSessionIdempotently() throws SQLException {
    String username = "sessoes.atual." + SUFFIX;
    String id = createUser(username, "Sessoes Atual", null);
    String tuiToken = login(username, PASSWORD, "TUI", null).jsonPath().getString("token");
    String webToken = login(username, PASSWORD, "WEB", null).jsonPath().getString("token");
    UUID userId = UUID.fromString(id);

    // Revogar a sessão atual (a da WEB) é permitido: o cliente cai junto.
    assertThat(delete(SESSIONS_PATH + "/" + sessionId(userId, "WEB"), webToken).statusCode())
        .isEqualTo(204);
    assertThat(me(webToken).statusCode()).isEqualTo(401);
    assertThat(sessionValue(UUID.fromString(sessionId(userId, "WEB")), "revoked_reason"))
        .isEqualTo("SESSION_REVOKED");

    // Com a TUI viva: revogar a já revogada e um id desconhecido é 204, sem derrubar mais nada.
    assertThat(delete(SESSIONS_PATH + "/" + sessionId(userId, "WEB"), tuiToken).statusCode())
        .isEqualTo(204);
    assertThat(delete(SESSIONS_PATH + "/" + UUID.randomUUID(), tuiToken).statusCode())
        .isEqualTo(204);
    assertThat(me(tuiToken).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("DELETE de sessão de outro usuário responde 404 e não derruba a sessão dele")
  void rejectsRevokingAnotherUsersSession() throws SQLException {
    String usernameA = "sessoes.dono-a." + SUFFIX;
    String usernameB = "sessoes.dono-b." + SUFFIX;
    createUser(usernameA, "Dono A", null);
    String idB = createUser(usernameB, "Dono B", null);
    String tokenA = login(usernameA, PASSWORD, "TUI", null).jsonPath().getString("token");
    String tokenB = login(usernameB, PASSWORD, "WEB", null).jsonPath().getString("token");
    UUID sessionB = UUID.fromString(sessionId(UUID.fromString(idB), "WEB"));

    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + tokenA)
            .when()
            .delete(SESSIONS_PATH + "/" + sessionB)
            .then()
            .extract()
            .response();

    // 404 e não 403: a existência da sessão alheia não é revelada (§6.3.4).
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("NOT_FOUND");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Recurso não encontrado");

    // A sessão do outro usuário segue viva e autenticando.
    assertThat(sessionValue(sessionB, "revoked_at")).isNull();
    assertThat(me(tokenB).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("GET e DELETE /api/v1/auth/sessions sem token respondem 401 problem+json")
  void rejectsSessionsWithoutToken() {
    String target = SESSIONS_PATH + "/" + UUID.randomUUID();

    Response list = given().when().get(SESSIONS_PATH).then().extract().response();
    Response revoke = given().when().delete(target).then().extract().response();

    assertThat(list.statusCode()).isEqualTo(401);
    assertThat(list.contentType()).contains("application/problem+json");
    assertThat(list.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(list.jsonPath().getString("instance")).isEqualTo(SESSIONS_PATH);

    assertThat(revoke.statusCode()).isEqualTo(401);
    assertThat(revoke.contentType()).contains("application/problem+json");
    assertThat(revoke.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(revoke.jsonPath().getString("instance")).isEqualTo(target);
  }

  /**
   * O request HTTP commita, então o que este teste cria é removido ao fim de cada teste: os testes
   * de repositório assumem as tabelas como as encontraram. As FKs são {@code on delete restrict},
   * por isso sessões e papéis saem antes do usuário.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "delete from auth_sessions where user_id in"
                  + " (select id from users where username like ?)")) {
        statement.setString(1, "%" + SUFFIX);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "delete from user_roles where user_id in (select id from users where username like ?)")) {
        statement.setString(1, "%" + SUFFIX);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement("delete from users where username like ?")) {
        statement.setString(1, "%" + SUFFIX);
        statement.executeUpdate();
      }
    }
  }

  /** Cria o usuário pelo caminho que já existe ({@code POST /api/v1/users}) e devolve o id. */
  private static String createUser(String username, String displayName, String roleCode) {
    String roleCodes = roleCode == null ? "" : ", \"roleCodes\": [\"%s\"]".formatted(roleCode);
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "%s"%s}
            """
                .formatted(username, displayName, PASSWORD, roleCodes))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  /** POST de login sem assert de status: cada teste confere o seu (200, 401, 400 ou 423). */
  private static Response login(
      String username, String password, String client, String cashRegisterId) {
    String body =
        cashRegisterId == null
            ? """
              {"username": "%s", "password": "%s"}
              """
                .formatted(username, password)
            : """
              {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
              """
                .formatted(username, password, cashRegisterId);
    RequestSpecification request =
        given().contentType("application/json").header("User-Agent", USER_AGENT).body(body);
    if (client != null) {
      request = request.header(AuthResource.CLIENT_HEADER, client);
    }
    return request.when().post(LOGIN_PATH).then().extract().response();
  }

  /** GET no /auth/me com o token informado; cada teste confere o status esperado. */
  private static Response me(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(ME_PATH)
        .then()
        .extract()
        .response();
  }

  /** DELETE da sessão com o token informado; cada teste confere o status esperado. */
  private static Response delete(String path, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(path)
        .then()
        .extract()
        .response();
  }

  /** Id da sessão mais recente do usuário com o cliente informado (TUI/WEB). */
  private String sessionId(UUID userId, String client) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id from auth_sessions where user_id = ?::uuid and client = ?"
                    + " order by created_at desc limit 1")) {
      statement.setString(1, userId.toString());
      statement.setString(2, client);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  /** Coluna da sessão pelo id; {@code null} quando a sessão não existe. */
  private String sessionValue(UUID sessionId, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select " + column + " from auth_sessions where id = ?::uuid")) {
      statement.setString(1, sessionId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private String sessionColumn(UUID userId, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select "
                    + column
                    + " from auth_sessions where user_id = ?::uuid order by created_at desc")) {
      statement.setString(1, userId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private int sessionCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from auth_sessions where user_id = ?::uuid")) {
      statement.setString(1, userId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }
}
