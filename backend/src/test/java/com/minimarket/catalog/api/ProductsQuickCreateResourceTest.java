package com.minimarket.catalog.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cadastro rápido do PDV (F-02, passo 1122) contra PostgreSQL real (Dev Services): a rota {@code
 * POST /api/v1/products/quick} com um OPERADOR criado pelo caso de uso e autenticado por login de
 * verdade, como nos testes do catálogo. O modal do caixa (passo 1128) usa este contrato: nome,
 * código lido (travado), preço e unidade; descrição, categoria, código interno e mínimo nascem
 * nulos e se completam depois pelo PUT do 410.
 *
 * <p>O teste prova os quatro desfechos do aceite: 201 do OPERADOR com a permissão (com barcode
 * normalizado, preço em escala 2, {@code Location} do detalhe, evento {@code PRODUCT_QUICK_CREATED}
 * na mesma transação e o bipe resolvendo o produto logo depois), 409 {@code
 * BARCODE_ALREADY_EXISTS}, 403 {@code ACCESS_DENIED} de quem perdeu a permissão — o mapa
 * role→permissão é editável em runtime (§4.5), então o teste tira a permissão de OPERADOR e a
 * devolve no fim — e 400 das validações de forma e da whitelist de unidade, sempre sem gravar e sem
 * auditar.
 *
 * <p>O request HTTP commita: o {@link #removeRowsCreatedByThisTest()} apaga, ao fim de cada teste,
 * os eventos de auditoria (do ator e do produto criado), as sessões, os papéis, o usuário OPERADOR
 * e os produtos com o sufixo desta classe, e restaura a permissão de OPERADOR.
 */
@QuarkusTest
class ProductsQuickCreateResourceTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes, barcodes e usernames nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/products/quick";
  private static final String OPERATOR_ROLE = "OPERADOR";

  /** A permissão do cadastro rápido, tirada de OPERADOR no teste do 403 e devolvida no fim. */
  private static final String QUICK_PERMISSION = "product.quick_create";

  /** Caso de uso da criação de usuário: o OPERADOR é fixture, não o alvo do teste. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta para semear o barcode duplicado sem passar pelo cadastro (nem pela auditoria). */
  @Inject ProductStore productStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Id da loja do seed da V1, resolvido uma vez por teste. */
  private UUID storeId;

  @Test
  @DisplayName(
      "OPERADOR com product.quick_create cadastra o produto: 201, auditoria e o bipe passa a resolver")
  void operatorQuickCreatesProduct() throws SQLException {
    String username = "cadastro.rapido." + SUFFIX;
    createUser(username);
    String token = login(username);
    String productName = name("Coca-Cola 350ml");

    // O código chega como o leitor o entrega (com espaços): quem normaliza é o caso de uso.
    Response response = quickCreate(token, body(productName, spacedBarcode(), "4.9", "UN"));

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(201);
    assertThat(response.contentType()).contains("application/json");
    String id = response.jsonPath().getString("id");
    assertThat(UUID.fromString(id).version()).as("id é UUIDv7").isEqualTo(7);
    assertThat(response.header("Location"))
        .as("o Location aponta o detalhe do produto, não /products/quick")
        .endsWith("/api/v1/products/" + id);

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("name")).isEqualTo(productName);
    assertThat(json.get("barcode"))
        .as("trim e espaços internos removidos pelo caso de uso")
        .isEqualTo(storedBarcode());
    assertThat(json.get("internalCode")).as("cadastro rápido não tem PLU").isNull();
    assertThat(json.get("description")).as("cadastro rápido não tem descrição").isNull();
    assertThat(json.get("categoryId")).as("cadastro rápido não tem categoria").isNull();
    assertThat(json.get("unit")).isEqualTo("UN");
    assertThat(number(json.get("price")))
        .as("preço em escala 2 (HALF_UP)")
        .isEqualByComparingTo("4.90");
    assertThat(json.get("minQuantity")).as("cadastro rápido não tem mínimo").isNull();
    assertThat(json.get("active")).as("produto nasce ativo").isEqualTo(true);
    assertThat(json.get("version")).as("primeira versão do lock otimista").isEqualTo(0);
    assertThat(json.get("createdAt")).isNotNull();
    assertThat(json.get("updatedAt")).isNotNull();

    StoredProduct stored = productOf(id);
    assertThat(stored.barcode()).as("o corpo é o que ficou no banco").isEqualTo(storedBarcode());
    assertThat(stored.price()).isEqualTo("4.90");
    assertThat(stored.active()).isTrue();
    assertThat(stored.deleted()).isFalse();
    assertThat(stored.internalCode()).isNull();
    assertThat(stored.description()).isNull();

    // Auditoria na mesma transação: o evento nasce com o operador como ator e o "after" da criação.
    AuditEvent event = quickCreatedEventOf(UUID.fromString(id));

    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(event.actorUsername()).as("o ator é o operador do caixa").isEqualTo(username);
    JsonPath details = JsonPath.from(event.details());
    assertThat(details.getString("name")).isEqualTo(productName);
    assertThat(details.getString("barcode")).isEqualTo(storedBarcode());
    assertThat(number(details.get("price"))).isEqualByComparingTo("4.90");
    assertThat(details.getString("unit")).isEqualTo("UN");

    // O fim do fluxo do F-02: o código que não existia agora resolve no bipe do PDV.
    Response byBarcode = getByBarcode(token, storedBarcode());

    assertThat(byBarcode.statusCode()).as("bipe do código recém-cadastrado").isEqualTo(200);
    assertThat(byBarcode.jsonPath().getString("id")).isEqualTo(id);
  }

  @Test
  @DisplayName("barcode já usado por produto vivo responde 409 BARCODE_ALREADY_EXISTS e não grava")
  void rejectsDuplicateBarcode() throws SQLException {
    String token = loginNewOperator("cadastro.rapido.duplicado");
    String barcode = "555" + SUFFIX;
    seedProduct(name("Arroz 5kg"), "24.90", barcode);

    Response response =
        quickCreate(token, body(name("Arroz 1kg"), " " + barcode + " ", "12.5", "UN"));

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/barcode-already-exists");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Código de barras já está em uso");
    assertThat(response.jsonPath().getString("code")).isEqualTo("BARCODE_ALREADY_EXISTS");
    assertThat(countActiveByBarcode(barcode)).as("o 409 não cria a segunda linha").isEqualTo(1);
    assertThat(quickCreatedCountByBarcode(barcode)).as("o 409 não audita").isZero();
  }

  @Test
  @DisplayName("OPERADOR sem product.quick_create responde 403 ACCESS_DENIED e nada é gravado")
  void deniesOperatorWithoutPermission() throws SQLException {
    String username = "cadastro.rapido.sem." + SUFFIX;
    createUser(username);
    revokeOperatorQuickCreate();
    String token = login(username);
    String barcode = "777" + SUFFIX;

    Response response = quickCreate(token, body(name("Coca-Cola 350ml"), barcode, "4.9", "UN"));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains(QUICK_PERMISSION);
    assertThat(countActiveByBarcode(barcode)).as("o 403 barra antes do caso de uso").isZero();
    assertThat(quickCreatedCountByBarcode(barcode)).as("o 403 não audita a criação").isZero();
  }

  @Test
  @DisplayName(
      "forma inválida responde 400 VALIDATION_ERROR apontando o campo, sem gravar nem auditar")
  void rejectsInvalidShape() throws SQLException {
    String token = loginNewOperator("cadastro.rapido.invalido");

    assertInvalid(
        token, "{\"barcode\": \"789" + SUFFIX + "\", \"price\": 1.00, \"unit\": \"UN\"}", "name");
    assertInvalid(
        token,
        "{\"name\": \"   \", \"barcode\": \"789"
            + SUFFIX
            + "\", \"price\": 1.00, \"unit\": \"UN\"}",
        "name");
    assertInvalid(token, "{\"name\": \"Arroz\", \"price\": 1.00, \"unit\": \"UN\"}", "barcode");
    assertInvalid(
        token,
        "{\"name\": \"Arroz\", \"barcode\": \"   \", \"price\": 1.00, \"unit\": \"UN\"}",
        "barcode");
    assertInvalid(
        token,
        "{\"name\": \"Arroz\", \"barcode\": \"789"
            + SUFFIX
            + "\", \"price\": -0.01, \"unit\": \"UN\"}",
        "price");
    assertInvalid(
        token,
        "{\"name\": \"Arroz\", \"barcode\": \"789" + SUFFIX + "\", \"price\": 1.00}",
        "unit");

    assertThat(countActiveByBarcode("789" + SUFFIX))
        .as("nenhuma das tentativas inválidas criou produto")
        .isZero();
    assertThat(quickCreatedCountByBarcode("789" + SUFFIX)).as("o 400 não audita").isZero();
  }

  @Test
  @DisplayName("unidade fora de UN/KG responde 400 VALIDATION_ERROR do caso de uso, sem gravar")
  void rejectsUnknownUnit() throws SQLException {
    String token = loginNewOperator("cadastro.rapido.unidade");
    String barcode = "888" + SUFFIX;

    Response response = quickCreate(token, body(name("Coca-Cola 350ml"), barcode, "4.9", "CX"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getString("detail")).contains("unidade deve ser UN ou KG");
    assertThat(countActiveByBarcode(barcode)).isZero();
    assertThat(quickCreatedCountByBarcode(barcode)).as("o 400 não audita").isZero();
  }

  @Test
  @DisplayName("preço com mais de 2 decimais responde 400: o cadastro rápido não inventa casas")
  void rejectsPriceWithTooManyDecimals() {
    String token = loginNewOperator("cadastro.rapido.decimais");
    String barcode = "999" + SUFFIX;

    assertInvalid(token, body(name("Coca-Cola 350ml"), barcode, "4.999", "UN"), "price");
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos de
   * auditoria do ator e os que apontam para os produtos do sufixo antes das sessões, do usuário e
   * dos produtos que eles referenciam — e a permissão do cadastro rápido volta para OPERADOR, que o
   * teste do 403 tirou (o mapa de §4.5 é editável em runtime).
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
      restoreOperatorQuickCreate(connection);
    }
  }

  /** Cria o OPERADOR pelo caso de uso (passo 107); o login é o de verdade. */
  private void createUser(String username) {
    createUserUseCase.execute(
        new CreateUserCommand(username, username, PASSWORD, List.of(OPERATOR_ROLE)));
  }

  /** Cria e loga o OPERADOR da fixture; o token nasce com as permissões efetivas do papel. */
  private String loginNewOperator(String usernameBase) {
    String username = usernameBase + "." + SUFFIX;
    createUser(username);
    return login(username);
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

  /** O barcode como o leitor o entrega: com espaços, que só o caso de uso sabe normalizar. */
  private static String spacedBarcode() {
    return " 789 1000 " + SUFFIX + " 17 ";
  }

  /** O barcode como o caso de uso o guarda: sem espaços — o que o 201 precisa devolver. */
  private static String storedBarcode() {
    return "7891000" + SUFFIX + "17";
  }

  /** Corpo do cadastro rápido: os quatro campos do contrato, com o preço cru como número JSON. */
  private static String body(String productName, String barcode, String price, String unit) {
    return """
        {"name": "%s", "barcode": "%s", "price": %s, "unit": "%s"}
        """
        .formatted(productName, barcode, price, unit);
  }

  /** POST no cadastro rápido com o token do ator. */
  private static Response quickCreate(String token, String body) {
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

  /** GET no bipe do PDV como o ator; o RestAssured encoda o path param do barcode. */
  private static Response getByBarcode(String token, String barcode) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .pathParam("barcode", barcode)
        .when()
        .get("/api/v1/products/barcode/{barcode}")
        .then()
        .extract()
        .response();
  }

  /** O 400 padrão da validação: problem+json com o campo apontado em {@code errors[]}. */
  private void assertInvalid(String token, String body, String field) {
    Response response = quickCreate(token, body);

    assertThat(response.statusCode()).as("corpo %s", body).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getList("errors.field", String.class))
        .as("campo apontado")
        .contains(field);
  }

  /** Produto da fixture pela porta, sem caso de uso nem auditoria — o alvo é o 409. */
  private void seedProduct(String productName, String price, String barcode) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        productName,
                        barcode,
                        "descrição de " + productName,
                        null,
                        "UN",
                        new BigDecimal(price),
                        null)));
  }

  /** Id da loja configurada pela porta {@code StoreLookup}, sem importar infrastructure alheia. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      storeLookup
                          .findByCode(defaultStoreCode)
                          .orElseThrow(
                              () -> new IllegalStateException("loja do seed da V1 ausente"))
                          .id());
    }
    return storeId;
  }

  /**
   * Tira a permissão do cadastro rápido de OPERADOR direto no banco: o mapa role→permissão é
   * editável em runtime (§4.5) e o login seguinte — e só ele — enxerga o conjunto novo. A volta é
   * do {@link #removeRowsCreatedByThisTest()}.
   */
  private void revokeOperatorQuickCreate() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "delete from role_permissions where role_id ="
                    + " (select id from roles where code = 'OPERADOR') and permission_id ="
                    + " (select id from permissions where code = ?)")) {
      statement.setString(1, QUICK_PERMISSION);
      statement.executeUpdate();
    }
  }

  /** Devolve a permissão ao mapa de OPERADOR, como a migration V22 o deixou; idempotente. */
  private static void restoreOperatorQuickCreate(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into role_permissions (role_id, permission_id)"
                + " select role.id, permission.id from roles role"
                + " join permissions permission on permission.code = ?"
                + " where role.code = 'OPERADOR' on conflict do nothing")) {
      statement.setString(1, QUICK_PERMISSION);
      statement.executeUpdate();
    }
  }

  /** Barcode, código interno, descrição, preço (numeric textual), active e soft delete da linha. */
  private StoredProduct productOf(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select barcode, internal_code, description, price::text as price, active,"
                    + " deleted_at is not null as deleted from products where id = ?::uuid")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return new StoredProduct(
            resultSet.getString("barcode"),
            resultSet.getString("internal_code"),
            resultSet.getString("description"),
            resultSet.getString("price"),
            resultSet.getBoolean("active"),
            resultSet.getBoolean("deleted"));
      }
    }
  }

  /**
   * Evento {@code PRODUCT_QUICK_CREATED} do produto, com o {@code details} no texto do jsonb; falha
   * se houver zero ou mais de um evento para o alvo.
   */
  private AuditEvent quickCreatedEventOf(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, source, actor_username, details::text as details"
                    + " from audit_events where action = 'PRODUCT_QUICK_CREATED'"
                    + " and entity_id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento PRODUCT_QUICK_CREATED do produto %s", id).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma criação, um evento").isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos de cadastro rápido têm o barcode no {@code details}; 4xx exigem zero. */
  private int quickCreatedCountByBarcode(String barcode) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events"
                    + " where action = 'PRODUCT_QUICK_CREATED' and details->>'barcode' = ?")) {
      statement.setString(1, barcode);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Quantos produtos vivos existem com o barcode informado; o 409 e os 4xx não podem criar. */
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

  /**
   * Valor numérico do JSON como BigDecimal: o parser pode devolver Integer, Double ou BigDecimal.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no corpo da resposta").isNotNull();
    return new BigDecimal(value.toString());
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
  private record StoredProduct(
      String barcode,
      String internalCode,
      String description,
      String price,
      boolean active,
      boolean deleted) {}

  /** Linha de {@code audit_events} do cadastro rápido, com o {@code details} no texto do jsonb. */
  private record AuditEvent(
      String entityType, String source, String actorUsername, String details) {}
}
