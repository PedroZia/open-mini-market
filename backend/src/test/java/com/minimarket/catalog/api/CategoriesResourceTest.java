package com.minimarket.catalog.api;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CRUD de categorias na API (passo 402b) contra PostgreSQL real (Dev Services): as rotas de
 * verdade, com ADMIN da fixture ({@code asAdmin()}) e um OPERADOR criado pelo caso de uso e
 * autenticado por login real, como no {@code PermissionMatrixTest}.
 *
 * <p>O request HTTP commita: o {@link #removeRowsCreatedByThisTest()} apaga ao fim de cada teste as
 * categorias com o sufixo desta classe (filho antes do pai, porque a FK {@code parent_id} é {@code
 * on delete restrict}) e o usuário/sessões/eventos do OPERADOR.
 */
@QuarkusTest
class CategoriesResourceTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes de categoria e de usuário nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/categories";
  private static final String OPERATOR_ROLE = "OPERADOR";

  /** Caso de uso da criação de usuário: o OPERADOR é fixture, não o alvo do teste. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName(
      "ADMIN cria categoria raiz: 201 com Location, corpo do contrato e presença na listagem")
  void createsRootCategory() {
    String name = name("Bebidas");

    // Sem parentId e sem sortOrder no corpo: categoria raiz e a ordenação vale o default da coluna.
    Response response = post(adminToken(), body(name, null, null));

    assertThat(response.statusCode()).isEqualTo(201);
    String id = response.jsonPath().getString("id");
    assertThat(UUID.fromString(id).version()).as("id é UUIDv7").isEqualTo(7);
    assertThat(response.header("Location")).endsWith(PATH + "/" + id);
    assertThat(response.jsonPath().getString("name")).isEqualTo(name);
    assertThat(response.jsonPath().getString("parentId")).as("raiz").isNull();
    assertThat(response.jsonPath().getBoolean("active")).isTrue();
    assertThat(response.jsonPath().getInt("sortOrder")).as("sortOrder ausente vale 0").isZero();

    Map<String, Object> listed = listedCategory(id);
    assertThat(listed)
        .as("contrato da listagem: nem storeId nem timestamps vazam")
        .containsOnlyKeys("id", "name", "parentId", "active", "sortOrder");
    assertThat(listed.get("name")).isEqualTo(name);
    assertThat(listed.get("active")).isEqualTo(true);
    assertThat(listed.get("sortOrder")).isEqualTo(0);
  }

  @Test
  @DisplayName("categoria filha grava o pai e a listagem sai ordenada por sortOrder")
  void createsChildCategory() {
    String parentId = createdId(post(adminToken(), body(name("Bebidas"), null, 1)));
    String childId =
        createdId(post(adminToken(), body(name("Sucos"), UUID.fromString(parentId), 2)));

    Response listing = get(adminToken(), PATH);

    assertThat(listing.statusCode()).isEqualTo(200);
    assertThat(listing.contentType()).contains("application/json");
    List<String> ids = listing.jsonPath().getList("id", String.class);
    assertThat(ids).contains(parentId, childId);
    assertThat(ids.indexOf(parentId))
        .as("sortOrder 1 vem antes de 2")
        .isLessThan(ids.indexOf(childId));
    assertThat(listedCategory(childId).get("parentId")).isEqualTo(parentId);
  }

  @Test
  @DisplayName("PUT substitui nome, pai e ordenação e devolve 200 com a categoria atualizada")
  void updatesCategory() {
    String parentId = createdId(post(adminToken(), body(name("Bebidas"), null, 0)));
    String id = createdId(post(adminToken(), body(name("Sucos"), null, 0)));
    String newName = name("Sucos naturais");

    Response response = put(adminToken(), id, body(newName, UUID.fromString(parentId), 5));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("id")).isEqualTo(id);
    assertThat(response.jsonPath().getString("name")).isEqualTo(newName);
    assertThat(response.jsonPath().getString("parentId")).isEqualTo(parentId);
    assertThat(response.jsonPath().getInt("sortOrder")).isEqualTo(5);
    assertThat(response.jsonPath().getBoolean("active"))
        .as("PUT não mexe no estado ativo")
        .isTrue();
    assertThat(listedCategory(id).get("name")).isEqualTo(newName);
  }

  @Test
  @DisplayName("DELETE desativa sem apagar a linha: 204, active = false no banco e na listagem")
  void deactivatesCategory() throws SQLException {
    String id = createdId(post(adminToken(), body(name("Descartáveis"), null, 2)));

    Response response = delete(adminToken(), id);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.asString()).isEmpty();
    assertThat(activeColumn(id)).as("soft delete: a linha continua na tabela").isFalse();
    assertThat(listedCategory(id).get("active")).isEqualTo(false);

    Response again = delete(adminToken(), id);
    assertThat(again.statusCode()).as("já desativada conta como inexistente").isEqualTo(404);
    assertThat(again.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");
  }

  @Test
  @DisplayName("nome já usado responde 409 no POST e no PUT, inclusive contra desativada")
  void rejectsDuplicateName() {
    String taken = name("Bebidas");
    String takenId = createdId(post(adminToken(), body(taken, null, 0)));
    String otherId = createdId(post(adminToken(), body(name("Limpeza"), null, 0)));

    Response onCreate = post(adminToken(), body(taken, null, 1));
    assertThat(onCreate.statusCode()).isEqualTo(409);
    assertThat(onCreate.jsonPath().getString("code")).isEqualTo("CATEGORY_NAME_ALREADY_EXISTS");

    Response onUpdate = put(adminToken(), otherId, body(taken, null, 0));
    assertThat(onUpdate.statusCode()).isEqualTo(409);
    assertThat(onUpdate.jsonPath().getString("code")).isEqualTo("CATEGORY_NAME_ALREADY_EXISTS");
    assertThat(listedCategory(otherId).get("name"))
        .as("o 409 não deixa a alteração pela metade")
        .isEqualTo(name("Limpeza"));

    // O índice único é (store_id, name): nome de categoria desativada continua ocupado.
    assertThat(delete(adminToken(), takenId).statusCode()).isEqualTo(204);
    Response onDeactivated = post(adminToken(), body(taken, null, 0));
    assertThat(onDeactivated.statusCode()).isEqualTo(409);
    assertThat(onDeactivated.jsonPath().getString("code"))
        .isEqualTo("CATEGORY_NAME_ALREADY_EXISTS");
  }

  @Test
  @DisplayName("name em branco responde 400 VALIDATION_ERROR apontando o campo")
  void rejectsBlankName() {
    Response response = post(adminToken(), body("   ", null, 0));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getString("errors[0].field")).isEqualTo("name");
  }

  @Test
  @DisplayName("id inexistente responde 404 CATEGORY_NOT_FOUND no PUT e no DELETE")
  void notFoundForUnknownId() {
    String unknownId = UUID.randomUUID().toString();

    Response onUpdate = put(adminToken(), unknownId, body(name("Fantasma"), null, 0));
    assertThat(onUpdate.statusCode()).isEqualTo(404);
    assertThat(onUpdate.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");

    Response onDelete = delete(adminToken(), unknownId);
    assertThat(onDelete.statusCode()).isEqualTo(404);
    assertThat(onDelete.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");
  }

  @Test
  @DisplayName("pai inexistente responde 404 CATEGORY_NOT_FOUND no POST e no PUT, sem gravar nada")
  void notFoundForUnknownParent() {
    String ghostParent = UUID.randomUUID().toString();

    Response onCreate = post(adminToken(), body(name("Sucos"), UUID.fromString(ghostParent), 0));
    assertThat(onCreate.statusCode()).isEqualTo(404);
    assertThat(onCreate.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");

    String id = createdId(post(adminToken(), body(name("Limpeza"), null, 0)));
    Response onUpdate =
        put(adminToken(), id, body(name("Limpeza"), UUID.fromString(ghostParent), 0));
    assertThat(onUpdate.statusCode()).isEqualTo(404);
    assertThat(onUpdate.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");
    assertThat(listedCategory(id).get("parentId")).isNull();
  }

  @Test
  @DisplayName("OPERADOR lê categorias, mas POST, PUT e DELETE respondem 403 ACCESS_DENIED")
  void operatorReadsButCannotWrite() throws SQLException {
    String username = "categorias.operador." + SUFFIX;
    createUser(username, OPERATOR_ROLE);
    String token = login(username);
    String id = createdId(post(adminToken(), body(name("Limpeza"), null, 0)));

    Response listing = get(token, PATH);
    assertThat(listing.statusCode()).as("OPERADOR tem product.read").isEqualTo(200);
    assertThat(listing.jsonPath().getList("id", String.class)).contains(id);

    assertDenied(post(token, body(name("Bebidas"), null, 0)));
    assertDenied(put(token, id, body(name("Bebidas"), null, 0)));
    assertDenied(delete(token, id));

    assertThat(activeColumn(id)).as("o 403 não desativou nada").isTrue();
    assertThat(listedCategory(id).get("name")).isEqualTo(name("Limpeza"));
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos de
   * auditoria do OPERADOR (login e acesso negado) antes das sessões e do usuário que eles
   * referenciam, e as categorias do sufixo (filho antes do pai, porque a FK {@code parent_id} é
   * {@code on delete restrict}).
   */
  @AfterEach
  void removeRowsCreatedByThisTest() throws SQLException {
    String suffixLike = "%" + SUFFIX;
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from audit_events where actor_user_id in"
              + " (select id from users where username like ?) or entity_id in"
              + " (select id from users where username like ?)",
          suffixLike,
          suffixLike);
      delete(
          connection,
          "delete from audit_events where entity_id in (select id from auth_sessions where user_id"
              + " in (select id from users where username like ?))",
          suffixLike);
      delete(
          connection,
          "delete from auth_sessions where user_id in"
              + " (select id from users where username like ?)",
          suffixLike);
      delete(
          connection,
          "delete from user_roles where user_id in (select id from users where username like ?)",
          suffixLike);
      delete(connection, "delete from users where username like ?", suffixLike);
      delete(
          connection,
          "delete from categories where parent_id is not null and name like ?",
          suffixLike);
      delete(connection, "delete from categories where name like ?", suffixLike);
    }
  }

  /** Cria o OPERADOR pelo caso de uso (passo 107); o login é o de verdade. */
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

  /** Nome de categoria com o sufixo da classe, para a limpeza no fim do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
  }

  /** Corpo do POST/PUT: os campos nulos ficam de fora, como o cliente os omite. */
  private static String body(String name, UUID parentId, Integer sortOrder) {
    StringBuilder json = new StringBuilder("{\"name\": \"").append(name).append('"');
    if (parentId != null) {
      json.append(", \"parentId\": \"").append(parentId).append('"');
    }
    if (sortOrder != null) {
      json.append(", \"sortOrder\": ").append(sortOrder);
    }
    return json.append('}').toString();
  }

  /** Corpo do POST/PUT pronto para virar requisição, com o token do ator. */
  private static Response post(String token, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .post(PATH)
        .then()
        .extract()
        .response();
  }

  private static Response put(String token, String id, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .put(PATH + "/" + id)
        .then()
        .extract()
        .response();
  }

  private static Response delete(String token, String id) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(PATH + "/" + id)
        .then()
        .extract()
        .response();
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

  /** Id do corpo de um 201; falha com o status quando a criação não aconteceu. */
  private static String createdId(Response response) {
    assertThat(response.statusCode())
        .as("criação da fixture: %s", response.asString())
        .isEqualTo(201);
    return response.jsonPath().getString("id");
  }

  /** A categoria como a listagem a devolve; falha quando ela não aparece. */
  private Map<String, Object> listedCategory(String id) {
    Response listing = get(adminToken(), PATH);
    assertThat(listing.statusCode()).isEqualTo(200);
    List<Map<String, Object>> items = listing.jsonPath().getList("$");
    return items.stream()
        .filter(item -> id.equals(item.get("id")))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("categoria %s não apareceu na listagem".formatted(id)));
  }

  /** Coluna {@code active} da categoria no banco; falha quando a linha não existe. */
  private boolean activeColumn(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select active from categories where id = ?::uuid")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("categoria %s continua na tabela (soft delete)", id)
            .isTrue();
        return resultSet.getBoolean(1);
      }
    }
  }

  /** O 403 padrão do {@code RequirePermission}, citando a permissão que faltou. */
  private static void assertDenied(Response response) {
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("category.write");
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
