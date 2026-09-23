package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.domain.TokenHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Autenticação bearer contra PostgreSQL real (Dev Services): a política de {@code %test} protege só
 * {@link TestIdentityResource#PATH}, então cada teste prova um caminho do mecanismo. O request HTTP
 * commita de verdade e o {@link #removeUsersCreatedByThisRun()} limpa sessões, papéis e usuários ao
 * fim de cada teste (as FKs de {@code auth_sessions} e {@code user_roles} são {@code on delete
 * restrict}).
 */
@QuarkusTest
class BearerAuthenticationTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String IDENTITY_PATH = TestIdentityResource.PATH;
  private static final String META_PATH = "/api/v1/meta";
  private static final String AUTHORIZATION = "Authorization";

  /** Domínio puro, sem estado e sem CDI (passo 203): o teste instancia o hash do token. */
  private final TokenHasher tokenHasher = new TokenHasher();

  @Test
  @DisplayName("sem token o path protegido responde 401 problem+json com INVALID_CREDENTIALS")
  void rejectsMissingToken() {
    Response response = given().when().get(IDENTITY_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Credenciais inválidas");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(IDENTITY_PATH);
    assertThat(response.jsonPath().getString("traceId")).isNotBlank();
    // O filtro de correlação é do JAX-RS e não roda no challenge: o mecanismo ecoa o id aqui.
    assertThat(response.getHeader("X-Request-Id"))
        .isEqualTo(response.jsonPath().getString("traceId"));
  }

  @Test
  @DisplayName("token desconhecido responde 401 problem+json sem enumerar o motivo")
  void rejectsUnknownToken() {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer token-que-nunca-existiu")
            .when()
            .get(IDENTITY_PATH)
            .then()
            .extract()
            .response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("token de sessão revogada responde 401 INVALID_CREDENTIALS")
  void rejectsRevokedToken() throws SQLException {
    String username = "bearer.revogado." + SUFFIX;
    createUser(username, null);
    String token = login(username);

    revokeSession(tokenHasher.hash(token));

    Response response = getIdentity(token);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("token de sessão expirada responde 401 SESSION_EXPIRED")
  void rejectsExpiredToken() throws SQLException {
    String username = "bearer.expirado." + SUFFIX;
    createUser(username, null);
    String token = login(username);

    expireSession(tokenHasher.hash(token));

    Response response = getIdentity(token);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SESSION_EXPIRED");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Sessão expirada");
  }

  @Test
  @DisplayName("token válido devolve a identidade com usuário, roles, permissões e id da sessão")
  void exposesIdentityForValidToken() throws SQLException {
    String username = "bearer.ok." + SUFFIX;
    createUser(username, "OPERADOR");
    String token = login(username);

    Response response = getIdentity(token);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("username")).isEqualTo(username);
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.jsonPath().getList("permissions", String.class))
        .contains("sale.create", "payment.add", "cash.open");
    assertThat(response.jsonPath().getString("sessionId")).isEqualTo(sessionId(token));
  }

  @Test
  @DisplayName("last_seen_at é atualizado no máximo 1x/min: requisição recente não gera write")
  void touchesLastSeenAtMostOncePerMinute() throws SQLException {
    String username = "bearer.touch." + SUFFIX;
    createUser(username, null);
    String token = login(username);
    Instant seenAtAfterLogin = lastSeenAt(tokenHasher.hash(token));

    assertThat(getIdentity(token).statusCode()).isEqualTo(200);
    // O login acabou de gravar last_seen_at: a requisição não pode tocar de novo.
    assertThat(lastSeenAt(tokenHasher.hash(token))).isEqualTo(seenAtAfterLogin);

    // Sessão parada há mais de 1 min volta a ser tocada na próxima requisição.
    ageLastSeen(tokenHasher.hash(token));
    assertThat(getIdentity(token).statusCode()).isEqualTo(200);
    assertThat(lastSeenAt(tokenHasher.hash(token))).isAfter(seenAtAfterLogin);
  }

  @Test
  @DisplayName("GET /api/v1/meta continua 200 sem token: a janela da Fase 1 não foi fechada")
  void keepsUnprotectedRoutesOpen() {
    Response response = given().when().get(META_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("storeCode")).isEqualTo("MATRIZ");
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
  private static String createUser(String username, String roleCode) {
    String roleCodes = roleCode == null ? "" : ", \"roleCodes\": [\"%s\"]".formatted(roleCode);
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "%s"%s}
            """
                .formatted(username, username, PASSWORD, roleCodes))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
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

  private static Response getIdentity(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(IDENTITY_PATH)
        .then()
        .extract()
        .response();
  }

  private void expireSession(String tokenHash) throws SQLException {
    execute(
        "update auth_sessions set expires_at = now() - interval '1 minute' where token_hash = ?",
        tokenHash);
  }

  private void revokeSession(String tokenHash) throws SQLException {
    execute(
        "update auth_sessions set revoked_at = now(), revoked_reason = 'LOGOUT'"
            + " where token_hash = ?",
        tokenHash);
  }

  /** Empurra last_seen_at para trás para simular sessão parada há mais de 1 min. */
  private void ageLastSeen(String tokenHash) throws SQLException {
    execute(
        "update auth_sessions set last_seen_at = now() - interval '2 minutes' where token_hash = ?",
        tokenHash);
  }

  private Instant lastSeenAt(String tokenHash) throws SQLException {
    return instant("select last_seen_at from auth_sessions where token_hash = ?", tokenHash);
  }

  private String sessionId(String token) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id from auth_sessions where token_hash = ? order by created_at desc")) {
      statement.setString(1, tokenHasher.hash(token));
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getString(1);
      }
    }
  }

  private void execute(String sql, String parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      statement.executeUpdate();
    }
  }

  private Instant instant(String sql, String parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        Timestamp timestamp = resultSet.getTimestamp(1);
        return timestamp.toInstant();
      }
    }
  }
}
