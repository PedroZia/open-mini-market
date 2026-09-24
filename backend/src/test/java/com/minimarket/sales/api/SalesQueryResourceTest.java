package com.minimarket.sales.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.customers.application.CustomerStore;
import com.minimarket.customers.application.NewCustomer;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consulta de vendas na API (passo 812) contra PostgreSQL real (Dev Services): o detalhe {@code GET
 * /sales/{id}} e o histórico {@code GET /sales} com filtros e paginação, pela rota de verdade. O
 * OPERADOR nasce pelo caso de uso e loga vinculado ao {@code CAIXA-01} do seed, a sessão de caixa e
 * as três vendas da fixture abrem pela própria API (passos 607/807) e o produto e o cliente vêm das
 * portas do catálogo e do cadastro — o alvo é o contrato das rotas, não o CRUD. O GERENTE (tem
 * {@code report.read} e {@code sale.discount.apply}) loga na mesma sessão de autenticação para
 * montar o detalhe e usa uma segunda sessão <em>sem</em> caixa para provar o bypass de gestão.
 *
 * <p>O 401 sem token das duas rotas é do {@code RouteSecurityTest}; a posse da venda é do {@code
 * SaleAccessGuard} (unitários do {@code GetSaleUseCase}) e os filtros do adaptador são do {@code
 * SaleRepositoryTest} (passo 803). O {@code created_at} das três vendas é fixado por SQL no
 * {@code @BeforeEach} para o período dos filtros ser determinístico.
 *
 * <p>O request HTTP comita, então o teste limpa tudo o que comitou ao final, na ordem que as FKs
 * {@code restrict} exigem: chaves (que referenciam o usuário), itens, vendas, série, eventos,
 * movimentos, sessões de caixa, produtos, clientes, sessões de auth, papéis, usuários e os caixas
 * extras.
 */
@QuarkusTest
class SalesQueryResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture das vendas. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "17";

  private static final String PRODUCT_NAME = "Arroz 5kg";

  /** {@code created_at} fixos das três vendas: o período dos filtros não depende do relógio. */
  private static final Instant DAY_ONE = Instant.parse("2026-01-10T10:00:00Z");

  private static final Instant DAY_TWO = Instant.parse("2026-01-11T10:00:00Z");
  private static final Instant DAY_THREE = Instant.parse("2026-01-12T10:00:00Z");
  private static final Instant DAY_FOUR = Instant.parse("2026-01-13T10:00:00Z");

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Porta do cadastro: o cliente do detalhe nasce por aqui, sem passar pela API de clientes. */
  @Inject CustomerStore customerStore;

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

  /** Clientes semeados pelo teste. */
  private final List<UUID> customerIds = new ArrayList<>();

  /** Caixas extras criados pelo cenário do 403. */
  private final List<UUID> cashRegisterIds = new ArrayList<>();

  private String operatorUsername;

  private String managerUsername;

  private String operatorToken;

  /** GERENTE na mesma sessão de autenticação do caixa: monta o desconto e o cliente do detalhe. */
  private String managerToken;

  private UUID operatorId;

  private UUID registerId;

  private UUID sessionId;

  private UUID productId;

  private UUID customerId;

  /** Vendas da fixture, da mais antiga para a mais nova ({@code created_at} fixado por SQL). */
  private UUID saleOne;

  private UUID saleTwo;

  private UUID saleThree;

  @BeforeEach
  void prepareFixture() throws SQLException {
    operatorUsername = "vendas.consulta." + SUFFIX;
    managerUsername = "vendas.consulta.gerente." + SUFFIX;
    createUser(operatorUsername, List.of("OPERADOR"));
    createUser(managerUsername, List.of("GERENTE"));
    operatorId = userId(operatorUsername);
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    operatorToken = login(operatorUsername, registerId);
    managerToken = login(managerUsername, registerId);
    sessionId = open(registerId, operatorToken);
    cashSessionIds.add(sessionId);
    productId = seedProduct();
    customerId = seedCustomer("Ana Souza");
    saleOne = createSaleWithItem(operatorToken);
    saleTwo = createSaleWithItem(operatorToken);
    saleThree = createSaleWithItem(operatorToken);
    setCreatedAt(saleOne, DAY_ONE);
    setCreatedAt(saleTwo, DAY_TWO);
    setCreatedAt(saleThree, DAY_THREE);
  }

  @Test
  @DisplayName(
      "GET /sales/{id}: 200 com itens, totais, desconto e cliente da venda do próprio caixa")
  void returnsSaleDetail() {
    assertThat(putDiscount(managerToken, saleOne, "PERCENT", "10").statusCode())
        .as("desconto do cenário")
        .isEqualTo(200);
    assertThat(putCustomer(managerToken, saleOne, customerId).statusCode())
        .as("cliente do cenário")
        .isEqualTo(200);

    Response response = getSale(operatorToken, saleOne);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body)
        .as("contrato do detalhe: sem storeId, sem version e sem pagamento (Fase 9)")
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
            "items");
    assertThat(body.get("id")).isEqualTo(saleOne.toString());
    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(body.get("cashSessionId")).isEqualTo(sessionId.toString());
    assertThat(body.get("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(body.get("operatorUserId"))
        .as("o operador é o ator da venda, não o da consulta")
        .isEqualTo(operatorId.toString());
    assertThat(body.get("customerId")).isEqualTo(customerId.toString());
    assertThat(body.get("discountType")).isEqualTo("PERCENT");
    assertThat(decimal(body, "discountValue")).isEqualByComparingTo("10.00");
    assertThat(body.get("discountReason")).isEqualTo("cliente fidelidade");
    assertThat(decimal(body, "subtotal")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "discountAmount"))
        .as("10% de 19,80, calculado pelo servidor")
        .isEqualByComparingTo("1.98");
    assertThat(decimal(body, "total")).isEqualByComparingTo("17.82");
    assertThat(body.get("itemCount")).isEqualTo(1);
    assertThat(Instant.parse((String) body.get("createdAt")))
        .as("created_at como o banco o guardou")
        .isEqualTo(DAY_ONE);
    assertThat(body.get("completedAt")).isNull();

    Map<String, Object> item = firstItem(response);

    assertThat(item.get("productId")).isEqualTo(productId.toString());
    assertThat(item.get("name")).isEqualTo(PRODUCT_NAME);
    assertThat(item.get("unit")).isEqualTo("UN");
    assertThat(decimal(item, "unitPrice")).isEqualByComparingTo("9.90");
    assertThat(decimal(item, "quantity")).isEqualByComparingTo("2.000");
    assertThat(decimal(item, "lineTotal")).isEqualByComparingTo("19.80");
  }

  @Test
  @DisplayName("GET /sales/{id} de venda inexistente: 404 SALE_NOT_FOUND")
  void rejectsUnknownSale() {
    Response response = getSale(operatorToken, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_FOUND");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Venda não encontrada");
  }

  @Test
  @DisplayName("OPERADOR de outro caixa: 403 ACCESS_DENIED no detalhe, sem vazar a venda")
  void deniesOperatorFromAnotherRegister() throws SQLException {
    UUID otherRegister = insertCashRegister();
    String otherUsername = "vendas.consulta.outro." + SUFFIX;
    createUser(otherUsername, List.of("OPERADOR"));
    String otherToken = login(otherUsername, otherRegister);

    Response response = getSale(otherToken, saleOne);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail"))
        .as("a recusa é da guarda de visibilidade (BR-11), não do porteiro da rota")
        .contains(saleOne.toString())
        .doesNotContain("report.read");
    assertThat(response.asString())
        .as("a recusa não devolve nada da venda alheia")
        .doesNotContain(PRODUCT_NAME);
  }

  @Test
  @DisplayName("GERENTE com report.read lê a venda de outro caixa: 200 pelo bypass de gestão")
  void allowsManagerToReadAnotherRegisterSale() {
    // A segunda sessão do mesmo GERENTE nasce sem caixa vinculado: sem o bypass, a posse recusaria.
    String managerWithoutRegister = login(managerUsername, null);

    Response response = getSale(managerWithoutRegister, saleOne);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("id")).isEqualTo(saleOne.toString());
    assertThat(response.jsonPath().getInt("itemCount")).isEqualTo(1);
    assertThat(response.jsonPath().getString("operatorUserId")).isEqualTo(operatorId.toString());
  }

  @Test
  @DisplayName("GET /sales: período, status, sessão e operador recortam o histórico")
  void filtersHistory() throws SQLException {
    Response all = history(managerToken, params("cashSessionId", sessionId.toString()));

    assertThat(all.statusCode()).isEqualTo(200);
    assertThat(all.contentType()).contains("application/json");
    assertThat(idsOf(all))
        .as("created_at desc: a mais nova primeiro")
        .containsExactly(saleThree.toString(), saleTwo.toString(), saleOne.toString());
    assertThat(all.jsonPath().getInt("page")).isZero();
    assertThat(all.jsonPath().getInt("size")).isEqualTo(20);
    assertThat(all.jsonPath().getLong("totalItems")).isEqualTo(3);
    assertThat(all.jsonPath().getInt("totalPages")).isEqualTo(1);

    Map<String, Object> first = firstItem(all);

    assertThat(first)
        .as("contrato da linha do histórico: sem itens, sem storeId e sem pagamento (Fase 9)")
        .containsOnlyKeys(
            "id",
            "number",
            "status",
            "cashSessionId",
            "cashRegisterId",
            "operatorUserId",
            "customerId",
            "subtotal",
            "discountAmount",
            "total",
            "itemCount",
            "createdAt",
            "completedAt");
    assertThat(first.get("cashSessionId")).isEqualTo(sessionId.toString());
    assertThat(first.get("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(first.get("operatorUserId")).isEqualTo(operatorId.toString());
    assertThat(first.get("status")).isEqualTo("OPEN");
    assertThat(first.get("customerId")).isNull();
    assertThat(first.get("completedAt")).isNull();
    assertThat(decimal(first, "subtotal")).isEqualByComparingTo("19.80");
    assertThat(decimal(first, "discountAmount")).isEqualByComparingTo("0.00");
    assertThat(decimal(first, "total")).isEqualByComparingTo("19.80");
    assertThat(first.get("itemCount")).isEqualTo(1);
    assertThat(Instant.parse((String) first.get("createdAt"))).isEqualTo(DAY_THREE);

    assertThat(
            idsOf(
                history(
                    managerToken,
                    params("cashSessionId", sessionId.toString(), "from", DAY_TWO.toString()))))
        .as("from é inclusivo")
        .containsExactly(saleThree.toString(), saleTwo.toString());
    assertThat(
            idsOf(
                history(
                    managerToken,
                    params("cashSessionId", sessionId.toString(), "to", DAY_TWO.toString()))))
        .as("to é exclusivo")
        .containsExactly(saleOne.toString());
    assertThat(
            idsOf(
                history(
                    managerToken,
                    params(
                        "cashSessionId",
                        sessionId.toString(),
                        "from",
                        DAY_TWO.toString(),
                        "to",
                        DAY_THREE.toString()))))
        .containsExactly(saleTwo.toString());
    assertThat(
            history(
                    managerToken,
                    params("cashSessionId", sessionId.toString(), "from", DAY_FOUR.toString()))
                .jsonPath()
                .getLong("totalItems"))
        .as("período sem venda")
        .isZero();

    // A conclusão pela API só nasce na Fase 9: a venda mais antiga é concluída direto no banco.
    completeSale(saleOne);

    assertThat(
            idsOf(
                history(
                    managerToken, params("cashSessionId", sessionId.toString(), "status", "OPEN"))))
        .containsExactly(saleThree.toString(), saleTwo.toString());
    assertThat(
            idsOf(
                history(
                    managerToken,
                    params("cashSessionId", sessionId.toString(), "status", "COMPLETED"))))
        .containsExactly(saleOne.toString());
    assertThat(idsOf(history(managerToken, params("operatorUserId", operatorId.toString()))))
        .as("o operador da fixture só tem as três vendas dele")
        .containsExactly(saleThree.toString(), saleTwo.toString(), saleOne.toString());
    assertThat(
            history(managerToken, params("operatorUserId", UUID.randomUUID().toString()))
                .jsonPath()
                .getLong("totalItems"))
        .isZero();
    assertThat(
            history(managerToken, params("cashSessionId", UUID.randomUUID().toString()))
                .jsonPath()
                .getLong("totalItems"))
        .isZero();
  }

  @Test
  @DisplayName("GET /sales: paginação por page/size, teto de 100 e página fora da regra em 400")
  void paginatesHistory() {
    Response firstPage =
        history(managerToken, params("cashSessionId", sessionId.toString(), "size", "2"));

    assertThat(firstPage.statusCode()).isEqualTo(200);
    assertThat(idsOf(firstPage)).containsExactly(saleThree.toString(), saleTwo.toString());
    assertThat(firstPage.jsonPath().getInt("page")).isZero();
    assertThat(firstPage.jsonPath().getInt("size")).isEqualTo(2);
    assertThat(firstPage.jsonPath().getLong("totalItems")).isEqualTo(3);
    assertThat(firstPage.jsonPath().getInt("totalPages")).isEqualTo(2);

    Response secondPage =
        history(
            managerToken, params("cashSessionId", sessionId.toString(), "page", "1", "size", "2"));

    assertThat(idsOf(secondPage)).containsExactly(saleOne.toString());
    assertThat(secondPage.jsonPath().getInt("page")).isEqualTo(1);

    Response capped =
        history(managerToken, params("cashSessionId", sessionId.toString(), "size", "250"));

    assertThat(capped.jsonPath().getInt("size")).as("teto de 100 do §9.1").isEqualTo(100);
    assertThat(idsOf(capped)).hasSize(3);

    for (Response response :
        List.of(
            history(managerToken, params("page", "-1")),
            history(managerToken, params("size", "0")))) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  @DisplayName("GET /sales sem report.read: 403 ACCESS_DENIED do porteiro da rota")
  void deniesHistoryWithoutReportRead() {
    Response response = history(operatorToken, params("cashSessionId", sessionId.toString()));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail"))
        .as("o histórico é visão de loja: exige a permissão de relatório")
        .contains("report.read");
    assertThat(response.asString())
        .as("a recusa não devolve venda nenhuma")
        .doesNotContain(saleOne.toString());
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), itens, vendas, série, eventos, movimentos, sessões de
   * caixa, produtos, clientes, sessões de auth, papéis, usuários e caixas extras, nessa ordem.
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
      for (UUID id : customerIds) {
        execute(connection, "delete from customers where id = ?", id);
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

  /** GET do detalhe da venda; o status fica com cada teste. */
  private static Response getSale(String token, UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(SALES_PATH + "/" + saleId)
        .then()
        .extract()
        .response();
  }

  /** GET do histórico com os filtros informados; o status fica com cada teste. */
  private static Response history(String token, Map<String, String> filters) {
    RequestSpecification request = given().header(AUTHORIZATION, "Bearer " + token);
    for (Map.Entry<String, String> filter : filters.entrySet()) {
      request = request.queryParam(filter.getKey(), filter.getValue());
    }
    return request.when().get(SALES_PATH).then().extract().response();
  }

  /** Mapa ordenado de filtros a partir de pares chave/valor, para a query do histórico. */
  private static Map<String, String> params(String... keyValues) {
    Map<String, String> filters = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      filters.put(keyValues[index], keyValues[index + 1]);
    }
    return filters;
  }

  /** Ids das vendas do corpo da listagem, na ordem em que a página veio. */
  private static List<String> idsOf(Response response) {
    return response.jsonPath().getList("items.id", String.class);
  }

  /** Primeiro item do corpo da resposta como mapa — o cenário de cada teste tem um só. */
  private static Map<String, Object> firstItem(Response response) {
    List<Map<String, Object>> items = response.jsonPath().getList("items");
    assertThat(items).as("item no corpo da resposta").isNotEmpty();
    return items.getFirst();
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

  /** Abre a venda pela API (passo 807) e inclui 2 × 9,90 = 19,80 de item (passo 809b). */
  private UUID createSaleWithItem(String token) {
    UUID id = createSale(token);
    given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"productId\": \"%s\", \"quantity\": 2}".formatted(productId))
        .when()
        .post(SALES_PATH + "/" + id + "/items")
        .then()
        .statusCode(200);
    return id;
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

  /** Aplica o desconto pela rota do 811b; o status fica com cada teste. */
  private static Response putDiscount(String token, UUID saleId, String type, String value) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(
            "{\"type\": \"%s\", \"value\": %s, \"reason\": \"cliente fidelidade\"}"
                .formatted(type, value))
        .when()
        .put(SALES_PATH + "/" + saleId + "/discount")
        .then()
        .extract()
        .response();
  }

  /** Vincula o cliente pela rota do 811b; o status fica com cada teste. */
  private static Response putCustomer(String token, UUID saleId, UUID customerId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"customerId\": \"%s\"}".formatted(customerId))
        .when()
        .put(SALES_PATH + "/" + saleId + "/customer")
        .then()
        .extract()
        .response();
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

  /** Cliente ativo do cenário pela porta do cadastro (502a). */
  private UUID seedCustomer(String name) throws SQLException {
    UUID store = storeId();
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(() -> customerStore.insert(new NewCustomer(store, name, null, null, null, null)));
    customerIds.add(id);
    return id;
  }

  /** {@code created_at} fixo da venda: o período dos filtros não depende do relógio da máquina. */
  private void setCreatedAt(UUID saleId, Instant createdAt) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "update sales set created_at = ?::timestamptz where id = ?",
          createdAt.toString(),
          saleId);
    }
  }

  /** Marca a venda como concluída direto no banco: a conclusão pela API só nasce na Fase 9. */
  private void completeSale(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "update sales set status = 'COMPLETED', completed_at = now() where id = ?",
          saleId);
    }
  }

  /** Id da loja do seed, direto do banco: o produto e o cliente precisam de uma loja real. */
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

  /** Caixa extra do cenário do 403: a venda de um caixa não é lida pelo operador do outro. */
  private UUID insertCashRegister() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into cash_registers (id, store_id, code, name)"
                    + " values (uuidv7(), (select id from stores where code = 'MATRIZ'), ?,"
                    + " 'Caixa de teste') returning id")) {
      statement.setString(1, "CONSULTA." + SUFFIX);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        UUID id = resultSet.getObject("id", UUID.class);
        cashRegisterIds.add(id);
        return id;
      }
    }
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.consulta." + SUFFIX + "." + UUID.randomUUID();
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

  /** Id do usuário criado pelo teste. */
  private UUID userId(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from users where username = ?")) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("usuário %s criado", username).isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /**
   * Login pela API (passo 205) vinculando ou não a sessão ao caixa: o vínculo é o caixa que a venda
   * usa (BR-11) e o {@code null} é a sessão sem caixa, o cenário do bypass de gestão.
   */
  private static String login(String username, UUID cashRegisterId) {
    String body =
        cashRegisterId == null
            ? """
              {"username": "%s", "password": "%s"}
              """
                .formatted(username, PASSWORD)
            : """
              {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
              """
                .formatted(username, PASSWORD, cashRegisterId);
    return given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /**
   * Campo de dinheiro/quantidade do corpo como número (o JSON pode vir como float ou BigDecimal).
   */
  private static BigDecimal decimal(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
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
}
