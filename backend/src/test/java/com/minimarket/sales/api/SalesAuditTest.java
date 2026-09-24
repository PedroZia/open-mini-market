package com.minimarket.sales.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.api.AuthResource;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.api.IdempotencyGuard;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fechamento do módulo de vendas (passo 814b) contra PostgreSQL real (Dev Services): a suíte de
 * auditoria dos eventos da venda no fluxo real da API, no padrão do {@code
 * CashPermissionsAuditTest}.
 *
 * <p>O fluxo é uma venda de verdade operada por um GERENTE real (login de verdade vinculado ao
 * {@code CAIXA-01} do seed), na ordem em que a TUI opera: abre a venda, bipa o item, troca a
 * quantidade, aplica e tira o desconto, vincula e desvincula o cliente, remove o item e cancela.
 * Cada operação gera <em>um</em> evento ({@code SALE_CREATED}, {@code SALE_ITEM_ADDED}, {@code
 * SALE_ITEM_QUANTITY_CHANGED}, {@code SALE_DISCOUNT_APPLIED}, {@code SALE_DISCOUNT_REMOVED}, {@code
 * SALE_CUSTOMER_LINKED}, {@code SALE_CUSTOMER_UNLINKED}, {@code SALE_ITEM_REMOVED} e {@code
 * SALE_CANCELLED}) e a conferência é sempre pelo par {@code action} + {@code entity_id} — nunca por
 * contagem global, que outras linhas do log poderiam inflar: o alvo é a venda, o ator é o GERENTE
 * do token, a origem é a TUI da sessão autenticada e os {@code details} essenciais de cada operação
 * (totais do servidor, quantidades antes/depois, motivo do desconto e do cancelamento) são
 * conferidos no evento, não só no corpo da resposta.
 *
 * <p>O último gesto é uma operação barrada — incluir item na venda já cancelada, 409 {@code
 * SALE_NOT_OPEN} — e o rastro continua exatamente com as nove ações do fluxo: recusa não inventa
 * evento. A limpeza do {@code @AfterEach} segue a ordem que as FKs {@code restrict} exigem: chaves,
 * itens, vendas, eventos das vendas, movimentos e sessão de caixa, série, produtos, clientes e, por
 * fim, os eventos, as sessões de auth, os papéis e o usuário do ator — o banco é compartilhado com
 * os demais testes do fork e o {@code CAIXA-01} é fixture compartilhada.
 */
@QuarkusTest
class SalesAuditTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /** Cliente da sessão dos atores: a TUI é quem opera a venda, e a origem do evento é dela. */
  private static final String CLIENT = "TUI";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PRODUCT_NAME = "Arroz 5kg";

  private static final String CUSTOMER_NAME = "Ana Souza";

  /** Preço do produto: os totais do fluxo saem dele (2 × 9,90; 3 × 9,90). */
  private static final BigDecimal PRICE = new BigDecimal("9.90");

  /** Caso de uso da criação de usuário (passo 107): o ator do teste é fixture, não o alvo. */
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

  /** Clientes semeados pelo teste. */
  private final List<UUID> customerIds = new ArrayList<>();

  private String username;

  private String token;

  private UUID userId;

  private UUID registerId;

  @BeforeEach
  void prepareActor() throws SQLException {
    username = "vendas.auditoria." + SUFFIX;
    userId =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, List.of("GERENTE")))
            .id();
    userIds.add(userId);
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login();
    // A série nasce na primeira alocação: nenhum teste herda o contador do anterior.
    execute("delete from document_sequences where doc_type = 'SALE'");
  }

  @Test
  @DisplayName(
      "o fluxo da venda gera um evento por operação, no alvo, com ator, origem e details essenciais")
  void fullFlowAuditsOneEventPerOperation() throws SQLException {
    UUID productId = seedProduct();
    UUID customerId = seedCustomer();

    Response opened = open();
    assertThat(opened.statusCode()).as("resposta: %s", opened.asString()).isEqualTo(201);
    UUID cashSessionId = UUID.fromString(opened.jsonPath().getString("id"));
    cashSessionIds.add(cashSessionId);

    Response created = createSale();
    assertThat(created.statusCode()).as("resposta: %s", created.asString()).isEqualTo(201);
    UUID saleId = UUID.fromString(created.jsonPath().getString("id"));
    saleIds.add(saleId);

    // 1. Venda aberta: o evento carrega o número alocado e a sessão/caixa que o servidor resolveu.
    AuditEvent createdEvent = eventOf(saleId, "SALE_CREATED");
    assertSaleEvent(createdEvent, saleId);
    JsonPath createdDetails = JsonPath.from(createdEvent.details());
    assertThat(createdDetails.getString("number")).isEqualTo("1");
    assertThat(createdDetails.getString("cashSessionId")).isEqualTo(cashSessionId.toString());
    assertThat(createdDetails.getString("cashRegisterId")).isEqualTo(registerId.toString());

    // 2. Bipe: o snapshot do item e os totais recalculados pelo servidor.
    expect(postItem(saleId, productId, "2"), 200);
    AuditEvent added = eventOf(saleId, "SALE_ITEM_ADDED");
    assertSaleEvent(added, saleId);
    JsonPath addedDetails = JsonPath.from(added.details());
    assertThat(addedDetails.getString("productId")).isEqualTo(productId.toString());
    assertThat(number(addedDetails.get("quantity"))).isEqualByComparingTo("2");
    assertThat(number(addedDetails.get("subtotal"))).isEqualByComparingTo("19.80");
    assertThat(number(addedDetails.get("total"))).isEqualByComparingTo("19.80");
    assertThat(addedDetails.getInt("itemCount")).isEqualTo(1);

    // 3. Troca de quantidade: o antes/depois e o total da linha nova.
    expect(patchItem(saleId, productId, "3"), 200);
    AuditEvent changed = eventOf(saleId, "SALE_ITEM_QUANTITY_CHANGED");
    assertSaleEvent(changed, saleId);
    JsonPath changedDetails = JsonPath.from(changed.details());
    assertThat(changedDetails.getString("productId")).isEqualTo(productId.toString());
    assertThat(number(changedDetails.get("previousQuantity"))).isEqualByComparingTo("2");
    assertThat(number(changedDetails.get("quantity"))).isEqualByComparingTo("3");
    assertThat(number(changedDetails.get("lineTotal"))).isEqualByComparingTo("29.70");
    assertThat(number(changedDetails.get("subtotal"))).isEqualByComparingTo("29.70");

    // 4. Desconto aplicado: o motivo vai no reason do evento e o cálculo é do servidor.
    expect(
        putDiscount(
            saleId, "{\"type\": \"PERCENT\", \"value\": 10, \"reason\": \"cliente fidelidade\"}"),
        200);
    AuditEvent applied = eventOf(saleId, "SALE_DISCOUNT_APPLIED");
    assertSaleEvent(applied, saleId);
    assertThat(applied.reason())
        .as("o motivo do desconto é o rastro humano")
        .isEqualTo("cliente fidelidade");
    JsonPath appliedDetails = JsonPath.from(applied.details());
    assertThat(appliedDetails.getString("type")).isEqualTo("PERCENT");
    assertThat(number(appliedDetails.get("value"))).isEqualByComparingTo("10");
    assertThat(number(appliedDetails.get("discountAmount"))).isEqualByComparingTo("2.97");
    assertThat(number(appliedDetails.get("total"))).isEqualByComparingTo("26.73");

    // 5. Desconto removido: o evento guarda o desconto que saiu e o total de volta ao subtotal.
    expect(deleteDiscount(saleId), 200);
    AuditEvent removedDiscount = eventOf(saleId, "SALE_DISCOUNT_REMOVED");
    assertSaleEvent(removedDiscount, saleId);
    assertThat(removedDiscount.reason()).isEqualTo("cliente fidelidade");
    JsonPath removedDetails = JsonPath.from(removedDiscount.details());
    assertThat(removedDetails.getString("type")).isEqualTo("PERCENT");
    assertThat(number(removedDetails.get("value"))).isEqualByComparingTo("10");
    assertThat(number(removedDetails.get("discountAmount"))).isEqualByComparingTo("2.97");
    assertThat(number(removedDetails.get("total"))).isEqualByComparingTo("29.70");

    // 6. Cliente vinculado: o id e o nome que o operador reconhece.
    expect(putCustomer(saleId, customerId), 200);
    AuditEvent linked = eventOf(saleId, "SALE_CUSTOMER_LINKED");
    assertSaleEvent(linked, saleId);
    JsonPath linkedDetails = JsonPath.from(linked.details());
    assertThat(linkedDetails.getString("customerId")).isEqualTo(customerId.toString());
    assertThat(linkedDetails.getString("customerName")).isEqualTo(CUSTOMER_NAME);

    // 7. Cliente desvinculado: só o id, que é o rastro suficiente (a linha do cliente não some).
    expect(deleteCustomer(saleId), 200);
    AuditEvent unlinked = eventOf(saleId, "SALE_CUSTOMER_UNLINKED");
    assertSaleEvent(unlinked, saleId);
    assertThat(JsonPath.from(unlinked.details()).getString("customerId"))
        .isEqualTo(customerId.toString());

    // 8. Item removido: o item como ele saiu e a venda zerada.
    expect(deleteItem(saleId, productId), 200);
    AuditEvent itemRemoved = eventOf(saleId, "SALE_ITEM_REMOVED");
    assertSaleEvent(itemRemoved, saleId);
    JsonPath itemRemovedDetails = JsonPath.from(itemRemoved.details());
    assertThat(itemRemovedDetails.getString("productId")).isEqualTo(productId.toString());
    assertThat(number(itemRemovedDetails.get("quantity"))).isEqualByComparingTo("3");
    assertThat(number(itemRemovedDetails.get("lineTotal"))).isEqualByComparingTo("29.70");
    assertThat(number(itemRemovedDetails.get("subtotal"))).isEqualByComparingTo("0.00");
    assertThat(itemRemovedDetails.getInt("itemCount")).isZero();

    // 9. Venda cancelada: o motivo no reason do evento e o estado final nos details.
    expect(cancel(saleId, "{\"reason\": \"cliente desistiu\"}"), 200);
    AuditEvent cancelled = eventOf(saleId, "SALE_CANCELLED");
    assertSaleEvent(cancelled, saleId);
    assertThat(cancelled.reason()).isEqualTo("cliente desistiu");
    JsonPath cancelledDetails = JsonPath.from(cancelled.details());
    assertThat(cancelledDetails.getString("status")).isEqualTo("CANCELLED");
    assertThat(number(cancelledDetails.get("total"))).isEqualByComparingTo("0.00");
    assertThat(cancelledDetails.getInt("itemCount")).isZero();

    // Operação barrada depois do cancelamento: 409 sem inventar evento — o rastro é só das nove
    // operações que efetivaram, conferido por action + entity_id, nunca por contagem global.
    Response refused = postItem(saleId, productId, "1");

    assertThat(refused.statusCode()).as("resposta: %s", refused.asString()).isEqualTo(409);
    assertThat(refused.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");
    assertThat(actionsOf(saleId))
        .as("nove operações, nove eventos")
        .containsExactlyInAnyOrder(
            "SALE_CREATED",
            "SALE_ITEM_ADDED",
            "SALE_ITEM_QUANTITY_CHANGED",
            "SALE_DISCOUNT_APPLIED",
            "SALE_DISCOUNT_REMOVED",
            "SALE_CUSTOMER_LINKED",
            "SALE_CUSTOMER_UNLINKED",
            "SALE_ITEM_REMOVED",
            "SALE_CANCELLED");
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), itens, vendas, eventos das vendas; movimentos e
   * sessão de caixa; série; produtos; clientes; e os eventos, as sessões de auth, os papéis e o
   * usuário do ator, nessa ordem.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID saleId : saleIds) {
        execute(connection, "delete from sale_items where sale_id = ?", saleId);
        execute(connection, "delete from sales where id = ?", saleId);
        execute(connection, "delete from audit_events where entity_id = ?", saleId);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
      for (UUID productId : productIds) {
        execute(connection, "delete from products where id = ?", productId);
      }
      for (UUID customerId : customerIds) {
        execute(connection, "delete from customers where id = ?", customerId);
      }
      for (UUID actorId : userIds) {
        execute(
            connection,
            "delete from audit_events where actor_user_id = ? or entity_id = ?",
            actorId,
            actorId);
        execute(
            connection,
            "delete from audit_events where entity_id in"
                + " (select id from auth_sessions where user_id = ?)",
            actorId);
        execute(connection, "delete from auth_sessions where user_id = ?", actorId);
        execute(connection, "delete from user_roles where user_id = ?", actorId);
        execute(connection, "delete from users where id = ?", actorId);
      }
    }
  }

  /** Abre o CAIXA-01 pela API (passo 607) e devolve a resposta; a chave é nova e rastreada. */
  private Response open() {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"openingAmount\": 100.00}")
        .when()
        .post(OPEN_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** Abre a venda pela API (passo 807) com chave nova: é o primeiro evento do fluxo. */
  private Response createSale() {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .when()
        .post(SALES_PATH)
        .then()
        .extract()
        .response();
  }

  /** Item da venda pela rota do 809b: o produto vem pelo id, sem barcode no cenário. */
  private Response postItem(UUID saleId, UUID productId, String quantity) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"productId\": \"%s\", \"quantity\": %s}".formatted(productId, quantity))
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
  }

  /** Troca a quantidade pela rota do 809b; {@code {itemId}} é o productId. */
  private Response patchItem(UUID saleId, UUID productId, String quantity) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"quantity\": %s}".formatted(quantity))
        .when()
        .patch(SALES_PATH + "/" + saleId + "/items/" + productId)
        .then()
        .extract()
        .response();
  }

  /** Remove o item pela rota do 809b; {@code {itemId}} é o productId. */
  private Response deleteItem(UUID saleId, UUID productId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/items/" + productId)
        .then()
        .extract()
        .response();
  }

  /** Aplica o desconto pela rota do 811b (exige {@code sale.discount.apply}, que o GERENTE tem). */
  private Response putDiscount(UUID saleId, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .put(SALES_PATH + "/" + saleId + "/discount")
        .then()
        .extract()
        .response();
  }

  /** Tira o desconto pela rota do 811b. */
  private Response deleteDiscount(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/discount")
        .then()
        .extract()
        .response();
  }

  /** Vincula o cliente pela rota do 811b. */
  private Response putCustomer(UUID saleId, UUID customerId) {
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

  /** Desvincula o cliente pela rota do 811b. */
  private Response deleteCustomer(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/customer")
        .then()
        .extract()
        .response();
  }

  /** Cancela a venda pela rota do 813; a chave de idempotência é obrigatória (§8). */
  private Response cancel(UUID saleId, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + saleId + "/cancel")
        .then()
        .extract()
        .response();
  }

  /** Cria o usuário pelo caso de uso (passo 107), loga de verdade e devolve o token da sessão. */
  private String login() {
    return given()
        .contentType("application/json")
        .header(AuthResource.CLIENT_HEADER, CLIENT)
        .body(
            """
            {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
            """
                .formatted(username, PASSWORD, registerId))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** A operação do fluxo precisa ter passado; a resposta entra no relatório quando não passou. */
  private static void expect(Response response, int status) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(status);
  }

  /** Campos comuns dos eventos de venda: alvo, ator do token e origem TUI da sessão do PDV. */
  private void assertSaleEvent(AuditEvent event, UUID saleId) {
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(saleId.toString());
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isEqualTo(CLIENT);
    assertThat(event.actorUserId()).isEqualTo(userId);
    assertThat(event.actorUsername()).isEqualTo(username);
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.auditoria." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Produto do cenário pela porta do catálogo, em transação própria como o 405 o grava. */
  private UUID seedProduct() {
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(), PRODUCT_NAME, null, null, null, "UN", PRICE, null)));
    productIds.add(id);
    return id;
  }

  /** Cliente do cenário direto no banco: fixture da venda, não o alvo da suíte. */
  private UUID seedCustomer() throws SQLException {
    UUID id =
        insertReturningId(
            "insert into customers (id, store_id, name) values (uuidv7(), ?, ?) returning id",
            storeId(),
            CUSTOMER_NAME);
    customerIds.add(id);
    return id;
  }

  /**
   * Evento da ação pelo alvo da venda, com {@code details} no texto do jsonb; falha se houver zero
   * ou mais de um evento para o par — a conferência é sempre por {@code entity_id} + {@code
   * action}.
   */
  private AuditEvent eventOf(UUID saleId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id::text as entity_id, source, actor_user_id,"
                    + " actor_username, reason, details::text as details from audit_events"
                    + " where action = ? and entity_id = ?")) {
      statement.setString(1, action);
      statement.setObject(2, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s da venda %s", action, saleId).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("entity_id"),
                resultSet.getString("source"),
                resultSet.getObject("actor_user_id", UUID.class),
                resultSet.getString("actor_username"),
                resultSet.getString("reason"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma operação, um evento %s", action).isFalse();
        return event;
      }
    }
  }

  /** As ações registradas para a venda: o fluxo inteiro, uma vez cada. */
  private List<String> actionsOf(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action from audit_events where entity_id = ? order by action")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<String> actions = new ArrayList<>();
        while (resultSet.next()) {
          actions.add(resultSet.getString("action"));
        }
        return actions;
      }
    }
  }

  /** Id da loja do seed, direto do banco: a fixture precisa de uma loja real (FK restrict). */
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

  private UUID insertReturningId(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("insert com returning devolve o id").isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /**
   * Valor numérico do JSON como BigDecimal: o JsonPath do RestAssured devolve decimal como {@code
   * Float}, então o dinheiro do evento é conferido por valor ({@code compareTo}), não pelo texto.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no details do evento").isNotNull();
    return new BigDecimal(value.toString());
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, sql, parameters);
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

  /** Linha de {@code audit_events} com os campos que a suíte confere. */
  private record AuditEvent(
      String entityType,
      String entityId,
      String source,
      UUID actorUserId,
      String actorUsername,
      String reason,
      String details) {}
}
