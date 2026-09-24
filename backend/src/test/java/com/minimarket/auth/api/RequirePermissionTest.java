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
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Porteiro declarativo contra PostgreSQL real (Dev Services): cria OPERADOR e GERENTE pelo caso de
 * uso (a API de criar usuário exige token desde o passo 307a e aqui o usuário é fixture), faz login
 * e confere o 403/200 do {@link TestRequirePermissionResource}. O request HTTP commita de verdade e
 * o {@link #removeUsersCreatedByThisRun()} limpa sessões, papéis e usuários ao fim de cada teste
 * (as FKs são {@code on delete restrict}).
 */
@QuarkusTest
class RequirePermissionTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String MANAGEMENT_PATH = TestRequirePermissionResource.PATH;
  private static final String AUDIT_PATH = MANAGEMENT_PATH + "/audit";
  private static final String AUTHORIZATION = "Authorization";

  /** Caso de uso da criação de usuário (passo 107): a fixture nasce por aqui, não pela API. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName("OPERADOR sem a permissão do método recebe 403 ACCESS_DENIED em problem+json")
  void deniesOperatorWithoutMethodPermission() {
    String username = "perm.operador." + SUFFIX;
    createUser(username, "OPERADOR");

    Response response = get(login(username), AUDIT_PATH);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Acesso negado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(403);
    assertThat(response.jsonPath().getString("instance")).isEqualTo(AUDIT_PATH);
    // A permissão citada é a do método: se a da classe tivesse vencido, seria user.write.
    assertThat(response.jsonPath().getString("detail")).contains("audit.read");
    assertThat(response.jsonPath().getString("traceId")).isNotBlank();
  }

  @Test
  @DisplayName("GERENTE com a permissão do método passa e recebe 200 com a própria identidade")
  void allowsManagerWithMethodPermission() {
    String username = "perm.gerente." + SUFFIX;
    createUser(username, "GERENTE");

    Response response = get(login(username), AUDIT_PATH);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("username")).isEqualTo(username);
  }

  @Test
  @DisplayName(
      "permissão da classe vale para o método sem anotação: GERENTE recebe 403 em user.write")
  void deniesManagerOnClassPermission() {
    String username = "perm.classe." + SUFFIX;
    createUser(username, "GERENTE");

    Response response = get(login(username), MANAGEMENT_PATH);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("user.write");
  }

  @Test
  @DisplayName("sem token o endpoint anotado responde 401 problem+json")
  void rejectsMissingToken() {
    Response response = given().when().get(AUDIT_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  /**
   * O request HTTP commita, então o que este teste cria é removido ao fim de cada um: as FKs são
   * {@code on delete restrict}, por isso sessões e papéis saem antes do usuário.
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

  /** Cria o usuário pelo caso de uso (passo 107) com o papel pedido. */
  private void createUser(String username, String roleCode) {
    createUserUseCase.execute(
        new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)));
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

  private static Response get(String token, String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(path)
        .then()
        .extract()
        .response();
  }
}
