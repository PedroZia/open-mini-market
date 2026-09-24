package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.application.LoginRateLimiter;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Eventos de auditoria da autenticação (passo 304) contra PostgreSQL real (Dev Services): cada
 * cenário — login, recusa de credencial, bloqueio, logout e revogação de sessão — gera o evento
 * esperado em {@code audit_events}, com ator, IP e {@code auth_session_id} quando houver. A
 * conferência é por SQL: o banco é a fonte de verdade do que comitou junto com o caso de uso.
 *
 * <p>O request HTTP commita de verdade e o log é append-only para a aplicação; o teste, conectado
 * como dono das tabelas, remove os eventos e os usuários que ele mesmo criou ao fim de cada teste.
 */
@QuarkusTest
class AuthAuditEventsTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String LOGOUT_PATH = "/api/v1/auth/logout";
  private static final String SESSIONS_PATH = "/api/v1/auth/sessions";
  private static final String AUTHORIZATION = "Authorization";

  /** Falhas que fecham o ciclo do lock (§6.3.1): o mesmo valor de configuração. */
  private static final int LOCK_ATTEMPTS = 5;

  /**
   * Endereços de onde o RestAssured chega: limpar os dois devolve o limitador ao estado inicial.
   */
  private static final InetAddress LOOPBACK_V4 = InetAddress.ofLiteral("127.0.0.1");

  private static final InetAddress LOOPBACK_V6 = InetAddress.ofLiteral("::1");

  /** Estado global do processo (passo 212): o teto de falhas por IP não vaza entre testes. */
  @Inject LoginRateLimiter loginRateLimiter;

  @Test
  @DisplayName("login bem-sucedido grava LOGIN_SUCCESS com ator, sessão nova, IP e origem TUI")
  void auditsSuccessfulLogin() throws SQLException {
    String username = "audita.login." + SUFFIX;
    String id = createUser(username, "Audita Login");
    UUID cashRegisterId = UUID.randomUUID();

    loginToken(username, PASSWORD, "TUI", cashRegisterId.toString());

    UUID userId = UUID.fromString(id);
    Event event = singleEvent("LOGIN_SUCCESS", userId);
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.actorUserId()).isEqualTo(id);
    assertThat(event.actorUsername()).isEqualTo(username);
    assertThat(event.authSessionId())
        .as("a sessão nova é a do token")
        .isEqualTo(sessionId(userId, "TUI"));
    assertThat(event.source()).isEqualTo("TUI");
    assertThat(event.storeId()).isNotNull();
    assertThat(event.cashRegisterId()).isEqualTo(cashRegisterId.toString());
    assertThat(event.ip()).isNotNull();
    assertThat(event.requestId()).isNotBlank();
  }

  @Test
  @DisplayName("credencial recusada grava LOGIN_FAILED sem ator, com o username tentado em details")
  void auditsRejectedCredentials() throws SQLException {
    String username = "audita.falha." + SUFFIX;
    String id = createUser(username, "Audita Falha");

    assertThat(login(username, "senha-errada", "WEB", null).statusCode()).isEqualTo(401);

    Event event = singleEvent("LOGIN_FAILED", UUID.fromString(id));
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.actorUserId()).as("a tentativa recusada não tem ator").isNull();
    assertThat(event.actorUsername()).isNull();
    assertThat(event.authSessionId()).isNull();
    assertThat(event.details()).contains("\"username\": \"" + username + "\"");
    assertThat(event.ip()).isNotNull();
  }

  @Test
  @DisplayName("conta bloqueada grava LOGIN_LOCKED, depois das falhas que gravam LOGIN_FAILED")
  void auditsLockedAccount() throws SQLException {
    String username = "audita.lock." + SUFFIX;
    String id = createUser(username, "Audita Lock");
    UUID userId = UUID.fromString(id);

    for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
      assertThat(login(username, "senha-errada", null, null).statusCode()).isEqualTo(401);
    }
    assertThat(login(username, PASSWORD, null, null).statusCode()).isEqualTo(423);

    // A tentativa que atinge o limite ainda responde 401 e audita LOGIN_FAILED; o 423 da seguinte
    // é que vira LOGIN_LOCKED — uma tentativa, um evento.
    assertThat(events("LOGIN_FAILED", userId)).hasSize(LOCK_ATTEMPTS);
    Event locked = singleEvent("LOGIN_LOCKED", userId);
    assertThat(locked.actorUserId()).isNull();
    assertThat(locked.authSessionId()).isNull();
    assertThat(locked.details()).contains("\"username\": \"" + username + "\"");
    assertThat(locked.ip()).isNotNull();
  }

  @Test
  @DisplayName("logout grava LOGOUT apontando a sessão encerrada, com o ator que saiu")
  void auditsLogout() throws SQLException {
    String username = "audita.logout." + SUFFIX;
    String id = createUser(username, "Audita Logout");
    String token = loginToken(username, PASSWORD, "TUI", null);
    UUID sessionId = UUID.fromString(sessionId(UUID.fromString(id), "TUI"));

    assertThat(post(LOGOUT_PATH, token).statusCode()).isEqualTo(204);

    Event event = singleEvent("LOGOUT", sessionId);
    assertThat(event.entityType()).isEqualTo("AUTH_SESSION");
    assertThat(event.actorUserId()).isEqualTo(id);
    assertThat(event.authSessionId()).isEqualTo(sessionId.toString());
    assertThat(event.source()).isEqualTo("TUI");
    assertThat(event.ip()).isNotNull();
  }

  @Test
  @DisplayName("revogar a sessão de outro dispositivo grava SESSION_REVOKED com a sessão alvo")
  void auditsSessionRevoked() throws SQLException {
    String username = "audita.revoga." + SUFFIX;
    String id = createUser(username, "Audita Revoga");
    String webToken = loginToken(username, PASSWORD, "WEB", null);
    login(username, PASSWORD, "TUI", null);
    UUID userId = UUID.fromString(id);
    UUID target = UUID.fromString(sessionId(userId, "TUI"));

    assertThat(delete(SESSIONS_PATH + "/" + target, webToken).statusCode()).isEqualTo(204);

    Event event = singleEvent("SESSION_REVOKED", target);
    assertThat(event.entityType()).isEqualTo("AUTH_SESSION");
    assertThat(event.actorUserId()).as("quem revogou foi o dono, pela outra sessão").isEqualTo(id);
    assertThat(event.authSessionId()).isEqualTo(sessionId(userId, "WEB"));
    assertThat(event.source()).isEqualTo("WEB");
    assertThat(event.ip()).isNotNull();
  }

  @Test
  @DisplayName(
      "corte em massa por ADMIN grava um único SESSION_REVOKED por corte, com motivo e contagem")
  void auditsMassSessionRevocation() throws SQLException {
    String username = "audita.corte." + SUFFIX;
    String id = createUser(username, "Audita Corte");
    login(username, PASSWORD, "TUI", null);
    login(username, PASSWORD, "WEB", null);
    UUID userId = UUID.fromString(id);

    // O corte em massa exige user.session.revoke (passo 307a): quem corta é o ADMIN da fixture, e o
    // ator do evento passa a ser ele, não o dono das sessões.
    assertThat(delete("/api/v1/users/" + id + "/sessions", adminToken()).statusCode())
        .isEqualTo(204);

    // Um evento por corte, apontando o usuário afetado: não há uma sessão única para apontar.
    Event event = singleEvent("SESSION_REVOKED", userId);
    assertThat(event.entityType()).isEqualTo("USER");
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);
    assertThat(event.reason()).isEqualTo("ADMIN_REVOKE");
    assertThat(event.details())
        .contains("\"reason\": \"ADMIN_REVOKE\"")
        .contains("\"revokedCount\": 2");
  }

  /**
   * O log é append-only para a aplicação; o teste, conectado como dono das tabelas, remove o que
   * ele mesmo comitou — os eventos saem antes das sessões e usuários que eles referenciam.
   */
  @AfterEach
  void removeEventsAndUsersCreatedByThisRun() throws SQLException {
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

  /** Cada teste começa com o limitador do IP zerado, como as demais suítes de auth. */
  @BeforeEach
  void clearLoginRateLimitBefore() {
    clearLoginRateLimit();
  }

  @AfterEach
  void clearLoginRateLimitAfter() {
    clearLoginRateLimit();
  }

  /** Devolve o limitador de login ao estado inicial: o estado global não vaza entre testes. */
  private void clearLoginRateLimit() {
    loginRateLimiter.recordSuccess(LOOPBACK_V4);
    loginRateLimiter.recordSuccess(LOOPBACK_V6);
  }

  /** Cria o usuário pela API com o token do ADMIN da fixture (passo 307a) e devolve o id. */
  private String createUser(String username, String displayName) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "%s", "roleCodes": ["OPERADOR"]}
            """
                .formatted(username, displayName, PASSWORD))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  /** Login pela API (passo 205) sem assert de status: cada teste confere o seu. */
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
    RequestSpecification request = given().contentType("application/json").body(body);
    if (client != null) {
      request = request.header(AuthResource.CLIENT_HEADER, client);
    }
    return request.when().post(LOGIN_PATH).then().extract().response();
  }

  /** Login que precisa dar certo e devolve o token em claro da sessão nova. */
  private static String loginToken(
      String username, String password, String client, String cashRegisterId) {
    Response response = login(username, password, client, cashRegisterId);
    assertThat(response.statusCode()).as("login de %s", username).isEqualTo(200);
    return response.jsonPath().getString("token");
  }

  private static Response post(String path, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .post(path)
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

  /** Eventos com a ação e o alvo informados, na ordem de gravação. */
  private List<Event> events(String action, UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, actor_user_id, actor_username, auth_session_id, store_id,"
                    + " cash_register_id, source, request_id, reason, details::text as details,"
                    + " host(ip) as ip from audit_events where action = ? and entity_id = ?::uuid"
                    + " order by id")) {
      statement.setString(1, action);
      statement.setString(2, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Event> events = new ArrayList<>();
        while (resultSet.next()) {
          events.add(
              new Event(
                  resultSet.getString("entity_type"),
                  resultSet.getString("actor_user_id"),
                  resultSet.getString("actor_username"),
                  resultSet.getString("auth_session_id"),
                  resultSet.getString("store_id"),
                  resultSet.getString("cash_register_id"),
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

  /** Evento único do cenário; o teste falha se o caso de uso gravou zero ou mais de um. */
  private Event singleEvent(String action, UUID entityId) throws SQLException {
    List<Event> events = events(action, entityId);
    assertThat(events).as("eventos %s para a entidade %s", action, entityId).hasSize(1);
    return events.getFirst();
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
        assertThat(resultSet.next()).as("sessão %s do usuário %s", client, userId).isTrue();
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
      String actorUserId,
      String actorUsername,
      String authSessionId,
      String storeId,
      String cashRegisterId,
      String source,
      String requestId,
      String reason,
      String details,
      String ip) {}
}
