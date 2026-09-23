package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Revogação automática de sessões (passo 213) contra PostgreSQL real (Dev Services): desativar o
 * usuário e resetar a senha derrubam todas as sessões vivas na mesma transação, e o ADMIN corta as
 * sessões do usuário pela API. O request HTTP commita de verdade: cada teste usa um sufixo único da
 * execução e o {@link #removeUsersCreatedByThisRun()} apaga sessões, papéis e usuários ao fim de
 * cada um — as FKs de {@code auth_sessions} e {@code user_roles} são {@code on delete restrict}.
 */
@QuarkusTest
class AutomaticSessionRevocationTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String ME_PATH = "/api/v1/auth/me";

  /** Motivos gravados em {@code revoked_reason} (passo 213). */
  private static final String USER_DISABLED = "USER_DISABLED";

  private static final String PASSWORD_RESET = "PASSWORD_RESET";
  private static final String ADMIN_REVOKE = "ADMIN_REVOKE";

  @Test
  @DisplayName("desativar o usuário revoga todas as sessões: o token cai já na requisição seguinte")
  void disablingUserRevokesAllSessions() throws SQLException {
    String username = "revoga.desativa." + SUFFIX;
    String id = createUser(username, "Revoga Desativa");
    String tuiToken = login(username, PASSWORD);
    String webToken = login(username, PASSWORD);
    assertThat(me(tuiToken).statusCode()).isEqualTo(200);
    assertThat(me(webToken).statusCode()).isEqualTo(200);

    Response disable = post("/api/v1/users/" + id + "/disable");

    assertThat(disable.statusCode()).isEqualTo(200);
    assertThat(disable.jsonPath().getString("status")).isEqualTo("DISABLED");

    // O acesso cai imediatamente: as sessões foram revogadas, não só o usuário desativado.
    Response rejected = me(webToken);
    assertThat(rejected.statusCode()).isEqualTo(401);
    assertThat(rejected.contentType()).contains("application/problem+json");
    assertThat(rejected.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(me(tuiToken).statusCode()).isEqualTo(401);
    assertThat(sessionsWithReason(UUID.fromString(id), USER_DISABLED)).isEqualTo(2);
  }

  @Test
  @DisplayName("usuário reativado volta a logar e o reset de senha derruba a sessão nova")
  void resettingPasswordRevokesSessionsAfterReactivation() throws SQLException {
    String username = "revoga.reativa." + SUFFIX;
    String id = createUser(username, "Revoga Reativa");
    String firstToken = login(username, PASSWORD);

    assertThat(post("/api/v1/users/" + id + "/disable").statusCode()).isEqualTo(200);
    assertThat(me(firstToken).statusCode()).isEqualTo(401);

    // Reativado, o login volta a funcionar e abre uma sessão nova.
    assertThat(post("/api/v1/users/" + id + "/enable").statusCode()).isEqualTo(200);
    String tokenAfterEnable = login(username, PASSWORD);
    assertThat(me(tokenAfterEnable).statusCode()).isEqualTo(200);

    resetPassword(id, "senha-temporaria");

    // A sessão aberta antes do reset morre; a senha temporária é a única que autentica.
    assertThat(me(tokenAfterEnable).statusCode()).isEqualTo(401);
    assertThat(sessionsWithReason(UUID.fromString(id), PASSWORD_RESET)).isEqualTo(1);
    assertThat(login(username, "senha-temporaria")).isNotBlank();
  }

  @Test
  @DisplayName("DELETE /api/v1/users/{id}/sessions derruba todas as sessões do usuário")
  void adminRevokesAllSessions() throws SQLException {
    String username = "revoga.admin." + SUFFIX;
    String id = createUser(username, "Revoga Admin");
    String tuiToken = login(username, PASSWORD);
    String webToken = login(username, PASSWORD);

    Response response = delete("/api/v1/users/" + id + "/sessions");

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.asString()).isEmpty();
    assertThat(me(tuiToken).statusCode()).isEqualTo(401);
    assertThat(me(webToken).statusCode()).isEqualTo(401);
    assertThat(sessionsWithReason(UUID.fromString(id), ADMIN_REVOKE)).isEqualTo(2);

    // Repetir é inofensivo: não há sessão viva para revogar de novo.
    assertThat(delete("/api/v1/users/" + id + "/sessions").statusCode()).isEqualTo(204);
  }

  @Test
  @DisplayName("DELETE /api/v1/users/{id}/sessions de id inexistente responde 404 USER_NOT_FOUND")
  void adminRevocationReturnsNotFoundForUnknownUser() {
    Response response = delete("/api/v1/users/" + UUID.randomUUID() + "/sessions");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/user-not-found");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Usuário não encontrado");
    assertThat(response.jsonPath().getString("code")).isEqualTo("USER_NOT_FOUND");
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
  private static String createUser(String username, String displayName) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "%s"}
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

  /** Login pela API (passo 205) e devolve o token em claro da sessão nova. */
  private static String login(String username, String password) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(username, password))
        .when()
        .post(LOGIN_PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** GET no {@code /auth/me} com o token informado; cada teste confere o status esperado. */
  private static Response me(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(ME_PATH)
        .then()
        .extract()
        .response();
  }

  private static Response post(String path) {
    return given().when().post(path).then().extract().response();
  }

  private static Response delete(String path) {
    return given().when().delete(path).then().extract().response();
  }

  private static void resetPassword(String id, String newPassword) {
    given()
        .contentType("application/json")
        .body(
            """
            {"newPassword": "%s"}
            """
                .formatted(newPassword))
        .when()
        .post("/api/v1/users/{id}/password-reset", id)
        .then()
        .statusCode(200);
  }

  /** Sessões do usuário já revogadas com o motivo informado, lidas direto do banco. */
  private int sessionsWithReason(UUID userId, String reason) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from auth_sessions where user_id = ?::uuid"
                    + " and revoked_at is not null and revoked_reason = ?")) {
      statement.setString(1, userId.toString());
      statement.setString(2, reason);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }
}
