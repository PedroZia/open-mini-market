package com.minimarket.audit.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.api.AuthResource;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ApplyStockMovementCommand;
import com.minimarket.inventory.application.StockService;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.sales.api.SalesResource;
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
import java.time.Instant;
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
 * Histórico por entidade (passo 1002) contra PostgreSQL real (Dev Services): a vida de uma venda de
 * verdade reconstruída pela consulta do 1001 — {@code GET
 * /api/v1/audit-events?entityType=SALE&entityId=...&sort=occurredat,asc&size=100} —, que o §7.3
 * define como a consulta por entidade. Nenhuma rota nova: é o filtro do próprio recurso, e o teste
 * é o aceite.
 *
 * <p>O cenário é uma venda de verdade pela API, sem atalho de banco: o ator loga de verdade
 * vinculado ao {@code CAIXA-01} do seed, abre a sessão de caixa, abre a venda, bipa dois itens por
 * <em>barcode</em> (o servidor resolve o código, BR-14), aplica o desconto com motivo, registra o
 * pagamento em dinheiro e conclui — as seis operações que o aceite pede na linha do tempo. O ator é
 * um GERENTE porque o desconto exige {@code sale.discount.apply} (BR-04, §4.5), que o OPERADOR não
 * tem: num minimercado é o gerente quem opera o caixa e o histórico fica com um ator só, como no
 * {@code SalesAuditTest} (814b). A leitura é do ADMIN da suíte, o dono de {@code audit.read} que o
 * passo 1001 já usa.
 *
 * <p>A conferência é a do aceite: a consulta filtrada devolve exatamente os seis eventos da venda —
 * {@code SALE_CREATED}, dois {@code SALE_ITEM_ADDED}, {@code SALE_DISCOUNT_APPLIED}, {@code
 * PAYMENT_ADDED} e {@code SALE_COMPLETED} — em ordem cronológica ({@code occurred_at} e o desempate
 * por {@code id}), todos com o mesmo ator e nenhum evento de outra venda no meio. O evento do
 * desconto carrega o motivo em {@code reason}: é ele o rastro humano que a investigação procura.
 *
 * <p>O request HTTP comita, então a limpeza do {@code @AfterEach} espelha o {@code
 * FullSaleFlowTest} (910): chaves de idempotência, pagamentos, itens, venda e os eventos dela,
 * movimentos e saldos de estoque, produtos, movimentos e sessão de caixa, série e, por fim, os
 * eventos, as sessões de auth, os papéis e o usuário do ator — o banco é compartilhado com os
 * demais testes do fork e o {@code CAIXA-01} é fixture compartilhada.
 */
@QuarkusTest
class AuditSaleTimelineTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String AUDIT_EVENTS_PATH = AuditEventsResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /** Cliente da sessão: a TUI opera o PDV e é a origem dos eventos do fluxo. */
  private static final String CLIENT = "TUI";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Bloco aleatório comum aos dois barcodes: sem colidir com o produto de outra execução. */
  private static final String BARCODE_BLOCK =
      String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));

  /** Abertura do caixa: a venda em dinheiro entra no esperado (passo 909), conferido no 910. */
  private static final String OPENING_AMOUNT = "100.00";

  /** Estoque inicial de cada produto: a venda baixa 2 e 1 unidades (BR-08). */
  private static final String INITIAL_STOCK = "50.000";

  /** Motivo do desconto: o rastro humano que o aceite manda ler no {@code reason} (BR-04). */
  private static final String DISCOUNT_REASON = "cliente fidelidade";

  /** Caso de uso da criação de usuário (passo 107): o ator do teste é fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: os produtos do cenário nascem por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Porta única de alteração de saldo (passo 703): o estoque inicial da fixture nasce por aqui. */
  @Inject StockService stockService;

  /** Chaves de idempotência usadas pelo fluxo: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste (com pagamentos, itens e eventos delas). */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários do cenário (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Produtos semeados pelo teste (e os movimentos e saldos deles). */
  private final List<UUID> productIds = new ArrayList<>();

  /** Produtos do cenário, na ordem em que a venda os bipa. */
  private final List<ProductFixture> products = new ArrayList<>();

  private String actorUsername;

  private String actorToken;

  private UUID actorId;

  private UUID registerId;

  /**
   * Prepara o ator e os dois produtos: o GERENTE loga de verdade no {@code CAIXA-01} (a sessão do
   * PDV é vinculada ao caixa, BR-11) e cada produto nasce com barcode único e estoque inicial.
   */
  @BeforeEach
  void prepareActorAndProducts() throws SQLException {
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    actorUsername = "historico.venda.gerente." + SUFFIX;
    actorId =
        createUserUseCase
            .execute(
                new CreateUserCommand(actorUsername, actorUsername, PASSWORD, List.of("GERENTE")))
            .id();
    userIds.add(actorId);
    actorToken = login(actorUsername, registerId);
    // Produtos com barcode único e estoque inicial; a ordem é a do bipe no fluxo abaixo.
    products.add(seedProduct("Arroz 5kg", "10.00", "2", 1));
    products.add(seedProduct("Café Torrado 500g", "5.00", "1", 2));
  }

  @Test
  @DisplayName(
      "GET /audit-events por entityType+entityId: a vida da venda aparece em ordem cronológica, com ator e motivo")
  void reconstructsTheSaleTimeline() throws SQLException {
    // 1. Abrir o caixa: a sessão autenticada já está vinculada ao CAIXA-01 pelo login, e a operação
    //    idempotente (§8) leva Idempotency-Key nova.
    Response opened = openCashRegister();
    assertThat(opened.statusCode()).as("resposta: %s", opened.asString()).isEqualTo(201);
    UUID cashSessionId = UUID.fromString(opened.jsonPath().getString("id"));
    cashSessionIds.add(cashSessionId);

    // 2. Abrir a venda: caixa, operador e loja saem da sessão autenticada (BR-11/BR-06) e o número
    //    sai do alocador do servidor — o primeiro evento da linha do tempo.
    Response created = createSale();
    assertThat(created.statusCode()).as("resposta: %s", created.asString()).isEqualTo(201);
    UUID saleId = UUID.fromString(created.jsonPath().getString("id"));
    saleIds.add(saleId);

    // 3. Dois itens por barcode: o leitor manda a string bruta e quem resolve o produto é o
    //    servidor (BR-14); 2 × 10,00 + 1 × 5,00 = 25,00, recalculado pelo servidor (BR-12).
    Response withItems = null;
    for (ProductFixture product : products) {
      String body =
          "{\"barcode\": \"%s\", \"quantity\": %s}"
              .formatted(product.barcode(), product.quantity());
      Response item = addItem(saleId, body);
      assertThat(item.statusCode())
          .as("item %s: %s", product.name(), item.asString())
          .isEqualTo(200);
      withItems = item;
    }
    assertThat(withItems).as("venda com os dois itens bipados").isNotNull();
    assertThat(withItems.jsonPath().getList("items")).hasSize(2);
    assertThat(decimal(withItems.jsonPath().getMap("$"), "subtotal")).isEqualByComparingTo("25.00");

    // 4. Desconto com motivo: 10% de 25,00 = 2,50 pelo servidor (BR-03); o motivo vai para o
    //    reason do evento (BR-04).
    Response discounted = putDiscount(saleId);
    assertThat(discounted.statusCode()).as("resposta: %s", discounted.asString()).isEqualTo(200);
    assertThat(decimal(discounted.jsonPath().getMap("$"), "discountAmount"))
        .isEqualByComparingTo("2.50");
    assertThat(decimal(discounted.jsonPath().getMap("$"), "total")).isEqualByComparingTo("22.50");

    // 5. Pagamento em dinheiro cobre o total: 22,50 de 30,00 entregues → 7,50 de troco (BR-05).
    Response payment = payCash(saleId, "22.50", "30.00");
    assertThat(payment.statusCode()).as("resposta: %s", payment.asString()).isEqualTo(201);
    assertThat(decimal(payment.jsonPath().getMap("$"), "paidAmount")).isEqualByComparingTo("22.50");
    assertThat(decimal(payment.jsonPath().getMap("$"), "changeAmount"))
        .isEqualByComparingTo("7.50");

    // 6. Concluir: transição de estado que baixa estoque, entra no caixa e fecha a trilha de
    //    auditoria na mesma transação (passo 906).
    Response completed = complete(saleId);
    assertThat(completed.statusCode()).as("resposta: %s", completed.asString()).isEqualTo(200);
    assertThat(completed.jsonPath().getString("status")).isEqualTo("COMPLETED");

    // 7. A consulta por entidade do §7.3, do jeito que o cliente a faz: filtro por entityType +
    //    entityId, ordem cronológica crescente e a página inteira da vida da venda.
    Response timeline = saleTimeline(saleId);
    assertThat(timeline.statusCode()).as("resposta: %s", timeline.asString()).isEqualTo(200);
    assertThat(timeline.jsonPath().getInt("page")).isZero();
    assertThat(timeline.jsonPath().getInt("size")).isEqualTo(100);
    assertThat(timeline.jsonPath().getLong("totalItems"))
        .as("a venda inteira: uma operação, um evento")
        .isEqualTo(6);
    assertThat(timeline.jsonPath().getInt("totalPages")).isEqualTo(1);

    List<Map<String, Object>> events = timeline.jsonPath().getList("items");

    assertThat(events).hasSize(6);
    assertThat(fieldOf(events, "action"))
        .as("a linha do tempo na ordem do fluxo: as seis operações, uma vez cada")
        .containsExactly(
            "SALE_CREATED",
            "SALE_ITEM_ADDED",
            "SALE_ITEM_ADDED",
            "SALE_DISCOUNT_APPLIED",
            "PAYMENT_ADDED",
            "SALE_COMPLETED");
    assertThat(fieldOf(events, "entityType")).containsOnly("SALE");
    assertThat(fieldOf(events, "entityId"))
        .as("nenhum evento de outra venda no meio: só o alvo consultado")
        .containsOnly(saleId.toString());
    assertThat(fieldOf(events, "actorUsername"))
        .as("um ator só: quem opera o caixa e autoriza o desconto")
        .containsOnly(actorUsername);
    assertThat(fieldOf(events, "source")).containsOnly(CLIENT);
    assertThat(events.getFirst().get("actorUserId")).isEqualTo(actorId.toString());

    assertThat(
            events.stream().map(event -> Instant.parse((String) event.get("occurredAt"))).toList())
        .as("ordem cronológica crescente")
        .isSorted();
    assertThat(events.stream().map(event -> ((Number) event.get("id")).longValue()).toList())
        .as("identity do log: a ordem de gravação, sem repetição")
        .isSorted()
        .doesNotHaveDuplicates();

    assertThat(events.stream().map(event -> event.get("reason")).toList())
        .as("o motivo do desconto é o rastro humano; só o SALE_DISCOUNT_APPLIED tem reason")
        .containsExactly(null, null, null, DISCOUNT_REASON, null, null);
  }

  /**
   * Remove o que o fluxo comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves de
   * idempotência (que referenciam o usuário), pagamentos, itens, venda e os eventos dela;
   * movimentos e saldos de estoque antes do produto; movimentos e sessão de caixa; a série; e, por
   * fim, os eventos, as sessões de auth, os papéis e o usuário do ator.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID saleId : saleIds) {
        execute(connection, "delete from payments where sale_id = ?", saleId);
        execute(connection, "delete from sale_items where sale_id = ?", saleId);
        execute(connection, "delete from sales where id = ?", saleId);
        execute(connection, "delete from audit_events where entity_id = ?", saleId);
      }
      for (UUID productId : productIds) {
        execute(connection, "delete from stock_movements where product_id = ?", productId);
        execute(connection, "delete from product_stocks where product_id = ?", productId);
        execute(connection, "delete from products where id = ?", productId);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
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
    }
  }

  /** Abre o caixa do cenário pela API (passo 607) com chave nova; o status fica com o teste. */
  private Response openCashRegister() {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"openingAmount\": %s}".formatted(OPENING_AMOUNT))
        .when()
        .post(OPEN_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** Abre a venda no caixa da sessão (passo 807) com chave nova; o status fica com o teste. */
  private Response createSale() {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .when()
        .post(SALES_PATH)
        .then()
        .extract()
        .response();
  }

  /**
   * Bipa o item pela rota do 809b; o corpo traz o barcode bruto, como o leitor o digitou (BR-14).
   */
  private Response addItem(UUID saleId, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
  }

  /**
   * Aplica o desconto pela rota do 811b: o §8 só exige {@code Idempotency-Key} em {@code POST
   * /sales}, pagamentos, conclusão, cancelamento e dinheiro — aqui não há chave.
   */
  private Response putDiscount(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .contentType("application/json")
        .body(
            "{\"type\": \"PERCENT\", \"value\": 10, \"reason\": \"%s\"}".formatted(DISCOUNT_REASON))
        .when()
        .put(SALES_PATH + "/" + saleId + "/discount")
        .then()
        .extract()
        .response();
  }

  /** Registra o pagamento pela rota do 905 com chave nova; o status fica com o teste. */
  private Response payCash(UUID saleId, String amount, String tenderedAmount) {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body(
            "{\"method\": \"CASH\", \"amount\": %s, \"tenderedAmount\": %s}"
                .formatted(amount, tenderedAmount))
        .when()
        .post(SALES_PATH + "/" + saleId + "/payments")
        .then()
        .extract()
        .response();
  }

  /** Conclui a venda pela rota do 907 com chave nova; o status fica com o teste. */
  private Response complete(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .when()
        .post(SALES_PATH + "/" + saleId + "/complete")
        .then()
        .extract()
        .response();
  }

  /**
   * A consulta por entidade do §7.3 com o token do ADMIN da suíte — o dono de {@code audit.read}: o
   * filtro {@code entityType=SALE} + {@code entityId} responde "histórico completo desta venda", e
   * {@code sort=occurredat,asc} devolve a linha do tempo do começo para o fim.
   */
  private Response saleTimeline(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .queryParam("entityType", "SALE")
        .queryParam("entityId", saleId.toString())
        .queryParam("sort", "occurredat,asc")
        .queryParam("size", "100")
        .when()
        .get(AUDIT_EVENTS_PATH)
        .then()
        .extract()
        .response();
  }

  /**
   * Semeia um produto do cenário pela porta do catálogo (em transação própria, como o cadastro o
   * grava) e o estoque inicial pelo {@code StockService} (passo 703). O barcode é {@code 789102 +
   * bloco aleatório + índice + 90}: os 13 dígitos de um EAN-13, com prefixo distinto do {@code
   * FullSaleFlowTest} para nunca colidir com o produto dele.
   */
  private ProductFixture seedProduct(String name, String price, String quantity, int index)
      throws SQLException {
    String barcode = "789102" + BARCODE_BLOCK + index + "90";
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(),
                            name,
                            barcode,
                            null,
                            null,
                            "UN",
                            new BigDecimal(price),
                            null)));
    productIds.add(id);
    stockService.applyMovement(
        new ApplyStockMovementCommand(
            id,
            StockMovementType.INITIAL,
            new BigDecimal(INITIAL_STOCK),
            null,
            null,
            null,
            null,
            actorId));
    return new ProductFixture(name, barcode, quantity);
  }

  /** Login pela API (passo 205) vinculando a sessão ao caixa e declarando a origem TUI do PDV. */
  private static String login(String username, UUID cashRegisterId) {
    return given()
        .contentType("application/json")
        .header(AuthResource.CLIENT_HEADER, CLIENT)
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

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "historico.venda." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Id da loja do seed, direto do banco: os produtos precisam de uma loja real (FK restrict). */
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

  /** Campo de texto dos eventos da página, na ordem em que vieram. */
  private static List<String> fieldOf(List<Map<String, Object>> events, String field) {
    return events.stream().map(event -> (String) event.get(field)).toList();
  }

  /** Campo de dinheiro do corpo como BigDecimal (o JSON pode vir como float ou BigDecimal). */
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

  /** Produto do cenário: o que o fluxo bipa, pelo barcode que o servidor resolve. */
  private record ProductFixture(String name, String barcode, String quantity) {}
}
