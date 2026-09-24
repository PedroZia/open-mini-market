package com.minimarket.catalog.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.CategoryStore;
import com.minimarket.catalog.application.NewCategory;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fechamento do módulo de catálogo (passo 413) contra PostgreSQL real (Dev Services): a suíte
 * consolidada que prova as duas garantias da fase — autorização e auditoria — nas rotas reais de
 * {@code /api/v1/categories} e {@code /api/v1/products}.
 *
 * <p>A matriz de permissões usa usuários reais criados pelo {@code CreateUserUseCase} e login de
 * verdade, como no {@code PermissionMatrixTest}: o OPERADOR só lê (200 em categorias, listagem,
 * detalhe e bipe; 403 {@code ACCESS_DENIED} em toda escrita) e o GERENTE altera preço (200), o que
 * prova que {@code price.write} não é exclusividade do ADMIN.
 *
 * <p>A auditoria é conferida pelo par {@code entity_id} + {@code action} — nunca por contagem
 * global, que outras linhas do log poderiam inflar: criação, edição, preço, desativação e
 * reativação do mesmo produto, cada uma com o evento que o caso de uso promete, e a reativação de
 * produto já ativo sem evento nenhum. Os ramos que sobraram da fase fecham aqui: produto {@code
 * active = false} com {@code deleted_at} nulo (ajustado direto no banco, porque o 412 desativa com
 * os dois) responde 404 no detalhe e some da listagem com {@code ?active=true}, aparecendo com
 * {@code ?active=false} e sem filtro.
 *
 * <p>A fixture de leitura nasce pelas portas ({@code CategoryStore}/{@code ProductStore}, sem caso
 * de uso nem auditoria); o request HTTP commita, então o {@link #removeRowsCreatedByThisTest()}
 * apaga ao fim de cada teste os eventos por {@code entity_id} e por ator, as sessões, papéis e
 * usuários OPERADOR/GERENTE e os produtos e categorias do sufixo desta classe (produtos antes das
 * categorias, filho antes do pai — as FKs são {@code on delete restrict}); os eventos do ADMIN da
 * fixture saem pelo {@code TestAdmin.remove}.
 */
@QuarkusTest
class CatalogPermissionsAuditTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes, barcodes e usernames nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String PRODUCTS_PATH = "/api/v1/products";
  private static final String CATEGORIES_PATH = "/api/v1/categories";
  private static final String OPERATOR_ROLE = "OPERADOR";
  private static final String MANAGER_ROLE = "GERENTE";

  /** Caso de uso da criação de usuário: os atores da matriz são fixture, não o alvo do teste. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Portas para semear as leituras sem passar pelos casos de uso (nem pela auditoria). */
  @Inject ProductStore productStore;

  @Inject CategoryStore categoryStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Id da loja do seed da V1, resolvido uma vez por teste. */
  private UUID storeId;

  @Test
  @DisplayName("OPERADOR lê o catálogo: 200 em categorias, listagem, detalhe e bipe do produto")
  void operatorReadsTheCatalog() {
    String token = loginNewUser("catalogo.operador.leitura", OPERATOR_ROLE);
    UUID categoryId = seedCategory("Bebidas");
    String barcode = "789" + SUFFIX;
    UUID id = seedProduct("Refrigerante 2L", "7.50", barcode, categoryId);

    Response categories = get(token, CATEGORIES_PATH);

    assertThat(categories.statusCode()).isEqualTo(200);
    assertThat(categories.contentType()).contains("application/json");
    assertThat(categories.jsonPath().getList("id", String.class)).contains(categoryId.toString());

    Response listing = get(token, PRODUCTS_PATH + "?search=" + SUFFIX);

    assertThat(listing.statusCode()).isEqualTo(200);
    assertThat(listing.jsonPath().getList("items.name", String.class))
        .contains(name("Refrigerante 2L"));

    Response detail = get(token, PRODUCTS_PATH + "/" + id);

    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(detail.jsonPath().getString("id")).isEqualTo(id.toString());
    assertThat(detail.jsonPath().getString("name")).isEqualTo(name("Refrigerante 2L"));
    assertThat(detail.jsonPath().getString("categoryId")).isEqualTo(categoryId.toString());

    Response byBarcode = get(token, PRODUCTS_PATH + "/barcode/" + barcode);

    assertThat(byBarcode.statusCode()).isEqualTo(200);
    assertThat(byBarcode.jsonPath().getString("id")).isEqualTo(id.toString());
  }

  @Test
  @DisplayName(
      "OPERADOR não escreve no catálogo: 403 ACCESS_DENIED em toda rota de escrita, sem efeito")
  void operatorIsDeniedOnEveryCatalogWrite() throws SQLException {
    String token = loginNewUser("catalogo.operador.escrita", OPERATOR_ROLE);
    UUID categoryId = seedCategory("Limpeza");
    UUID id = seedProduct("Detergente 500ml", "3.79", null, null);
    String deniedBarcode = "555" + SUFFIX;

    assertAccessDenied(
        post(token, PRODUCTS_PATH, productBody(name("Sabão em pó"), deniedBarcode)),
        "product.write");
    assertAccessDenied(
        put(token, PRODUCTS_PATH + "/" + id, "\"0\"", updateBody(name("Detergente 1L"))),
        "product.write");
    assertAccessDenied(
        patch(token, PRODUCTS_PATH + "/" + id + "/price", priceBody("3.49", "promoção")),
        "price.write");
    assertAccessDenied(post(token, PRODUCTS_PATH + "/" + id + "/disable", null), "product.write");
    assertAccessDenied(post(token, PRODUCTS_PATH + "/" + id + "/enable", null), "product.write");
    assertAccessDenied(
        post(token, CATEGORIES_PATH, categoryBody(name("Bebidas"))), "category.write");
    assertAccessDenied(
        put(token, CATEGORIES_PATH + "/" + categoryId, null, categoryBody(name("Bebidas"))),
        "category.write");
    assertAccessDenied(delete(token, CATEGORIES_PATH + "/" + categoryId), "category.write");

    // O 403 barra antes do caso de uso: nada do que foi tentado tocou o banco.
    StoredProduct stored = productOf(id);
    assertThat(stored.name()).as("o PUT barrado não editou").isEqualTo(name("Detergente 500ml"));
    assertThat(stored.price()).as("o PATCH barrado não mudou o preço").isEqualTo("3.79");
    assertThat(stored.active()).as("o disable barrado não desativou").isTrue();
    assertThat(categoryActive(categoryId))
        .as("o DELETE barrado não desativou a categoria")
        .isTrue();
    assertThat(countActiveByBarcode(deniedBarcode)).as("o POST barrado não criou produto").isZero();
    assertThat(eventCount(id)).as("escrita barrada não audita como operação").isZero();
  }

  @Test
  @DisplayName(
      "GERENTE altera preço: 200 e o banco guarda o valor novo (price.write não é só do ADMIN)")
  void managerChangesPrice() throws SQLException {
    String token = loginNewUser("catalogo.gerente", MANAGER_ROLE);
    UUID id = seedProduct("Café 500g", "18.90", null, null);

    Response response =
        patch(
            token, PRODUCTS_PATH + "/" + id + "/price", priceBody("17.90", "promoção do gerente"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(number(response.jsonPath().get("price"))).isEqualByComparingTo("17.90");
    assertThat(storedPrice(id)).as("o banco guardou o preço novo").isEqualTo("17.90");
  }

  @Test
  @DisplayName("toda operação do catálogo pela API vira evento no entity_id do produto, na ordem")
  void auditsEveryProductOperation() throws SQLException {
    String barcode = "789" + SUFFIX;

    Response created = post(adminToken(), PRODUCTS_PATH, productBody(name("Arroz 5kg"), barcode));

    assertThat(created.statusCode()).isEqualTo(201);
    UUID id = UUID.fromString(created.jsonPath().getString("id"));

    AuditEvent createdEvent = eventOf(id, "PRODUCT_CREATED");

    assertProductEvent(createdEvent, id);
    JsonPath createdDetails = JsonPath.from(createdEvent.details());
    assertThat(createdDetails.getString("name")).isEqualTo(name("Arroz 5kg"));
    assertThat(createdDetails.getString("barcode")).isEqualTo(barcode);
    assertThat(number(createdDetails.get("price"))).isEqualByComparingTo("24.90");

    Response updated =
        put(adminToken(), PRODUCTS_PATH + "/" + id, "\"0\"", updateBody(name("Arroz Tipo 1 5kg")));

    assertThat(updated.statusCode()).isEqualTo(200);

    AuditEvent updatedEvent = eventOf(id, "PRODUCT_UPDATED");

    assertProductEvent(updatedEvent, id);
    JsonPath updatedDetails = JsonPath.from(updatedEvent.details());
    assertThat(updatedDetails.getString("before.name")).isEqualTo(name("Arroz 5kg"));
    assertThat(updatedDetails.getString("before.unit")).isEqualTo("UN");
    assertThat(updatedDetails.getString("after.name")).isEqualTo(name("Arroz Tipo 1 5kg"));
    assertThat(updatedDetails.getString("after.unit")).isEqualTo("KG");
    assertThat(number(updatedDetails.get("after.minQuantity"))).isEqualByComparingTo("2.500");

    Response priced =
        patch(
            adminToken(),
            PRODUCTS_PATH + "/" + id + "/price",
            priceBody("19.99", "promoção do dia"));

    assertThat(priced.statusCode()).isEqualTo(200);

    AuditEvent priceEvent = eventOf(id, "PRODUCT_PRICE_CHANGED");

    assertProductEvent(priceEvent, id);
    assertThat(priceEvent.reason())
        .as("o motivo é o rastro humano da alteração")
        .isEqualTo("promoção do dia");
    JsonPath priceDetails = JsonPath.from(priceEvent.details());
    assertThat(number(priceDetails.get("before.price"))).isEqualByComparingTo("24.90");
    assertThat(number(priceDetails.get("after.price"))).isEqualByComparingTo("19.99");

    assertThat(post(adminToken(), PRODUCTS_PATH + "/" + id + "/disable", null).statusCode())
        .isEqualTo(200);

    AuditEvent disabledEvent = eventOf(id, "PRODUCT_DISABLED");

    assertProductEvent(disabledEvent, id);
    JsonPath disabledDetails = JsonPath.from(disabledEvent.details());
    assertThat(disabledDetails.getString("before.active")).isEqualTo("true");
    assertThat(disabledDetails.getString("after.active")).isEqualTo("false");

    assertThat(post(adminToken(), PRODUCTS_PATH + "/" + id + "/enable", null).statusCode())
        .isEqualTo(200);

    AuditEvent enabledEvent = eventOf(id, "PRODUCT_ENABLED");

    assertProductEvent(enabledEvent, id);
    JsonPath enabledDetails = JsonPath.from(enabledEvent.details());
    assertThat(enabledDetails.getString("before.active")).isEqualTo("false");
    assertThat(enabledDetails.getString("after.active")).isEqualTo("true");

    // Reativar produto já ativo é no-op: responde 200 e não inventa evento para o alvo.
    int eventsBefore = eventCount(id);

    assertThat(post(adminToken(), PRODUCTS_PATH + "/" + id + "/enable", null).statusCode())
        .isEqualTo(200);
    assertThat(eventCount(id)).as("o no-op não inventa evento").isEqualTo(eventsBefore);
    assertThat(eventCount(id, "PRODUCT_ENABLED"))
        .as("uma reativação de fato, um evento")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "produto active=false com deleted_at nulo: detalhe 404 e a listagem o separa pelo filtro active")
  void hidesInactiveProductAndFiltersByActive() throws SQLException {
    UUID id = seedProduct("Detergente 500ml", "3.79", null, null);
    setActiveDirectly(id, false);

    Response detail = get(adminToken(), PRODUCTS_PATH + "/" + id);

    assertThat(detail.statusCode())
        .as("fora do catálogo, o detalhe esconde o produto")
        .isEqualTo(404);
    assertThat(detail.contentType()).contains("application/problem+json");
    assertThat(detail.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");

    Response activeOnly = get(adminToken(), PRODUCTS_PATH + "?search=" + SUFFIX + "&active=true");

    assertThat(activeOnly.statusCode()).isEqualTo(200);
    assertThat(activeOnly.jsonPath().getList("items.name", String.class))
        .as("o filtro padrão exclui o inativo")
        .doesNotContain(name("Detergente 500ml"));

    Response inactiveOnly =
        get(adminToken(), PRODUCTS_PATH + "?search=" + SUFFIX + "&active=false");

    assertThat(inactiveOnly.jsonPath().getList("items.name", String.class))
        .containsExactly(name("Detergente 500ml"));

    Response withoutFilter = get(adminToken(), PRODUCTS_PATH + "?search=" + SUFFIX);

    assertThat(withoutFilter.jsonPath().getList("items.name", String.class))
        .as("sem filtro de status o inativo aparece — a semântica documentada hoje")
        .containsExactly(name("Detergente 500ml"));
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos de
   * auditoria do OPERADOR/GERENTE e os que apontam para os produtos do sufixo antes das sessões,
   * usuários e produtos que eles referenciam; e as categorias depois dos produtos (a FK {@code
   * products.category_id} é {@code on delete restrict}, e o filho sai antes do pai).
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
      delete(
          connection,
          "delete from categories where parent_id is not null and name like ?",
          suffixLike);
      delete(connection, "delete from categories where name like ?", suffixLike);
    }
  }

  /** Cria o usuário pelo caso de uso (passo 107), loga de verdade e devolve o token da sessão. */
  private String loginNewUser(String usernameBase, String roleCode) {
    String username = usernameBase + "." + SUFFIX;
    createUserUseCase.execute(
        new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)));
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

  /** Nome de produto/categoria com o sufixo da classe, para a limpeza no fim do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
  }

  /** Corpo do POST de produto que o caso de uso aceita; o preço normaliza para escala 2. */
  private static String productBody(String productName, String barcode) {
    return """
        {"name": "%s", "barcode": "%s", "description": "grão longo", "unit": "UN", "price": 24.9}
        """
        .formatted(productName, barcode);
  }

  /** Corpo do PUT de edição (passo 410): os cinco campos, com o nome sempre sufixado. */
  private static String updateBody(String productName) {
    return """
        {"name": "%s", "unit": "KG", "description": "grão longo tipo 1", "minQuantity": 2.500}
        """
        .formatted(productName);
  }

  /** Corpo do PATCH de preço (passo 411) com o preço e o motivo. */
  private static String priceBody(String price, String reason) {
    return "{\"price\": %s, \"reason\": \"%s\"}".formatted(price, reason);
  }

  /** Corpo do POST/PUT de categoria (passo 402b) com o nome informado. */
  private static String categoryBody(String categoryName) {
    return "{\"name\": \"%s\", \"sortOrder\": 1}".formatted(categoryName);
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

  /** POST com o token do ator; {@code body} nulo omite o corpo, como o disable/enable. */
  private static Response post(String token, String path, String body) {
    RequestSpecification request = given().header(AUTHORIZATION, "Bearer " + token);
    if (body != null) {
      request = request.contentType("application/json").body(body);
    }
    return request.when().post(path).then().extract().response();
  }

  /** PUT com o token do ator; {@code ifMatch} nulo omite o cabeçalho, como o PUT de categoria. */
  private static Response put(String token, String path, String ifMatch, String body) {
    RequestSpecification request =
        given().header(AUTHORIZATION, "Bearer " + token).contentType("application/json").body(body);
    if (ifMatch != null) {
      request = request.header("If-Match", ifMatch);
    }
    return request.when().put(path).then().extract().response();
  }

  private static Response patch(String token, String path, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .patch(path)
        .then()
        .extract()
        .response();
  }

  private static Response delete(String token, String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(path)
        .then()
        .extract()
        .response();
  }

  /** O 403 padrão do {@code RequirePermission}, citando a permissão que faltou. */
  private static void assertAccessDenied(Response response, String permission) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains(permission);
  }

  /** Campos comuns dos eventos de produto: alvo, origem de requisição autenticada e ator ADMIN. */
  private static void assertProductEvent(AuditEvent event, UUID id) {
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.entityId()).isEqualTo(id.toString());
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);
  }

  /** Categoria da fixture pela porta, sem caso de uso nem auditoria. */
  private UUID seedCategory(String base) {
    return QuarkusTransaction.requiringNew()
        .call(() -> categoryStore.insert(new NewCategory(storeId(), name(base), null, 0)));
  }

  /** Produto da fixture pela porta, sem caso de uso nem auditoria. */
  private UUID seedProduct(String base, String price, String barcode, UUID categoryId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        name(base),
                        barcode,
                        "descrição de " + base,
                        categoryId,
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
   * Marca {@code active} direto no banco, com {@code deleted_at} nulo: desativar é regra do caso de
   * uso do passo 412 (com auditoria) e o detalhe precisa de um produto fora do catálogo sem o soft
   * delete que acompanha a desativação.
   */
  private void setActiveDirectly(UUID id, boolean active) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("update products set active = ? where id = ?::uuid")) {
      statement.setBoolean(1, active);
      statement.setString(2, id.toString());
      statement.executeUpdate();
    }
  }

  /** Nome, preço (numeric textual) e {@code active} do produto no banco. */
  private StoredProduct productOf(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select name, price::text as price, active from products where id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return new StoredProduct(
            resultSet.getString("name"),
            resultSet.getString("price"),
            resultSet.getBoolean("active"));
      }
    }
  }

  /** Preço do produto no banco, no formato textual do numeric(14,2). */
  private String storedPrice(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select price::text as price from products where id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return resultSet.getString("price");
      }
    }
  }

  /** {@code active} da categoria no banco; falha quando a linha não existe. */
  private boolean categoryActive(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select active from categories where id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("categoria %s gravada", id).isTrue();
        return resultSet.getBoolean(1);
      }
    }
  }

  /** Quantos produtos vivos existem com o barcode informado; o 403 não pode criar. */
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
   * Evento da ação pelo alvo, com {@code details} no texto do jsonb; falha se houver zero ou mais
   * de um evento para o par — a conferência é sempre por {@code entity_id} + {@code action}.
   */
  private AuditEvent eventOf(UUID entityId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id::text as entity_id, source, actor_username, reason,"
                    + " details::text as details from audit_events"
                    + " where action = ? and entity_id = ?::uuid")) {
      statement.setString(1, action);
      statement.setString(2, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s do alvo %s", action, entityId).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("entity_id"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("reason"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma operação, um evento %s", action).isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos o alvo tem, de qualquer ação — o no-op compara antes e depois. */
  private int eventCount(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where entity_id = ?::uuid")) {
      statement.setString(1, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Quantos eventos da ação o alvo tem; o no-op exige exatamente um. */
  private int eventCount(UUID entityId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where action = ? and entity_id = ?::uuid")) {
      statement.setString(1, action);
      statement.setString(2, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /**
   * Valor numérico do JSON como BigDecimal: o JsonPath do RestAssured devolve decimal como {@code
   * Float}, então o dinheiro do evento é conferido por valor ({@code compareTo}), não pelo texto —
   * a escala 2 do numeric continua valendo, só a renderização do parser é que encurta.
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
  private record StoredProduct(String name, String price, boolean active) {}

  /** Linha de {@code audit_events} com o {@code details} no texto do jsonb, pronto para o GPath. */
  private record AuditEvent(
      String entityType,
      String entityId,
      String source,
      String actorUsername,
      String reason,
      String details) {}
}
