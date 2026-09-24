package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.application.LoginRateLimiter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Troca da própria senha (passo 214) contra PostgreSQL real (Dev Services): exige a senha atual,
 * aplica a política da nova, limpa {@code mustChangePassword} e derruba as outras sessões do
 * usuário sem tocar na atual. O request HTTP commita de verdade: cada teste usa um sufixo único da
 * execução e o {@link #removeUsersCreatedByThisRun()} apaga sessões, papéis e usuários ao fim de
 * cada um — as FKs de {@code auth_sessions} e {@code user_roles} são {@code on delete restrict}.
 */
@QuarkusTest
class ChangeOwnPasswordTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String NEW_PASSWORD = "senha-nova-secreta";
  private static final String TEMP_PASSWORD = "senha-temporaria";
  private static final String AUTHORIZATION = "Authorization";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String ME_PATH = "/api/v1/auth/me";
  private static final String PASSWORD_PATH = "/api/v1/auth/password";

  /** Motivo gravado em {@code revoked_reason} das sessões derrubadas pela troca (passo 214). */
  private static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

  /**
   * Endereços de onde o RestAssured chega (o {@code localhost} da URL de teste pode resolver para
   * IPv4 ou IPv6): limpar os dois devolve o limitador de login ao estado inicial entre testes.
   */
  private static final InetAddress LOOPBACK_V4 = InetAddress.ofLiteral("127.0.0.1");

  private static final InetAddress LOOPBACK_V6 = InetAddress.ofLiteral("::1");

  /** Estado global do processo (passo 212): cada teste desta classe começa sem contagem. */
  @Inject LoginRateLimiter loginRateLimiter;

  @Test
  @DisplayName(
      "trocar a senha derruba as outras sessões e mantém a atual; a senha nova passa a logar")
  void changesPasswordAndKeepsCurrentSession() throws SQLException {
    String username = "troca.ok." + SUFFIX;
    UUID userId = UUID.fromString(createUser(username, "Troca Ok"));
    String tuiToken = tokenOf(login(username, PASSWORD, "TUI"));
    String webToken = tokenOf(login(username, PASSWORD, "WEB"));
    String oldHash = userColumn(userId, "password_hash");

    Response response = changePassword(webToken, PASSWORD, NEW_PASSWORD);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.asString()).isEmpty();

    // A sessão que trocou a senha continua viva; a outra caiu com o motivo da troca.
    assertThat(me(webToken).statusCode()).isEqualTo(200);
    assertThat(me(tuiToken).statusCode()).isEqualTo(401);
    assertThat(sessionColumn(userId, "TUI", "revoked_reason")).isEqualTo(PASSWORD_CHANGED);
    assertThat(sessionColumn(userId, "WEB", "revoked_reason")).isNull();

    // O hash é outro (Argon2id) e o carimbo da troca foi gravado.
    assertThat(userColumn(userId, "password_hash")).startsWith("$argon2").isNotEqualTo(oldHash);
    assertThat(userColumn(userId, "password_changed_at")).isNotNull();

    // A senha nova autentica; a antiga, não.
    assertThat(login(username, NEW_PASSWORD, null).statusCode()).isEqualTo(200);
    Response oldPassword = login(username, PASSWORD, null);
    assertThat(oldPassword.statusCode()).isEqualTo(401);
    assertThat(oldPassword.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("senha atual errada responde 400 INVALID_CURRENT_PASSWORD e não muda nada")
  void rejectsWrongCurrentPassword() throws SQLException {
    String username = "troca.errada." + SUFFIX;
    UUID userId = UUID.fromString(createUser(username, "Troca Errada"));
    String tuiToken = tokenOf(login(username, PASSWORD, "TUI"));
    String webToken = tokenOf(login(username, PASSWORD, "WEB"));
    String oldHash = userColumn(userId, "password_hash");

    Response response = changePassword(tuiToken, "senha-errada", NEW_PASSWORD);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/invalid-current-password");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Senha atual inválida");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CURRENT_PASSWORD");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(PASSWORD_PATH);
    // A mensagem não revela o username nem nada além da senha atual incorreta.
    assertThat(response.jsonPath().getString("detail")).doesNotContain(username);

    // Nada mudou: hash antigo, nenhuma sessão revogada e os dois tokens seguem valendo.
    assertThat(userColumn(userId, "password_hash")).isEqualTo(oldHash);
    assertThat(me(tuiToken).statusCode()).isEqualTo(200);
    assertThat(me(webToken).statusCode()).isEqualTo(200);
    assertThat(revokedSessions(userId)).isZero();
    assertThat(login(username, PASSWORD, null).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("senha nova curta e corpo ausente respondem 400 de validação")
  void rejectsInvalidNewPassword() throws SQLException {
    String username = "troca.curta." + SUFFIX;
    UUID userId = UUID.fromString(createUser(username, "Troca Curta"));
    String token = tokenOf(login(username, PASSWORD, "TUI"));

    Response shortPassword = changePassword(token, PASSWORD, "curta");

    assertThat(shortPassword.statusCode()).isEqualTo(400);
    assertThat(shortPassword.contentType()).contains("application/problem+json");
    assertThat(shortPassword.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(shortPassword.jsonPath().getList("errors.field", String.class))
        .containsExactly("newPassword");

    // Sem corpo é erro de forma, não erro interno.
    Response missingBody =
        given()
            .contentType("application/json")
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .post(PASSWORD_PATH)
            .then()
            .extract()
            .response();
    assertThat(missingBody.statusCode()).isEqualTo(400);
    assertThat(missingBody.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");

    // A senha antiga segue valendo e nenhuma sessão foi revogada.
    assertThat(userColumn(userId, "password_hash")).startsWith("$argon2");
    assertThat(me(token).statusCode()).isEqualTo(200);
    assertThat(revokedSessions(userId)).isZero();
  }

  @Test
  @DisplayName(
      "a troca limpa mustChangePassword do reset por ADMIN e a senha nova loga sem exigir troca")
  void clearsMustChangePasswordAfterAdminReset() throws SQLException {
    String username = "troca.temp." + SUFFIX;
    UUID userId = UUID.fromString(createUser(username, "Troca Temp"));
    resetPassword(userId, TEMP_PASSWORD);
    assertThat(userFlag(userId, "must_change_password")).isTrue();

    Response login = login(username, TEMP_PASSWORD, "TUI");
    assertThat(login.jsonPath().getBoolean("mustChangePassword")).isTrue();
    String token = login.jsonPath().getString("token");

    assertThat(changePassword(token, TEMP_PASSWORD, NEW_PASSWORD).statusCode()).isEqualTo(204);

    // A exigência de troca some no banco, a sessão atual segue viva e a senha nova loga sem pedir
    // troca de novo.
    assertThat(userFlag(userId, "must_change_password")).isFalse();
    assertThat(me(token).statusCode()).isEqualTo(200);
    Response newLogin = login(username, NEW_PASSWORD, null);
    assertThat(newLogin.statusCode()).isEqualTo(200);
    assertThat(newLogin.jsonPath().getBoolean("mustChangePassword")).isFalse();
  }

  @Test
  @DisplayName("POST /api/v1/auth/password sem token responde 401 problem+json")
  void rejectsWithoutToken() {
    Response response =
        given()
            .contentType("application/json")
            .body(
                """
                {"currentPassword": "%s", "newPassword": "%s"}
                """
                    .formatted(PASSWORD, NEW_PASSWORD))
            .when()
            .post(PASSWORD_PATH)
            .then()
            .extract()
            .response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.jsonPath().getString("instance")).isEqualTo(PASSWORD_PATH);
  }

  /**
   * O request HTTP commita, então o que este teste cria é removido ao fim de cada teste: os testes
   * de repositório assumem as tabelas como as encontraram. As FKs são {@code on delete restrict},
   * por isso sessões e papéis saem antes do usuário.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    // O limitador de login é estado global do processo (passo 212): a senha antiga recusada nos
    // testes não pode chegar aos seguintes.
    loginRateLimiter.recordSuccess(LOOPBACK_V4);
    loginRateLimiter.recordSuccess(LOOPBACK_V6);
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

  /** Cria o usuário pela API com o token do ADMIN da fixture (passo 307a) e devolve o id. */
  private String createUser(String username, String displayName) {
    return asAdmin()
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

  /** Reset de senha por ADMIN (passo 113): deixa o usuário com {@code mustChangePassword=true}. */
  private void resetPassword(UUID userId, String newPassword) {
    asAdmin()
        .contentType("application/json")
        .body(
            """
            {"newPassword": "%s"}
            """
                .formatted(newPassword))
        .when()
        .post("/api/v1/users/{id}/password-reset", userId)
        .then()
        .statusCode(200);
  }

  /** POST de login sem assert de status: cada teste confere o seu (200 ou 401). */
  private static Response login(String username, String password, String client) {
    var request =
        given()
            .contentType("application/json")
            .body(
                """
                {"username": "%s", "password": "%s"}
                """
                    .formatted(username, password));
    if (client != null) {
      request = request.header(AuthResource.CLIENT_HEADER, client);
    }
    return request.when().post(LOGIN_PATH).then().extract().response();
  }

  /** Token da resposta de login que cada teste já validou. */
  private static String tokenOf(Response login) {
    return login.jsonPath().getString("token");
  }

  /** POST da troca de senha com o token informado; cada teste confere o status esperado. */
  private static Response changePassword(String token, String currentPassword, String newPassword) {
    return given()
        .contentType("application/json")
        .header(AUTHORIZATION, "Bearer " + token)
        .body(
            """
            {"currentPassword": "%s", "newPassword": "%s"}
            """
                .formatted(currentPassword, newPassword))
        .when()
        .post(PASSWORD_PATH)
        .then()
        .extract()
        .response();
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

  /** Coluna do usuário pelo id; {@code null} quando o valor for nulo. */
  private String userColumn(UUID userId, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select " + column + " from users where id = ?::uuid")) {
      statement.setString(1, userId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getString(1);
      }
    }
  }

  /** Coluna booleana do usuário pelo id (o driver devolve {@code t}/{@code f} em texto). */
  private boolean userFlag(UUID userId, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select " + column + " from users where id = ?::uuid")) {
      statement.setString(1, userId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getBoolean(1);
      }
    }
  }

  /** Coluna da sessão mais recente do usuário com o cliente informado (TUI/WEB). */
  private String sessionColumn(UUID userId, String client, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select "
                    + column
                    + " from auth_sessions where user_id = ?::uuid and client = ?"
                    + " order by created_at desc limit 1")) {
      statement.setString(1, userId.toString());
      statement.setString(2, client);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  /** Sessões revogadas do usuário, qualquer motivo — a troca recusada não pode revogar nenhuma. */
  private int revokedSessions(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from auth_sessions where user_id = ?::uuid"
                    + " and revoked_at is not null")) {
      statement.setString(1, userId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }
}
