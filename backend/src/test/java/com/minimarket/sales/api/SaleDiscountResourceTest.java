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
 * Rotas de desconto da venda na API (passo 811b) contra PostgreSQL real (Dev Services): a venda é
 * aberta pela própria API (passo 807), o item entra pela rota do 809b e o ator da venda é um
 * GERENTE real (tem {@code sale.discount.apply}), como no {@code CashPermissionsAuditTest} — a
 * matriz de permissões é exercitada com usuário de verdade e login de verdade, não com dublê. O 401
 * sem token é do {@code RouteSecurityTest}; as regras do desconto (limite da loja, motivo, totais)
 * são do caso de uso do 810 e têm unitários próprios.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (colunas de desconto
 * de {@code sales} e eventos de auditoria) e limpa tudo o que comitou ao final, na ordem que as FKs
 * {@code restrict} exigem: chaves (que referenciam o usuário), itens, vendas, série, eventos,
 * movimentos, sessões de caixa, produtos, sessões de auth, papéis, usuários e os caixas extras.
 */
@QuarkusTest
class SaleDiscountResourceTest extends IntegrationTestBase {

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

  /** GERENTE dono da venda: tem {@code sale.discount.apply} (seed da {@code V3__rbac.sql}). */
  private String username;

  /** Id do GERENTE criado: é ele o autor do desconto gravado na venda (passo 1009). */
  private UUID ownerUserId;

  private String token;

  private UUID registerId;

  private UUID saleId;

  @BeforeEach
  void openSaleWithItem() throws SQLException {
    username = "vendas.desconto." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    ownerUserId = createUser(username, List.of("GERENTE"));
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, token));
    saleId = createSale(token);
    assertThat(postItem(token, seedProduct()).statusCode()).as("item da fixture").isEqualTo(200);
  }

  @Test
  @DisplayName(
      "PUT desconto percentual: 200 com o desconto no corpo e no banco, e o DELETE volta ao subtotal")
  void appliesAndRemovesPercentDiscount() throws SQLException {
    Response applied =
        putDiscount(
            token, "{\"type\": \"PERCENT\", \"value\": 10, \"reason\": \"cliente fidelidade\"}");

    assertThat(applied.statusCode()).isEqualTo(200);
    assertThat(applied.contentType()).contains("application/json");
    Map<String, Object> body = applied.jsonPath().getMap("$");

    assertThat(body.get("discountType")).isEqualTo("PERCENT");
    assertThat(decimal(body, "discountValue")).isEqualByComparingTo("10.00");
    assertThat(body.get("discountReason")).isEqualTo("cliente fidelidade");
    assertThat(decimal(body, "discountAmount"))
        .as("10% de 19,80, calculado pelo servidor")
        .isEqualByComparingTo("1.98");
    assertThat(decimal(body, "subtotal")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "total")).isEqualByComparingTo("17.82");
    assertThat(body.get("customerId")).as("o desconto não mexe no cliente").isNull();
    assertThat(applied.jsonPath().getInt("itemCount")).isEqualTo(1);

    SaleRow stored = saleRow(saleId);

    assertThat(stored.discountType()).isEqualTo("PERCENT");
    assertThat(stored.discountValue()).isEqualTo("10.00");
    assertThat(stored.discountReason()).isEqualTo("cliente fidelidade");
    assertThat(stored.discountAmount()).isEqualTo("1.98");
    assertThat(stored.total()).isEqualTo("17.82");
    assertThat(stored.discountAuthorizedByUserId())
        .as("o autor é o usuário da sessão autenticada (passo 1009)")
        .isEqualTo(ownerUserId.toString());
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_APPLIED")).isEqualTo(1);

    Response removed = deleteDiscount(token);

    assertThat(removed.statusCode()).isEqualTo(200);
    body = removed.jsonPath().getMap("$");
    assertThat(body.get("discountType")).isNull();
    assertThat(body.get("discountValue")).isNull();
    assertThat(body.get("discountReason")).isNull();
    assertThat(decimal(body, "discountAmount")).isEqualByComparingTo("0.00");
    assertThat(decimal(body, "total"))
        .as("sem desconto o total volta ao subtotal (BR-02)")
        .isEqualByComparingTo("19.80");

    SaleRow afterRemoval = saleRow(saleId);

    assertThat(afterRemoval.discountType()).isNull();
    assertThat(afterRemoval.discountAuthorizedByUserId())
        .as("remover o desconto limpa o autor (passo 1009)")
        .isNull();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_REMOVED")).isEqualTo(1);
  }

  @Test
  @DisplayName("PUT desconto por valor: 200 com o valor subtraído do subtotal")
  void appliesValueDiscount() throws SQLException {
    Response applied =
        putDiscount(
            token, "{\"type\": \"VALUE\", \"value\": 5.50, \"reason\": \"arredondamento\"}");

    assertThat(applied.statusCode()).isEqualTo(200);
    Map<String, Object> body = applied.jsonPath().getMap("$");

    assertThat(body.get("discountType")).isEqualTo("VALUE");
    assertThat(decimal(body, "discountValue")).isEqualByComparingTo("5.50");
    assertThat(decimal(body, "discountAmount")).isEqualByComparingTo("5.50");
    assertThat(decimal(body, "total")).isEqualByComparingTo("14.30");
    assertThat(saleRow(saleId).discountAmount()).isEqualTo("5.50");
  }

  @Test
  @DisplayName("OPERADOR sem sale.discount.apply: 403 ACCESS_DENIED nos dois, sem tocar na venda")
  void deniesOperatorOnBothDiscountRoutes() throws SQLException {
    String operatorUsername =
        "vendas.desconto.op." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(operatorUsername, List.of("OPERADOR"));
    String operatorToken = login(operatorUsername, registerId);

    Response applied =
        putDiscount(operatorToken, "{\"type\": \"VALUE\", \"value\": 1, \"reason\": \"cortesia\"}");
    Response removed = deleteDiscount(operatorToken);

    for (Response response : List.of(applied, removed)) {
      assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(403);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
      assertThat(response.jsonPath().getString("detail"))
          .as("a recusa é do porteiro da rota, citando a permissão que falta")
          .contains("sale.discount.apply");
    }

    assertThat(saleRow(saleId).discountType()).isNull();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_APPLIED")).isZero();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_REMOVED")).isZero();
  }

  @Test
  @DisplayName("venda de outro caixa: 403 ACCESS_DENIED nos dois, sem tocar na venda")
  void deniesSaleOfAnotherRegister() throws SQLException {
    UUID otherRegister = insertCashRegister();
    String otherToken = login(username, otherRegister);

    Response applied =
        putDiscount(otherToken, "{\"type\": \"VALUE\", \"value\": 1, \"reason\": \"cortesia\"}");
    Response removed = deleteDiscount(otherToken);

    for (Response response : List.of(applied, removed)) {
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
      assertThat(response.jsonPath().getString("detail"))
          .as("a recusa é da guarda de posse (BR-11), não do porteiro da rota")
          .contains(saleId.toString())
          .doesNotContain("sale.discount.apply");
    }

    assertThat(saleRow(saleId).discountType()).isNull();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_APPLIED")).isZero();
  }

  @Test
  @DisplayName("venda concluída: 409 SALE_NOT_OPEN nos dois, sem tocar na venda")
  void rejectsCompletedSale() throws SQLException {
    completeSale(saleId);

    Response applied =
        putDiscount(token, "{\"type\": \"VALUE\", \"value\": 1, \"reason\": \"cortesia\"}");
    Response removed = deleteDiscount(token);

    for (Response response : List.of(applied, removed)) {
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");
    }

    assertThat(saleRow(saleId).discountType()).isNull();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_APPLIED")).isZero();
  }

  @Test
  @DisplayName("venda inexistente: 404 SALE_NOT_FOUND nos dois")
  void rejectsUnknownSale() {
    UUID unknownSale = UUID.randomUUID();

    Response applied =
        putDiscount(
            token, unknownSale, "{\"type\": \"VALUE\", \"value\": 1, \"reason\": \"cortesia\"}");
    Response removed = deleteDiscount(token, unknownSale);

    for (Response response : List.of(applied, removed)) {
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_FOUND");
    }
  }

  @Test
  @DisplayName("desconto acima do limite da loja: 422 DISCOUNT_LIMIT_EXCEEDED sem gravar")
  void rejectsDiscountAboveStoreLimit() throws SQLException {
    Response response =
        putDiscount(token, "{\"type\": \"PERCENT\", \"value\": 101, \"reason\": \"cortesia\"}");

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("DISCOUNT_LIMIT_EXCEEDED");
    assertThat(saleRow(saleId).discountType()).isNull();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_APPLIED")).isZero();
  }

  @Test
  @DisplayName(
      "forma inválida: 400 VALIDATION_ERROR sem tipo, com valor zero e com motivo em branco")
  void rejectsInvalidForm() throws SQLException {
    Response withoutType = putDiscount(token, "{\"value\": 5, \"reason\": \"cortesia\"}");
    Response zeroValue =
        putDiscount(token, "{\"type\": \"VALUE\", \"value\": 0, \"reason\": \"cortesia\"}");
    Response blankReason =
        putDiscount(token, "{\"type\": \"VALUE\", \"value\": 5, \"reason\": \"  \"}");

    for (Response response : List.of(withoutType, zeroValue, blankReason)) {
      assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    assertThat(saleRow(saleId).discountType())
        .as("a forma inválida nem chega ao caso de uso")
        .isNull();
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_APPLIED")).isZero();
  }

  @Test
  @DisplayName("DELETE sem desconto é no-op: 200 com a venda intacta e sem evento")
  void removalWithoutDiscountIsNoOp() throws SQLException {
    Response removed = deleteDiscount(token);

    assertThat(removed.statusCode()).isEqualTo(200);
    assertThat(removed.jsonPath().getMap("$").get("discountType")).isNull();
    assertThat(decimal(removed.jsonPath().getMap("$"), "total")).isEqualByComparingTo("19.80");
    assertThat(auditEventCount(saleId, "SALE_DISCOUNT_REMOVED")).isZero();
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
      for (UUID id : cashRegisterIds) {
        execute(connection, "delete from cash_registers where id = ?", id);
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
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(),
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

  /** Item da venda pela rota do 809b: 2 × 9,90 = 19,80 de subtotal. */
  private Response postItem(String token, UUID productId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"productId\": \"%s\", \"quantity\": 2}".formatted(productId))
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
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
      statement.setString(1, "DESCONTO." + SUFFIX);
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

  /** PUT do desconto na venda do cenário; o status fica com cada teste. */
  private Response putDiscount(String token, String body) {
    return putDiscount(token, saleId, body);
  }

  /** PUT do desconto na venda informada; o status fica com cada teste. */
  private Response putDiscount(String token, UUID id, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .put(SALES_PATH + "/" + id + "/discount")
        .then()
        .extract()
        .response();
  }

  /** DELETE do desconto na venda do cenário; o status fica com cada teste. */
  private Response deleteDiscount(String token) {
    return deleteDiscount(token, saleId);
  }

  /** DELETE do desconto na venda informada; o status fica com cada teste. */
  private Response deleteDiscount(String token, UUID id) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + id + "/discount")
        .then()
        .extract()
        .response();
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.desconto." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Cria o usuário pelo caso de uso (passo 107) com os papéis pedidos e rastreia o id. */
  private UUID createUser(String username, List<String> roleCodes) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, roleCodes))
            .id();
    userIds.add(id);
    return id;
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

  /** Colunas de desconto e totais de {@code sales} como o banco as guardou. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select discount_type, discount_value::text as discount_value, discount_reason,"
                    + " discount_amount::text as discount_amount, total::text as total,"
                    + " discount_authorized_by_user_id::text as discount_authorized_by_user_id"
                    + " from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("discount_type"),
            resultSet.getString("discount_value"),
            resultSet.getString("discount_reason"),
            resultSet.getString("discount_amount"),
            resultSet.getString("total"),
            resultSet.getString("discount_authorized_by_user_id"));
      }
    }
  }

  /** Eventos da ação para o alvo: um por operação efetivada, zero por tentativa barrada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
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

  /** Colunas de desconto e totais de {@code sales} como o banco as guardou. */
  private record SaleRow(
      String discountType,
      String discountValue,
      String discountReason,
      String discountAmount,
      String total,
      String discountAuthorizedByUserId) {}
}
