package com.minimarket.sales.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rotas de item da venda na API (passo 809b) contra PostgreSQL real (Dev Services): o operador
 * nasce pelo caso de uso, o login vincula a sessão ao {@code CAIXA-01} do seed, o caixa e a venda
 * abrem pela própria API (passos 607 e 807) e o produto é semeado pela porta do catálogo — o alvo é
 * o contrato das rotas, não o cadastro. O 401 sem token é do {@code RouteSecurityTest}; a posse da
 * venda e a recusa por estado são dos casos de uso (passo 809a) e a resolução do produto, do 808.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (linhas de {@code
 * sale_items}, totais de {@code sales} e eventos de auditoria) e limpa tudo o que comitou ao final,
 * na ordem que as FKs {@code restrict} exigem: chaves (que referenciam o usuário), itens, vendas,
 * série, eventos, movimentos, sessões de caixa, produtos, sessões de auth, papéis, usuários e os
 * caixas que o cenário do 403 criou.
 */
@QuarkusTest
class SaleItemsResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "17";

  private static final String PRODUCT_NAME = "Arroz 5kg";

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste. */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Produtos semeados pelo teste. */
  private final List<UUID> productIds = new ArrayList<>();

  /** Caixas extras criados pelo cenário do 403. */
  private final List<UUID> cashRegisterIds = new ArrayList<>();

  /** Operador dono da venda: o token é o da sessão vinculada ao {@code CAIXA-01}. */
  private String token;

  private UUID saleId;

  private UUID productId;

  @BeforeEach
  void openSaleWithProduct() throws SQLException {
    String username = "itens.api." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(username, List.of("OPERADOR"));
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, token));
    saleId = createSale(token);
    productId = seedProduct();
  }

  @Test
  @DisplayName("POST item por barcode: 200 com a venda inteira, item somado e totais recalculados")
  void addsItemByBarcodeRecalculatingTotals() throws SQLException {
    Response response = postItem(token, barcodeBody(" " + BARCODE + " ", "2"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(body)
        .as("contrato do detalhe da venda: nada de storeId nem version")
        .containsOnlyKeys(
            "id",
            "number",
            "status",
            "cashSessionId",
            "cashRegisterId",
            "operatorUserId",
            "customerId",
            "subtotal",
            "discountType",
            "discountValue",
            "discountReason",
            "discountAmount",
            "total",
            "itemCount",
            "createdAt",
            "completedAt",
            "cancelReason",
            "cancelledByUserId",
            "cancelledAt",
            "items");
    assertThat(body.get("id")).isEqualTo(saleId.toString());
    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(body.get("customerId")).as("venda sem cliente vinculado").isNull();
    assertThat(body.get("completedAt")).isNull();
    assertThat(body.get("cancelledAt")).as("venda aberta não foi cancelada").isNull();
    assertThat(body.get("discountType")).as("venda sem desconto").isNull();
    assertThat(body.get("discountValue")).isNull();
    assertThat(body.get("discountReason")).isNull();
    assertThat(decimal(body, "subtotal")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "discountAmount")).isEqualByComparingTo("0.00");
    assertThat(decimal(body, "total")).isEqualByComparingTo("19.80");
    assertThat(response.jsonPath().getInt("itemCount")).isEqualTo(1);

    Map<String, Object> item = firstItem(response);
    assertThat(item.get("productId")).isEqualTo(productId.toString());
    assertThat(item.get("barcode"))
        .as("snapshot do código do produto, não a string lida")
        .isEqualTo(BARCODE);
    assertThat(item.get("name")).isEqualTo(PRODUCT_NAME);
    assertThat(item.get("unit")).isEqualTo("UN");
    assertThat(decimal(item, "unitPrice")).isEqualByComparingTo("9.90");
    assertThat(decimal(item, "quantity")).isEqualByComparingTo("2.000");
    assertThat(decimal(item, "lineTotal")).isEqualByComparingTo("19.80");

    List<ItemRow> rows = itemRows(saleId);
    assertThat(rows).as("uma linha nova em sale_items").hasSize(1);
    assertThat(rows.getFirst().quantity()).isEqualTo("2.000");
    assertThat(saleTotals(saleId).subtotal()).isEqualTo("19.80");
    assertThat(saleTotals(saleId).itemCount()).isEqualTo(1);
    assertThat(auditEventCount(saleId, "SALE_ITEM_ADDED")).isEqualTo(1);
  }

  @Test
  @DisplayName("POST item por productId repetido: 200 nas duas e uma linha com a quantidade somada")
  void addsItemByProductIdSummingRepeatedProduct() throws SQLException {
    assertThat(postItem(token, productBody("2")).statusCode()).isEqualTo(200);

    Response response = postItem(token, productBody("1.5"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getInt("itemCount")).isEqualTo(1);
    assertThat(response.jsonPath().getList("items")).as("uma linha por produto").hasSize(1);
    assertThat(decimal(firstItem(response), "quantity")).isEqualByComparingTo("3.500");
    assertThat(decimal(response.jsonPath().getMap("$"), "total")).isEqualByComparingTo("34.65");

    List<ItemRow> rows = itemRows(saleId);
    assertThat(rows).as("sem duplicar a linha do produto").hasSize(1);
    assertThat(rows.getFirst().quantity()).isEqualTo("3.500");
    assertThat(saleTotals(saleId).total()).isEqualTo("34.65");
    assertThat(auditEventCount(saleId, "SALE_ITEM_ADDED")).isEqualTo(2);
  }

  @Test
  @DisplayName("PATCH quantidade: 200 com a linha e os totais recalculados e o evento da mudança")
  void changesQuantityRecalculatingTotals() throws SQLException {
    assertThat(postItem(token, barcodeBody(BARCODE, "2")).statusCode()).isEqualTo(200);

    Response response = patchItem(token, productId, "{\"quantity\": 3.5}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(decimal(firstItem(response), "quantity")).isEqualByComparingTo("3.500");
    assertThat(decimal(firstItem(response), "lineTotal")).isEqualByComparingTo("34.65");
    assertThat(decimal(firstItem(response), "unitPrice"))
        .as("BR-01: o snapshot do preço não muda")
        .isEqualByComparingTo("9.90");
    assertThat(decimal(response.jsonPath().getMap("$"), "total")).isEqualByComparingTo("34.65");

    List<ItemRow> rows = itemRows(saleId);
    assertThat(rows.getFirst().quantity()).isEqualTo("3.500");
    assertThat(rows.getFirst().lineTotal()).isEqualTo("34.65");
    assertThat(saleTotals(saleId).subtotal()).isEqualTo("34.65");
    assertThat(auditEventCount(saleId, "SALE_ITEM_QUANTITY_CHANGED")).isEqualTo(1);
  }

  @Test
  @DisplayName("DELETE item: 200 com a venda sem o item, totais zerados e o evento da remoção")
  void removesItemRecalculatingTotals() throws SQLException {
    assertThat(postItem(token, barcodeBody(BARCODE, "2")).statusCode()).isEqualTo(200);

    Response response = deleteItem(token, productId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("items")).as("a venda fica vazia").isEmpty();
    assertThat(response.jsonPath().getInt("itemCount")).isZero();
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(decimal(body, "subtotal")).isEqualByComparingTo("0.00");
    assertThat(decimal(body, "total")).isEqualByComparingTo("0.00");

    assertThat(itemRows(saleId)).as("a linha saiu de sale_items").isEmpty();
    assertThat(saleTotals(saleId).itemCount()).isZero();
    assertThat(auditEventCount(saleId, "SALE_ITEM_REMOVED")).isEqualTo(1);
  }

  @Test
  @DisplayName("venda de outro caixa: 403 ACCESS_DENIED nas três rotas, sem tocar na venda")
  void deniesSaleOfAnotherRegister() throws SQLException {
    UUID otherRegister = insertCashRegister();
    String otherUsername = "itens.outro." + SUFFIX;
    createUser(otherUsername, List.of("OPERADOR"));
    String otherToken = login(otherUsername, otherRegister);

    Response added = postItem(otherToken, barcodeBody(BARCODE, "1"));
    Response changed = patchItem(otherToken, productId, "{\"quantity\": 2}");
    Response removed = deleteItem(otherToken, productId);

    for (Response response : List.of(added, changed, removed)) {
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
      assertThat(response.jsonPath().getString("detail"))
          .as("a recusa é da guarda de posse (BR-11), não do porteiro da rota")
          .contains(saleId.toString())
          .doesNotContain("sale.create");
    }

    assertThat(itemRows(saleId)).as("nada entra, muda ou sai na venda alheia").isEmpty();
    assertThat(auditEventCount(saleId, "SALE_ITEM_ADDED")).isZero();
    assertThat(auditEventCount(saleId, "SALE_ITEM_QUANTITY_CHANGED")).isZero();
    assertThat(auditEventCount(saleId, "SALE_ITEM_REMOVED")).isZero();
  }

  @Test
  @DisplayName("produto fora da venda: 404 SALE_ITEM_NOT_FOUND no PATCH e no DELETE")
  void rejectsUnknownItem() {
    UUID unknownProduct = UUID.randomUUID();

    Response changed = patchItem(token, unknownProduct, "{\"quantity\": 1}");
    Response removed = deleteItem(token, unknownProduct);

    for (Response response : List.of(changed, removed)) {
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_ITEM_NOT_FOUND");
    }
  }

  @Test
  @DisplayName("venda concluída: 409 SALE_NOT_OPEN nas três rotas, sem tocar na venda")
  void rejectsCompletedSale() throws SQLException {
    assertThat(postItem(token, barcodeBody(BARCODE, "2")).statusCode()).isEqualTo(200);
    completeSale(saleId);

    Response added = postItem(token, barcodeBody(BARCODE, "1"));
    Response changed = patchItem(token, productId, "{\"quantity\": 5}");
    Response removed = deleteItem(token, productId);

    for (Response response : List.of(added, changed, removed)) {
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");
    }

    assertThat(itemRows(saleId).getFirst().quantity())
        .as("o item da venda concluída não é tocado")
        .isEqualTo("2.000");
    assertThat(saleTotals(saleId).itemCount()).isEqualTo(1);
    assertThat(auditEventCount(saleId, "SALE_ITEM_QUANTITY_CHANGED")).isZero();
    assertThat(auditEventCount(saleId, "SALE_ITEM_REMOVED")).isZero();
  }

  @Test
  @DisplayName("forma inválida: 400 VALIDATION_ERROR sem quantidade e com quantidade zero")
  void rejectsInvalidForm() throws SQLException {
    Response addedWithoutQuantity = postItem(token, barcodeBody(BARCODE, "null"));
    Response changedToZero = patchItem(token, productId, "{\"quantity\": 0}");

    for (Response response : List.of(addedWithoutQuantity, changedToZero)) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    assertThat(itemRows(saleId)).as("a forma inválida nem chega ao caso de uso").isEmpty();
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), itens, vendas, série, eventos, movimentos, sessões de
   * caixa, produtos, sessões de auth, papéis, usuários e caixas extras, nessa ordem.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID id : saleIds) {
        execute(connection, "delete from sale_items where sale_id = ?", id);
        execute(connection, "delete from sales where id = ?", id);
        execute(connection, "delete from audit_events where entity_id = ?", id);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
      for (UUID id : productIds) {
        execute(connection, "delete from products where id = ?", id);
      }
      for (UUID userId : userIds) {
        execute(
            connection,
            "delete from audit_events where actor_user_id = ? or entity_id = ?",
            userId,
            userId);
        execute(
            connection,
            "delete from audit_events where entity_id in"
                + " (select id from auth_sessions where user_id = ?)",
            userId);
        execute(connection, "delete from auth_sessions where user_id = ?", userId);
        execute(connection, "delete from user_roles where user_id = ?", userId);
        execute(connection, "delete from users where id = ?", userId);
      }
      for (UUID registerId : cashRegisterIds) {
        execute(connection, "delete from cash_registers where id = ?", registerId);
      }
    }
  }

  /** Abre o caixa pela API (passo 607) com chave nova e devolve o id da sessão comitada. */
  private UUID open(UUID registerId, String token) {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .contentType("application/json")
            .body("{\"openingAmount\": 100.00}")
            .when()
            .post(OPEN_PATH.formatted(registerId))
            .then()
            .statusCode(201)
            .extract()
            .response();
    return UUID.fromString(response.jsonPath().getString("id"));
  }

  /** Abre a venda pela API (passo 807) com chave nova e devolve o id da venda comitada. */
  private UUID createSale(String token) {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .when()
            .post(SALES_PATH)
            .then()
            .statusCode(201)
            .extract()
            .response();
    UUID id = UUID.fromString(response.jsonPath().getString("id"));
    saleIds.add(id);
    return id;
  }

  /** Produto do cenário pela porta do catálogo, em transação própria como o 405 o grava. */
  private UUID seedProduct() throws SQLException {
    UUID store = storeId();
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            store,
                            PRODUCT_NAME,
                            BARCODE,
                            null,
                            null,
                            "UN",
                            new BigDecimal("9.90"),
                            null)));
    productIds.add(id);
    return id;
  }

  /** Id da loja do seed, direto do banco: o produto precisa de uma loja real (FK restrict). */
  private UUID storeId() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from stores where code = 'MATRIZ'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("loja MATRIZ do seed da V1 presente").isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /** Caixa extra do cenário do 403: a venda de um caixa não é operável pelo outro. */
  private UUID insertCashRegister() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into cash_registers (id, store_id, code, name)"
                    + " values (uuidv7(), (select id from stores where code = 'MATRIZ'), ?,"
                    + " 'Caixa de teste') returning id")) {
      statement.setString(1, "ITENS." + SUFFIX);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        UUID id = resultSet.getObject("id", UUID.class);
        cashRegisterIds.add(id);
        return id;
      }
    }
  }

  /** Marca a venda como concluída direto no banco: a conclusão pela API só nasce na Fase 9. */
  private void completeSale(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "update sales set status = 'COMPLETED', completed_at = now() where id = ?",
          id);
    }
  }

  /** POST na coleção de itens da venda do cenário; o status fica com cada teste. */
  private Response postItem(String token, String body) {
    return itemRequest(token)
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
  }

  /** PATCH da quantidade do item da venda do cenário; o status fica com cada teste. */
  private Response patchItem(String token, UUID itemId, String body) {
    return itemRequest(token)
        .contentType("application/json")
        .body(body)
        .when()
        .patch(SALES_PATH + "/" + saleId + "/items/" + itemId)
        .then()
        .extract()
        .response();
  }

  /** DELETE do item da venda do cenário; o status fica com cada teste. */
  private Response deleteItem(String token, UUID itemId) {
    return itemRequest(token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/items/" + itemId)
        .then()
        .extract()
        .response();
  }

  private static RequestSpecification itemRequest(String token) {
    return given().header(AUTHORIZATION, "Bearer " + token);
  }

  private static String barcodeBody(String barcode, String quantity) {
    return """
        {"barcode": "%s", "quantity": %s}
        """
        .formatted(barcode, quantity);
  }

  private String productBody(String quantity) {
    return """
        {"productId": "%s", "quantity": %s}
        """
        .formatted(productId, quantity);
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.itens." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Cria o usuário pelo caso de uso (passo 107) com os papéis pedidos e rastreia o id. */
  private void createUser(String username, List<String> roleCodes) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, roleCodes))
            .id();
    userIds.add(id);
  }

  /** Login pela API (passo 205) vinculando a sessão ao caixa informado. */
  private static String login(String username, UUID cashRegisterId) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
            """
                .formatted(username, PASSWORD, cashRegisterId))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** Primeiro item do corpo da resposta como mapa — o cenário de cada teste tem um só. */
  private static Map<String, Object> firstItem(Response response) {
    List<Map<String, Object>> items = response.jsonPath().getList("items");
    assertThat(items).as("item no corpo da resposta").isNotEmpty();
    return items.getFirst();
  }

  /** Itens da venda na ordem de {@code line_number}, como o banco os guardou. */
  private List<ItemRow> itemRows(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select line_number, product_id, quantity::text as quantity,"
                    + " line_total::text as line_total from sale_items where sale_id = ?"
                    + " order by line_number")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<ItemRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new ItemRow(
                  resultSet.getInt("line_number"),
                  resultSet.getObject("product_id", UUID.class),
                  resultSet.getString("quantity"),
                  resultSet.getString("line_total")));
        }
        return rows;
      }
    }
  }

  /** Cabeçalho da venda como o banco o guardou. */
  private SaleRow saleTotals(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select subtotal::text as subtotal, total::text as total, item_count"
                    + " from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("subtotal"),
            resultSet.getString("total"),
            resultSet.getInt("item_count"));
      }
    }
  }

  /** Eventos da ação para o alvo: um por operação efetivada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
  }

  /**
   * Campo de dinheiro/quantidade do corpo como número (o JSON pode vir como float ou BigDecimal).
   */
  private static BigDecimal decimal(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  private int queryInt(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  /** Linha de {@code sale_items} como o banco a guardou. */
  private record ItemRow(int lineNumber, UUID productId, String quantity, String lineTotal) {}

  /** Cabeçalho de {@code sales} como o banco o guardou. */
  private record SaleRow(String subtotal, String total, int itemCount) {}
}
