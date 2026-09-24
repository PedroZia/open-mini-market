package com.minimarket.catalog.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
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
 * Criação de produto na API (passo 406) contra PostgreSQL real (Dev Services): a rota de verdade,
 * com o ADMIN da fixture ({@code asAdmin()}) e um OPERADOR criado pelo caso de uso e autenticado
 * por login real, como no {@code PermissionMatrixTest}.
 *
 * <p>O request HTTP commita: o {@link #removeRowsCreatedByThisTest()} apaga ao fim de cada teste os
 * produtos com o sufixo desta classe (e os eventos de auditoria que os referenciam), o usuário
 * OPERADOR, as sessões e os eventos dele — os do ADMIN já saem pelo {@code TestAdmin.remove}.
 */
@QuarkusTest
class ProductsResourceTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes e barcodes nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/products";
  private static final String OPERATOR_ROLE = "OPERADOR";

  /** O barcode como o teste o digita: com espaços, que só o caso de uso sabe normalizar. */
  private static final String SPACED_BARCODE = " 789 1000 " + SUFFIX + " 17 ";

  /** O barcode como o caso de uso o guarda: sem espaços — o que o 201 precisa devolver. */
  private static final String STORED_BARCODE = "7891000" + SUFFIX + "17";

  /** Caso de uso da criação de usuário: o OPERADOR é fixture, não o alvo do teste. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName(
      "ADMIN cria produto: 201 com Location e corpo com os valores como o banco os guardou")
  void createsProduct() throws SQLException {
    String name = name("Arroz 5kg");

    Response response =
        post(
            adminToken(),
            """
            {"name": "%s", "barcode": "%s", "description": "grão longo", "unit": "UN",
             "price": 24.9, "minQuantity": 1.5}
            """
                .formatted(name, SPACED_BARCODE));

    assertThat(response.statusCode()).isEqualTo(201);
    String id = response.jsonPath().getString("id");
    assertThat(UUID.fromString(id).version()).as("id é UUIDv7").isEqualTo(7);
    assertThat(response.header("Location")).endsWith(PATH + "/" + id);

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("name")).isEqualTo(name);
    assertThat(json.get("barcode"))
        .as("trim e espaços internos removidos pelo caso de uso, não pela API")
        .isEqualTo(STORED_BARCODE);
    assertThat(json.get("description")).isEqualTo("grão longo");
    assertThat(json.get("categoryId")).as("categoria é opcional").isNull();
    assertThat(json.get("unit")).isEqualTo("UN");
    assertThat(number(json.get("price")))
        .as("preço em escala 2 (HALF_UP)")
        .isEqualByComparingTo("24.90");
    assertThat(number(json.get("minQuantity"))).isEqualByComparingTo("1.500");
    assertThat(json.get("active")).as("produto nasce ativo").isEqualTo(true);
    assertThat(json.get("version")).as("primeira versão do lock otimista").isEqualTo(0);
    assertThat(json.get("createdAt")).isNotNull();
    assertThat(json.get("updatedAt")).isNotNull();

    StoredProduct stored = productOf(id);
    assertThat(stored.barcode()).as("o corpo é o que ficou no banco").isEqualTo(STORED_BARCODE);
    assertThat(stored.price()).isEqualTo("24.90");
    assertThat(stored.active()).isTrue();
  }

  @Test
  @DisplayName("barcode já usado por produto vivo responde 409 BARCODE_ALREADY_EXISTS")
  void rejectsDuplicateBarcode() throws SQLException {
    createdId(post(adminToken(), validBody(name("Arroz 5kg"), SPACED_BARCODE)));

    // A segunda tentativa manda o barcode sem espaços: é a normalização do caso de uso que colide.
    Response response = post(adminToken(), validBody(name("Arroz 1kg"), STORED_BARCODE));

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("BARCODE_ALREADY_EXISTS");
    assertThat(countActiveByBarcode(STORED_BARCODE))
        .as("o 409 não cria a segunda linha")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("forma inválida responde 400 VALIDATION_ERROR em problem+json apontando o campo")
  void rejectsInvalidShape() {
    assertInvalid(
        """
        {"name": "   ", "unit": "UN", "price": 1.00}
        """,
        "name");
    assertInvalid(
        """
        {"name": "Arroz", "unit": "UN", "price": -0.01}
        """,
        "price");
    assertInvalid(
        """
        {"name": "Arroz", "price": 1.00}
        """,
        "unit");
  }

  @Test
  @DisplayName(
      "OPERADOR não cria produto: 403 ACCESS_DENIED citando product.write, sem gravar nada")
  void deniesOperator() throws SQLException {
    String username = "produtos.operador." + SUFFIX;
    createUser(username, OPERATOR_ROLE);
    String barcode = "555" + SUFFIX;

    Response response = post(login(username), validBody(name("Café"), barcode));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("product.write");
    assertThat(countActiveByBarcode(barcode)).as("o 403 barra antes do caso de uso").isZero();
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos de
   * auditoria (do OPERADOR e os que apontam para os produtos criados) antes das sessões, do usuário
   * e dos produtos que eles referenciam.
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
          "delete from audit_events where entity_id in"
              + " (select id from products where name like ? or barcode like ?)",
          suffixLike,
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
      delete(connection, "delete from products where name like ?", suffixLike);
      delete(connection, "delete from products where barcode like ?", suffixLike);
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

  /** Nome de produto com o sufixo da classe, para a limpeza no fim do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
  }

  /**
   * Valor numérico do JSON como BigDecimal: o parser pode devolver Integer, Double ou BigDecimal.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no corpo da resposta").isNotNull();
    return new BigDecimal(value.toString());
  }

  /** Corpo do POST que o caso de uso aceita: só o nome e o barcode variam entre os testes. */
  private static String validBody(String name, String barcode) {
    return """
        {"name": "%s", "barcode": "%s", "description": "grão longo", "unit": "UN", "price": 12.5}
        """
        .formatted(name, barcode);
  }

  /** O 400 padrão da bean validation: problem+json com o campo apontado em {@code errors[]}. */
  private void assertInvalid(String body, String field) {
    Response response = post(adminToken(), body);

    assertThat(response.statusCode()).as("corpo %s", body).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getList("errors.field", String.class))
        .as("campo apontado")
        .contains(field);
  }

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

  /** Id do corpo de um 201; falha com o status quando a criação não aconteceu. */
  private static String createdId(Response response) {
    assertThat(response.statusCode())
        .as("criação da fixture: %s", response.asString())
        .isEqualTo(201);
    return response.jsonPath().getString("id");
  }

  /** Barcode, preço (numeric textual) e active do produto no banco; falha quando não existe. */
  private StoredProduct productOf(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select barcode, price::text as price, active from products where id = ?::uuid")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return new StoredProduct(
            resultSet.getString("barcode"),
            resultSet.getString("price"),
            resultSet.getBoolean("active"));
      }
    }
  }

  /** Quantos produtos vivos existem com o barcode informado; o 409 e o 403 não podem criar. */
  private int countActiveByBarcode(String barcode) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from products where barcode = ? and deleted_at is null")) {
      statement.setString(1, barcode);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
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

  /** Linha de {@code products} como o banco a guardou; preço no formato textual do numeric. */
  private record StoredProduct(String barcode, String price, boolean active) {}
}
