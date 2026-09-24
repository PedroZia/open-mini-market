package com.minimarket.users.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.minimarket.IntegrationTestBase;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API do {@code POST /api/v1/users} contra PostgreSQL real (Dev Services). Diferente dos testes de
 * repositório, aqui o request HTTP commita de verdade: cada teste usa um sufixo único da execução e
 * o {@link #removeUsersCreatedByThisRun()} apaga o que foi criado ao fim de cada um, para não
 * sobrar linha para os testes de repositório nem para a próxima execução.
 *
 * <p>Todas as chamadas falam com o token do ADMIN da fixture ({@link #adminToken()}, passo 307a): a
 * API de usuários exige {@code user.read}/{@code user.write} desde que a política global fechou a
 * janela da Fase 1.
 */
@QuarkusTest
class UsersResourceTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  @Inject PasswordHasher passwordHasher;

  @Test
  @DisplayName("POST /api/v1/users responde 201 com Location, roles e status, sem vazar credencial")
  void createsUser() throws SQLException {
    String username = "maria.silva." + SUFFIX;

    Response response =
        asAdmin()
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
    assertThat(response.jsonPath().getBoolean("mustChangePassword")).isFalse();
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.asString()).doesNotContain("senha-secreta").doesNotContain("$argon2");

    assertThat(passwordHashOf(username)).startsWith("$argon2id$").doesNotContain("senha-secreta");
  }

  @Test
  @DisplayName("username duplicado responde 409 USERNAME_ALREADY_EXISTS e não cria segunda linha")
  void rejectsDuplicateUsername() throws SQLException {
    String username = "joao.pereira." + SUFFIX;
    createUser(username, "João Pereira", "senha-secreta");

    asAdmin()
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
        asAdmin()
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
        asAdmin()
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

  @Test
  @DisplayName(
      "GET /api/v1/users pagina 25 usuários: página 0/size 10 devolve 10 itens e totalItems=25")
  void listsWithPagination() {
    createListingUsers(25);

    Response firstPage = listUsers("search", SUFFIX, "page", "0", "size", "10");

    assertThat(firstPage.jsonPath().getList("items")).hasSize(10);
    assertThat(firstPage.jsonPath().getInt("page")).isZero();
    assertThat(firstPage.jsonPath().getInt("size")).isEqualTo(10);
    assertThat(firstPage.jsonPath().getInt("totalItems")).isEqualTo(25);
    assertThat(firstPage.jsonPath().getInt("totalPages")).isEqualTo(3);

    Response lastPage = listUsers("search", SUFFIX, "page", "2", "size", "10");
    assertThat(lastPage.jsonPath().getList("items")).hasSize(5);
  }

  @Test
  @DisplayName(
      "GET /api/v1/users busca por trecho do username ou do nome, sem diferenciar maiúsculas")
  void searchesByPartialName() {
    createUser("ana.souza." + SUFFIX, "Ana Souza", "senha-secreta");
    createUserWithRole("bruno.lima." + SUFFIX, "Bruno Lima " + SUFFIX, "OPERADOR");

    Response byUsername = listUsers("search", ("SOUZA." + SUFFIX).toUpperCase(Locale.ROOT));
    assertThat(byUsername.jsonPath().getList("items")).hasSize(1);
    assertThat(byUsername.jsonPath().getString("items[0].username"))
        .isEqualTo("ana.souza." + SUFFIX);
    assertThat(byUsername.jsonPath().getInt("totalItems")).isEqualTo(1);

    Response byDisplayName = listUsers("search", "lima " + SUFFIX);
    assertThat(byDisplayName.jsonPath().getList("items")).hasSize(1);
    assertThat(byDisplayName.jsonPath().getString("items[0].displayName"))
        .isEqualTo("Bruno Lima " + SUFFIX);
    assertThat(byDisplayName.jsonPath().getList("items[0].roles", String.class))
        .containsExactly("OPERADOR");
  }

  @Test
  @DisplayName("GET /api/v1/users com size=500 devolve size limitado a 100")
  void limitsSizeToMaximum() {
    createListingUsers(2);

    Response response = listUsers("search", SUFFIX, "size", "500");

    assertThat(response.jsonPath().getInt("size")).isEqualTo(100);
    assertThat(response.jsonPath().getList("items")).hasSize(2);
  }

  @Test
  @DisplayName("GET /api/v1/users ordena por displayName,desc")
  void sortsByDisplayNameDescending() {
    createUser("mario.costa." + SUFFIX, "Mario Costa", "senha-secreta");
    createUser("ana.souza." + SUFFIX, "Ana Souza", "senha-secreta");
    createUser("zeca.alves." + SUFFIX, "Zeca Alves", "senha-secreta");

    Response response = listUsers("search", SUFFIX, "sort", "displayName,desc");

    assertThat(response.jsonPath().getList("items.displayName", String.class))
        .containsExactly("Zeca Alves", "Mario Costa", "Ana Souza");
  }

  @Test
  @DisplayName("GET /api/v1/users com sort fora da whitelist ou direção inválida responde 400")
  void rejectsInvalidSort() {
    Response response = listUsersBadRequest("sort", "password,desc");

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(listUsersBadRequest("sort", "username,up").jsonPath().getString("code"))
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  @DisplayName("GET /api/v1/users com page negativo ou size menor que 1 responde 400")
  void rejectsInvalidPagination() {
    assertThat(listUsersBadRequest("page", "-1").jsonPath().getString("code"))
        .isEqualTo("VALIDATION_ERROR");
    assertThat(listUsersBadRequest("size", "0").jsonPath().getString("code"))
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  @DisplayName("GET /api/v1/users com filtro tipado inválido responde 400 citando o campo, não 404")
  void rejectsInvalidTypedFilters() {
    for (String[] invalid :
        List.of(
            new String[] {"active", "abc"},
            new String[] {"page", "primeira"},
            new String[] {"size", "muitos"})) {
      Response response = listUsersBadRequest(invalid[0], invalid[1]);

      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
      assertThat(response.jsonPath().getList("errors.field", String.class))
          .as("o 400 cita o campo %s", invalid[0])
          .containsExactly(invalid[0]);
    }
  }

  @Test
  @DisplayName("GET /api/v1/users não lista usuário soft-deletado")
  void hidesSoftDeletedUsers() throws SQLException {
    createListingUsers(3);
    softDeleteUser("lista02." + SUFFIX);

    Response response = listUsers("search", SUFFIX);

    assertThat(response.jsonPath().getInt("totalItems")).isEqualTo(2);
    assertThat(response.jsonPath().getList("items.username", String.class))
        .containsExactlyInAnyOrder("lista01." + SUFFIX, "lista03." + SUFFIX);
  }

  @Test
  @DisplayName("GET /api/v1/users sem resultado devolve items vazio e totalPages 0")
  void returnsEmptyPage() {
    Response response = listUsers("search", "ninguem." + SUFFIX);

    assertThat(response.jsonPath().getList("items")).isEmpty();
    assertThat(response.jsonPath().getInt("totalItems")).isZero();
    assertThat(response.jsonPath().getInt("totalPages")).isZero();
  }

  @Test
  @DisplayName("GET /api/v1/users/{id} devolve 200 com username, displayName, status e roles")
  void returnsUserDetail() {
    String id = createUserWithRole("detalhe." + SUFFIX, "Detalhe Usuario", "OPERADOR");

    Response response = getUser(id);

    assertThat(response.jsonPath().getString("id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("username")).isEqualTo("detalhe." + SUFFIX);
    assertThat(response.jsonPath().getString("displayName")).isEqualTo("Detalhe Usuario");
    assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.asString()).doesNotContain("$argon2");
  }

  @Test
  @DisplayName("GET /api/v1/users/{id} de id inexistente responde 404 USER_NOT_FOUND")
  void returnsNotFoundForUnknownId() {
    Response response = getUserExpectingNotFound(UUID.randomUUID().toString());

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/user-not-found");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Usuário não encontrado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("USER_NOT_FOUND");
  }

  @Test
  @DisplayName("GET /api/v1/users/{id} de usuário soft-deletado responde 404 USER_NOT_FOUND")
  void hidesSoftDeletedUserFromDetail() throws SQLException {
    String id = createUserWithRole("apagado." + SUFFIX, "Apagado", "OPERADOR");
    softDeleteUser("apagado." + SUFFIX);

    assertThat(getUserExpectingNotFound(id).jsonPath().getString("code"))
        .isEqualTo("USER_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "PUT /api/v1/users/{id} responde 200 e altera nome e roles, sem tocar em username e senha")
  void updatesDisplayNameAndRoles() throws SQLException {
    String id = createUserWithRole("edita." + SUFFIX, "Nome Antigo", "OPERADOR");
    String hashBefore = passwordHashOf("edita." + SUFFIX);

    Response response =
        asAdmin()
            .contentType("application/json")
            .body(
                """
                {"displayName": "Nome Novo", "roleCodes": ["GERENTE", "ADMIN"],
                 "username": "invasor.%s", "password": "senha-invasora"}
                """
                    .formatted(SUFFIX))
            .when()
            .put("/api/v1/users/{id}", id)
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .extract()
            .response();

    assertThat(response.jsonPath().getString("id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("username")).isEqualTo("edita." + SUFFIX);
    assertThat(response.jsonPath().getString("displayName")).isEqualTo("Nome Novo");
    assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getList("roles", String.class))
        .containsExactly("ADMIN", "GERENTE");
    assertThat(response.asString()).doesNotContain("senha-invasora").doesNotContain("$argon2");

    Response persisted = getUser(id);
    assertThat(persisted.jsonPath().getString("username")).isEqualTo("edita." + SUFFIX);
    assertThat(persisted.jsonPath().getString("displayName")).isEqualTo("Nome Novo");
    assertThat(persisted.jsonPath().getList("roles", String.class))
        .containsExactly("ADMIN", "GERENTE");
    assertThat(passwordHashOf("edita." + SUFFIX)).isEqualTo(hashBefore);

    // roleCodes vazio é válido e remove todos os papéis
    Response cleared =
        putUser(
            id,
            """
            {"displayName": "Nome Novo", "roleCodes": []}
            """,
            200);
    assertThat(cleared.jsonPath().getList("roles")).isEmpty();
    assertThat(getUser(id).jsonPath().getList("roles")).isEmpty();
  }

  @Test
  @DisplayName("PUT /api/v1/users/{id} de id inexistente responde 404 USER_NOT_FOUND")
  void returnsNotFoundWhenUpdatingUnknownId() {
    Response response =
        putUser(
            UUID.randomUUID().toString(),
            """
            {"displayName": "Ninguém", "roleCodes": ["OPERADOR"]}
            """,
            404);

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/user-not-found");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Usuário não encontrado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("USER_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "PUT /api/v1/users/{id} com role desconhecida responde 400 UNKNOWN_ROLE e não altera nada")
  void rejectsUnknownRoleWithoutChangingUser() {
    String id = createUserWithRole("role.invalida." + SUFFIX, "Nome Antigo", "OPERADOR");

    Response response =
        putUser(
            id,
            """
            {"displayName": "Nome Novo", "roleCodes": ["OPERADOR", "FANTASMA"]}
            """,
            400);

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/unknown-role");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Papel desconhecido");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
    assertThat(response.jsonPath().getString("code")).isEqualTo("UNKNOWN_ROLE");

    Response persisted = getUser(id);
    assertThat(persisted.jsonPath().getString("displayName")).isEqualTo("Nome Antigo");
    assertThat(persisted.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
  }

  @Test
  @DisplayName("PUT /api/v1/users/{id} com displayName em branco ou roleCodes ausente responde 400")
  void rejectsInvalidUpdateBody() {
    String id = createUserWithRole("corpo.invalido." + SUFFIX, "Nome Antigo", "OPERADOR");

    Response blankName =
        putUser(
            id,
            """
            {"displayName": "   ", "roleCodes": []}
            """,
            400);
    assertThat(blankName.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(blankName.jsonPath().getList("errors.field", String.class))
        .containsExactly("displayName");

    Response missingRoles =
        putUser(
            id,
            """
            {"displayName": "Nome Novo"}
            """,
            400);
    assertThat(missingRoles.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(missingRoles.jsonPath().getList("errors.field", String.class))
        .containsExactly("roleCodes");

    assertThat(getUser(id).jsonPath().getString("displayName")).isEqualTo("Nome Antigo");
  }

  @Test
  @DisplayName(
      "POST /api/v1/users/{id}/disable responde 200 DISABLED e tira o usuário da busca padrão")
  void disablesUser() throws SQLException {
    String id = createUserWithRole("desativa." + SUFFIX, "Desativa Usuario", "OPERADOR");

    Response response = postUserAction(id, "disable", 200);

    assertThat(response.jsonPath().getString("id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("username")).isEqualTo("desativa." + SUFFIX);
    assertThat(response.jsonPath().getString("status")).isEqualTo("DISABLED");
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");
    assertThat(response.asString()).doesNotContain("$argon2");

    // busca padrão (sem filtro de status) já não mostra o desativado
    Response list = listUsers("search", SUFFIX);
    assertThat(list.jsonPath().getList("items.id", String.class)).doesNotContain(id);
    assertThat(list.jsonPath().getInt("totalItems")).isZero();
    // detalhe some junto: desativado não existe para a API
    assertThat(getUserExpectingNotFound(id).jsonPath().getString("code"))
        .isEqualTo("USER_NOT_FOUND");
    // histórico preservado no banco, acesso cortado
    assertThat(statusOf(id)).isEqualTo("DISABLED");
    assertThat(deletedAtOf(id)).isNotNull();
  }

  @Test
  @DisplayName(
      "POST /api/v1/users/{id}/enable responde 200 ACTIVE e devolve o usuário à busca padrão")
  void enablesUser() throws SQLException {
    String id = createUserWithRole("reativa." + SUFFIX, "Reativa Usuario", "OPERADOR");
    postUserAction(id, "disable", 200);

    Response response = postUserAction(id, "enable", 200);

    assertThat(response.jsonPath().getString("id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getList("roles", String.class)).containsExactly("OPERADOR");

    Response list = listUsers("search", SUFFIX);
    assertThat(list.jsonPath().getList("items.id", String.class)).containsExactly(id);
    assertThat(getUser(id).jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(deletedAtOf(id)).isNull();
  }

  @Test
  @DisplayName("POST /api/v1/users/{id}/disable do último ADMIN ativo responde 409 CONFLICT")
  void rejectsDisablingLastActiveAdmin() throws SQLException {
    String firstAdmin = createUserWithRole("admin.um." + SUFFIX, "Admin Um", "ADMIN");
    String secondAdmin = createUserWithRole("admin.dois." + SUFFIX, "Admin Dois", "ADMIN");
    // O ADMIN da fixture (passo 307a) sai da contagem: sem isso ele seria o ADMIN que "sobra" e o
    // 409 do último ativo nunca chegaria. O soft delete não derruba a sessão dele, que é a de quem
    // faz as chamadas.
    softDeleteUser(TestAdmin.USERNAME);

    // Com dois ADMINs ativos a desativação é permitida: ainda sobra um.
    assertThat(postUserAction(secondAdmin, "disable", 200).jsonPath().getString("status"))
        .isEqualTo("DISABLED");

    Response response = postUserAction(firstAdmin, "disable", 409);

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/conflict");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Conflito de estado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(409);
    assertThat(response.jsonPath().getString("code")).isEqualTo("CONFLICT");
    assertThat(response.jsonPath().getString("detail")).contains("último ADMIN ativo");

    // nada foi gravado: o último ADMIN continua ativo
    assertThat(getUser(firstAdmin).jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(deletedAtOf(firstAdmin)).isNull();
  }

  @Test
  @DisplayName("POST /api/v1/users/{id}/disable de id inexistente ou já desativado responde 404")
  void returnsNotFoundWhenDisablingUnknownOrDisabledUser() throws SQLException {
    assertThat(
            postUserAction(UUID.randomUUID().toString(), "disable", 404)
                .jsonPath()
                .getString("code"))
        .isEqualTo("USER_NOT_FOUND");

    String id = createUserWithRole("ja.desativado." + SUFFIX, "Ja Desativado", "OPERADOR");
    postUserAction(id, "disable", 200);

    Response again = postUserAction(id, "disable", 404);

    assertThat(again.contentType()).contains("application/problem+json");
    assertThat(again.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/user-not-found");
    assertThat(again.jsonPath().getString("code")).isEqualTo("USER_NOT_FOUND");
  }

  @Test
  @DisplayName("POST /api/v1/users/{id}/enable de id inexistente responde 404; de ativo é no-op")
  void returnsNotFoundWhenEnablingUnknownId() throws SQLException {
    assertThat(
            postUserAction(UUID.randomUUID().toString(), "enable", 404)
                .jsonPath()
                .getString("code"))
        .isEqualTo("USER_NOT_FOUND");

    String id = createUserWithRole("ja.ativo." + SUFFIX, "Ja Ativo", "OPERADOR");

    assertThat(postUserAction(id, "enable", 200).jsonPath().getString("status"))
        .isEqualTo("ACTIVE");
    assertThat(getUser(id).jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(deletedAtOf(id)).isNull();
  }

  @Test
  @DisplayName(
      "POST /api/v1/users/{id}/password-reset responde 200 com mustChangePassword=true e hash novo")
  void resetsPassword() throws SQLException {
    String username = "reset.senha." + SUFFIX;
    String id = createUserWithRole(username, "Reset Senha", "OPERADOR");
    String hashBefore = passwordHashOf(username);
    assertThat(passwordHasher.verify("senha-secreta", hashBefore)).isTrue();

    Response response =
        postPasswordReset(
            id,
            """
            {"newPassword": "nova-senha-temporaria"}
            """,
            200);

    assertThat(response.jsonPath().getString("id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("username")).isEqualTo(username);
    assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getBoolean("mustChangePassword")).isTrue();
    assertThat(response.asString())
        .doesNotContain("nova-senha-temporaria")
        .doesNotContain("senha-secreta")
        .doesNotContain("$argon2");

    String hashAfter = passwordHashOf(username);
    assertThat(hashAfter).startsWith("$argon2id$").isNotEqualTo(hashBefore);
    assertThat(passwordHasher.verify("senha-secreta", hashAfter)).isFalse();
    assertThat(passwordHasher.verify("nova-senha-temporaria", hashAfter)).isTrue();
    assertThat(columnOf(id, "must_change_password")).isEqualTo("t");
    assertThat(columnOf(id, "password_changed_at")).isNotNull();
  }

  @Test
  @DisplayName(
      "POST /api/v1/users/{id}/password-reset de id inexistente ou desativado responde 404")
  void returnsNotFoundWhenResettingUnknownUser() throws SQLException {
    Response unknown =
        postPasswordReset(
            UUID.randomUUID().toString(),
            """
            {"newPassword": "nova-senha-temporaria"}
            """,
            404);

    assertThat(unknown.contentType()).contains("application/problem+json");
    assertThat(unknown.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/user-not-found");
    assertThat(unknown.jsonPath().getString("title")).isEqualTo("Usuário não encontrado");
    assertThat(unknown.jsonPath().getInt("status")).isEqualTo(404);
    assertThat(unknown.jsonPath().getString("code")).isEqualTo("USER_NOT_FOUND");

    String username = "reset.apagado." + SUFFIX;
    String id = createUserWithRole(username, "Reset Apagado", "OPERADOR");
    softDeleteUser(username);

    assertThat(
            postPasswordReset(
                    id,
                    """
                    {"newPassword": "nova-senha-temporaria"}
                    """,
                    404)
                .jsonPath()
                .getString("code"))
        .isEqualTo("USER_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "POST /api/v1/users/{id}/password-reset com senha curta ou em branco responde 400 e não altera nada")
  void rejectsInvalidResetPassword() throws SQLException {
    String username = "reset.curta." + SUFFIX;
    String id = createUserWithRole(username, "Reset Curta", "OPERADOR");
    String hashBefore = passwordHashOf(username);

    Response shortPassword =
        postPasswordReset(
            id,
            """
            {"newPassword": "1234567"}
            """,
            400);

    assertThat(shortPassword.contentType()).contains("application/problem+json");
    assertThat(shortPassword.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(shortPassword.jsonPath().getList("errors.field", String.class))
        .containsExactly("newPassword");
    assertThat(shortPassword.jsonPath().getString("errors[0].message")).contains("8 caracteres");

    Response blankPassword =
        postPasswordReset(
            id,
            """
            {"newPassword": "   "}
            """,
            400);
    assertThat(blankPassword.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(blankPassword.jsonPath().getList("errors.field", String.class))
        .contains("newPassword");

    assertThat(passwordHashOf(username)).isEqualTo(hashBefore);
    assertThat(columnOf(id, "must_change_password")).isEqualTo("f");
  }

  @Test
  @DisplayName("sem token a rota de usuários responde 401 problem+json da política global")
  void rejectsRequestsWithoutToken() {
    Response list = given().when().get("/api/v1/users").then().extract().response();

    assertThat(list.statusCode()).isEqualTo(401);
    assertThat(list.contentType()).contains("application/problem+json");
    assertThat(list.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(list.jsonPath().getString("instance")).isEqualTo("/api/v1/users");
    assertThat(list.jsonPath().getString("traceId")).isNotBlank();

    // A política global vale para todos os verbos: sem token a escrita também é 401, e a validação
    // do corpo nem chega a rodar.
    Response create =
        given()
            .contentType("application/json")
            .body("{}")
            .when()
            .post("/api/v1/users")
            .then()
            .extract()
            .response();
    assertThat(create.statusCode()).isEqualTo(401);
    assertThat(create.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
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

  private void createUser(String username, String displayName, String password) {
    asAdmin()
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

  /** Cria o usuário com o papel informado e devolve o id gerado (para o GET por id). */
  private String createUserWithRole(String username, String displayName, String roleCode) {
    return asAdmin()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "senha-secreta",
             "roleCodes": ["%s"]}
            """
                .formatted(username, displayName, roleCode))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  /**
   * Cria usuários numerados; o SUFFIX no username/displayName deixa a busca isolar a própria
   * página.
   */
  private void createListingUsers(int count) {
    for (int i = 1; i <= count; i++) {
      createUser("lista%02d.%s".formatted(i, SUFFIX), "Usuario %02d".formatted(i), "senha-secreta");
    }
  }

  private Response listUsers(String... queryParams) {
    return withQueryParams(asAdmin(), queryParams)
        .when()
        .get("/api/v1/users")
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  private Response listUsersBadRequest(String... queryParams) {
    return withQueryParams(asAdmin(), queryParams)
        .when()
        .get("/api/v1/users")
        .then()
        .statusCode(400)
        .extract()
        .response();
  }

  private Response getUser(String id) {
    return asAdmin()
        .when()
        .get("/api/v1/users/{id}", id)
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  /** PUT com corpo bruto; espera o status informado e devolve a resposta. */
  private Response putUser(String id, String body, int expectedStatus) {
    return asAdmin()
        .contentType("application/json")
        .body(body)
        .when()
        .put("/api/v1/users/{id}", id)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .response();
  }

  private Response getUserExpectingNotFound(String id) {
    return asAdmin()
        .when()
        .get("/api/v1/users/{id}", id)
        .then()
        .statusCode(404)
        .extract()
        .response();
  }

  /** Pares {@code nome, valor} viram query params. */
  private static RequestSpecification withQueryParams(
      RequestSpecification request, String... queryParams) {
    for (int i = 0; i < queryParams.length; i += 2) {
      request = request.queryParam(queryParams[i], queryParams[i + 1]);
    }
    return request;
  }

  /** POST sem corpo nas ações de ciclo de vida ({@code disable}/{@code enable}) do usuário. */
  private Response postUserAction(String id, String action, int expectedStatus) {
    return asAdmin()
        .when()
        .post("/api/v1/users/{id}/{action}", id, action)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .response();
  }

  /** POST com corpo na ação de reset de senha; espera o status informado. */
  private Response postPasswordReset(String id, String body, int expectedStatus) {
    return asAdmin()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/users/{id}/password-reset", id)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .response();
  }

  /** Status persistido, lido direto do banco para conferir o efeito do disable/enable. */
  private String statusOf(String id) throws SQLException {
    return columnOf(id, "status");
  }

  /** {@code deleted_at} persistido; {@code null} quando o usuário está vivo. */
  private String deletedAtOf(String id) throws SQLException {
    return columnOf(id, "deleted_at");
  }

  private String columnOf(String id, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select " + column + " from users where id = ?::uuid")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  /** Soft delete direto no banco: atalho de fixture, sem passar pelo caso de uso de desativar. */
  private void softDeleteUser(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("update users set deleted_at = now() where username = ?")) {
      statement.setString(1, username);
      statement.executeUpdate();
    }
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
