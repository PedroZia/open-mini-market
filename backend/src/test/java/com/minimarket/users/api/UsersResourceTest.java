package com.minimarket.users.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

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
 * API do {@code POST /api/v1/users} contra PostgreSQL real (Dev Services). Diferente dos testes de
 * repositório, aqui o request HTTP commita de verdade: cada teste usa um sufixo único da execução e
 * o {@link #removeUsersCreatedByThisRun()} apaga o que foi criado ao fim de cada um, para não
 * sobrar linha para os testes de repositório nem para a próxima execução.
 */
@QuarkusTest
class UsersResourceTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  @Test
  @DisplayName("POST /api/v1/users responde 201 com Location, roles e status, sem vazar credencial")
  void createsUser() throws SQLException {
    String username = "maria.silva." + SUFFIX;

    Response response =
        given()
            .contentType("application/json")
            .body(
                """
                {"username": " Maria.Silva.%s ", "displayName": "Maria Silva",
                 "password": "senha-secreta", "roleCodes": ["OPERADOR"]}
                """
                    .formatted(SUFFIX))
            .when()
            .post("/api/v1/users")
            .then()
            .statusCode(201)
            .contentType(containsString("application/json"))
            .extract()
            .response();

    String id = response.jsonPath().getString("id");
    assertThat(UUID.fromString(id)).isNotNull();
    assertThat(response.getHeader("Location")).endsWith("/api/v1/users/" + id);
    assertThat(response.jsonPath().getString("username")).isEqualTo(username);
    assertThat(response.jsonPath().getString("displayName")).isEqualTo("Maria Silva");
    assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.asString()).doesNotContain("senha-secreta").doesNotContain("$argon2");

    assertThat(passwordHashOf(username)).startsWith("$argon2id$").doesNotContain("senha-secreta");
  }

  @Test
  @DisplayName("username duplicado responde 409 USERNAME_ALREADY_EXISTS e não cria segunda linha")
  void rejectsDuplicateUsername() throws SQLException {
    String username = "joao.pereira." + SUFFIX;
    createUser(username, "João Pereira", "senha-secreta");

    given()
        .contentType("application/json")
        .body(
            """
            {"username": " JOAO.PEREIRA.%s ", "displayName": "João Pereira",
             "password": "outra-senha-secreta"}
            """
                .formatted(SUFFIX))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(409)
        .contentType(containsString("application/problem+json"))
        .body("type", equalTo("https://minimarket.local/problems/username-already-exists"))
        .body("title", equalTo("Username já está em uso"))
        .body("status", equalTo(409))
        .body("code", equalTo("USERNAME_ALREADY_EXISTS"));

    assertThat(countUsers(username)).isEqualTo(1);
  }

  @Test
  @DisplayName("senha curta responde 400 com errors[] no campo password e não cria usuário")
  void rejectsShortPassword() throws SQLException {
    String username = "curta.senha." + SUFFIX;

    Response response =
        given()
            .contentType("application/json")
            .body(
                """
                {"username": "%s", "displayName": "Curta Senha", "password": "1234567"}
                """
                    .formatted(username))
            .when()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .body("code", equalTo("VALIDATION_ERROR"))
            .extract()
            .response();

    assertThat(response.jsonPath().getList("errors.field", String.class))
        .containsExactly("password");
    assertThat(response.jsonPath().getString("errors[0].message")).contains("8 caracteres");
    assertThat(countUsers(username)).isZero();
  }

  @Test
  @DisplayName("campos obrigatórios em branco respondem 400 com errors[] em cada campo")
  void rejectsBlankRequiredFields() {
    Response response =
        given()
            .contentType("application/json")
            .body(
                """
                {"username": "  ", "displayName": "", "password": "senha-secreta"}
                """)
            .when()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .body("code", equalTo("VALIDATION_ERROR"))
            .extract()
            .response();

    assertThat(response.jsonPath().getList("errors.field", String.class))
        .containsExactlyInAnyOrder("username", "displayName");
  }

  /**
   * O request HTTP commita, então os usuários criados aqui são removidos ao fim de cada teste: os
   * testes de repositório assumem a tabela como a encontraram. A FK de {@code user_roles} é {@code
   * on delete restrict}, por isso as linhas de papel saem antes.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
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

  private static void createUser(String username, String displayName, String password) {
    given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "%s"}
            """
                .formatted(username, displayName, password))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(201);
  }

  private String passwordHashOf(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select password_hash from users where username = ?")) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private int countUsers(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from users where username = ?")) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }
}
