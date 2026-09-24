package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ApplyStockMovementCommand;
import com.minimarket.inventory.application.StockService;
import com.minimarket.inventory.domain.StockMovementType;
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
 * Fechamento de caixa com vendas (passo 909) contra PostgreSQL real (Dev Services): o GERENTE do
 * cenário nasce pelo caso de uso e o login vincula a sessão ao {@code CAIXA-01} do seed, o caixa e
 * as vendas abrem pela própria API (passos 607, 807, 809b, 905 e 907) e o produto é semeado pela
 * porta do catálogo com estoque inicial pelo {@code StockService} (passo 703). O alvo é o contrato
 * de {@code GET /cash-sessions/{id}/summary} com a quebra por forma de pagamento e de {@code POST
 * /cash-registers/{id}/close} bloqueado por venda em andamento — a conta do esperado é do caso de
 * uso (passo 611) e a baixa de estoque da conclusão é do 906, cada um com teste próprio.
 *
 * <p>O request HTTP comita, então o teste limpa no {@code @AfterEach} tudo o que comitou, na ordem
 * que as FKs {@code restrict} exigem: pagamentos e itens antes da venda, movimentos e saldo antes
 * do produto, movimentos de caixa antes da sessão, a série da venda, chaves de idempotência (que
 * referenciam o usuário), sessões de auth, papéis e o usuário. O {@link
 * com.minimarket.support.TestAdmin} apaga a fixture dele depois.
 */
@QuarkusTest
class CashSessionSalesResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = "/api/v1/sales";

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String CLOSE_PATH = "/api/v1/cash-registers/%s/close";

  private static final String WITHDRAWALS_PATH = "/api/v1/cash-registers/%s/withdrawals";

  private static final String SUMMARY_PATH = "/api/v1/cash-sessions/%s/summary";

  /** Caixa do seed da V11: existe sempre, é a fixture do cenário. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "30";

  private static final String PRODUCT_NAME = "Café 500g";

  /** Preço do produto da fixture: 10,00 a unidade deixa as contas do cenário redondas. */
  private static final String PRICE = "10.00";

  /** Estoque inicial do produto: as quatro unidades vendidas fecham em 96,000 no saldo. */
  private static final String INITIAL_STOCK = "100.000";

  /** As cinco formas de pagamento, como o JSON as traz (ordem do enum, passo 909). */
  private static final List<String> PAYMENT_METHODS =
      List.of("CASH", "PIX", "DEBIT", "CREDIT", "VOUCHER");

  /** Caso de uso da criação de usuário (passo 107): o ator do teste é fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Porta única de alteração de saldo (passo 703): o estoque inicial da fixture nasce por aqui. */
  @Inject StockService stockService;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste (e os pagamentos e itens delas). */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth e papéis deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Produtos semeados pelo teste (e os movimentos e saldos deles). */
  private final List<UUID> productIds = new ArrayList<>();

  /** GERENTE do cenário: tem venda, caixa e sangria — tudo o que o fluxo do teste usa. */
  private String username;

  private String token;

  private UUID registerId;

  private UUID productId;

  /** GERENTE autenticado no {@code CAIXA-01}, caixa aberto com 100,00 e produto com estoque. */
  @BeforeEach
  void opensCashWithProduct() throws SQLException {
    username = "caixa.vendas." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(username, List.of("GERENTE"));
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, "100.00"));
    productId = seedProduct();
    seedStock();
  }

  @Test
  @DisplayName(
      "resumo: dinheiro das vendas entra no esperado, cartão não, e a quebra traz as cinco formas")
  void summarizesSalesByPaymentMethod() throws SQLException {
    UUID sessionId = cashSessionIds.getFirst();

    completeSale("1", "{\"method\": \"CASH\", \"amount\": 10.00, \"tenderedAmount\": 20.00}");
    completeSale("2", "{\"method\": \"CASH\", \"amount\": 20.00, \"tenderedAmount\": 30.00}");
    completeSale("1", "{\"method\": \"CREDIT\", \"amount\": 10.00}");
    withdrawal("25.00", "depósito bancário");

    Response response = get(SUMMARY_PATH.formatted(sessionId));

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body)
        .as("contrato do resumo: nem storeId, nem cashRegisterId, nem version")
        .containsOnlyKeys(
            "sessionId",
            "status",
            "openingAmount",
            "expectedAmount",
            "countedAmount",
            "differenceAmount",
            "totalsByType",
            "paymentsByMethod");
    assertThat(money(body, "expectedAmount"))
        .as("100 de abertura + 30 das vendas em dinheiro − 25 de sangria")
        .isEqualByComparingTo("105.00");
    assertThat(money(totalsByType(body), "SALE"))
        .as("só a parcela em dinheiro das conclusões vira movimento SALE")
        .isEqualByComparingTo("30.00");

    Map<String, Object> payments = paymentsByMethod(body);

    assertThat(payments.keySet())
        .as("as cinco formas, na ordem do enum, mesmo sem pagamento nelas")
        .containsExactly(PAYMENT_METHODS.toArray(String[]::new));
    assertThat(money(payments, "CASH"))
        .as("os dois pagamentos em dinheiro somados")
        .isEqualByComparingTo("30.00");
    assertThat(money(payments, "CREDIT"))
        .as("a venda no cartão aparece na quebra, mas fora do dinheiro esperado")
        .isEqualByComparingTo("10.00");
    for (String method : List.of("PIX", "DEBIT", "VOUCHER")) {
      assertThat(money(payments, method))
          .as("forma %s sem pagamento no cenário", method)
          .isEqualByComparingTo("0.00");
    }

    Response closed = close("105.00");

    assertThat(closed.statusCode()).as("resposta: %s", closed.asString()).isEqualTo(200);

    Map<String, Object> closedBody = closed.jsonPath().getMap("$");

    assertThat(money(closedBody, "expectedAmount"))
        .as("o fechamento recalcula o mesmo esperado")
        .isEqualByComparingTo("105.00");
    assertThat(money(closedBody, "differenceAmount")).isEqualByComparingTo("0.00");

    Map<String, Object> closedSummary =
        get(SUMMARY_PATH.formatted(sessionId)).jsonPath().getMap("$");

    assertThat(closedSummary.get("status")).isEqualTo("CLOSED");
    assertThat(money(closedSummary, "expectedAmount")).isEqualByComparingTo("105.00");
    assertThat(paymentsByMethod(closedSummary))
        .as("o resumo do fechamento traz a mesma quebra por forma")
        .isEqualTo(payments);
  }

  @Test
  @DisplayName("fechar com venda aberta: 409 SESSION_HAS_OPEN_SALES e a sessão continua aberta")
  void refusesCloseWithOpenSale() throws SQLException {
    UUID sessionId = cashSessionIds.getFirst();
    UUID openSaleId = createSale();

    assertThat(addItem(openSaleId, "1").statusCode()).as("item da venda aberta").isEqualTo(200);

    Response response = close("100.00");

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SESSION_HAS_OPEN_SALES");
    assertThat(response.jsonPath().getString("title"))
        .isEqualTo("Sessão de caixa com venda em andamento");

    SessionRow blocked = storedSession(sessionId);

    assertThat(blocked.status()).as("o fechamento bloqueado não muda a sessão").isEqualTo("OPEN");
    assertThat(blocked.countedAmount()).isNull();
    assertThat(blocked.expectedAmount()).isNull();
    assertThat(countAuditEvents(sessionId, "CASH_SESSION_CLOSED"))
        .as("o bloqueio não grava fechamento")
        .isZero();

    pay(openSaleId, "{\"method\": \"CASH\", \"amount\": 10.00, \"tenderedAmount\": 20.00}");
    complete(openSaleId);

    Response closed = close("110.00");

    assertThat(closed.statusCode())
        .as("venda concluída não bloqueia mais: %s", closed.asString())
        .isEqualTo(200);
    assertThat(money(closed.jsonPath().getMap("$"), "expectedAmount"))
        .as("100 de abertura + 10 da venda em dinheiro")
        .isEqualByComparingTo("110.00");
  }

  /** Remove o que o teste comitou, na ordem que as FKs {@code restrict} exigem. */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (UUID id : saleIds) {
        execute(connection, "delete from payments where sale_id = ?", id);
        execute(connection, "delete from sale_items where sale_id = ?", id);
        execute(connection, "delete from sales where id = ?", id);
        execute(connection, "delete from audit_events where entity_id = ?", id);
      }
      for (UUID id : productIds) {
        execute(connection, "delete from stock_movements where product_id = ?", id);
        execute(connection, "delete from product_stocks where product_id = ?", id);
        execute(connection, "delete from products where id = ?", id);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
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
    }
  }

  /** Abre o caixa pela API (passo 607) com chave nova e devolve o id da sessão comitada. */
  private UUID open(UUID registerId, String openingAmount) {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .contentType("application/json")
            .body("{\"openingAmount\": %s}".formatted(openingAmount))
            .when()
            .post(OPEN_PATH.formatted(registerId))
            .then()
            .statusCode(201)
            .extract()
            .response();
    UUID sessionId = UUID.fromString(response.jsonPath().getString("id"));
    cashSessionIds.add(sessionId);
    return sessionId;
  }

  /** Abre a venda pela API (passo 807) com chave nova e devolve o id da venda comitada. */
  private UUID createSale() {
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

  /** Item da venda pela rota do 809b: {@code quantity} × 10,00 de total. */
  private Response addItem(UUID saleId, String quantity) {
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

  /**
   * Venda completa do cenário: abre, bipa {@code quantity} unidades, paga com o corpo informado
   * (passo 905) e conclui (passo 907), conferindo cada status — o alvo do teste é o resumo, e é
   * aqui que a fixture sai do caminho.
   */
  private void completeSale(String quantity, String paymentBody) {
    UUID saleId = createSale();

    assertThat(addItem(saleId, quantity).statusCode())
        .as("item da venda do cenário")
        .isEqualTo(200);
    pay(saleId, paymentBody);
    complete(saleId);
  }

  /** Pagamento da venda pela rota do 905 com chave nova; o status fica com o chamador. */
  private Response pay(UUID saleId, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + saleId + "/payments")
        .then()
        .statusCode(201)
        .extract()
        .response();
  }

  /** Conclusão da venda pela rota do 907 com chave nova; o status fica com o chamador. */
  private Response complete(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .when()
        .post(SALES_PATH + "/" + saleId + "/complete")
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  /** Sangria pela API (passo 609) com chave nova: o dinheiro que sai da gaveta no cenário. */
  private void withdrawal(String amount, String reason) {
    given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason))
        .when()
        .post(WITHDRAWALS_PATH.formatted(registerId))
        .then()
        .statusCode(201);
  }

  /** Fechamento pela API (passo 612) com chave nova; o status fica com cada teste. */
  private Response close(String countedAmount) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"countedAmount\": %s}".formatted(countedAmount))
        .when()
        .post(CLOSE_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** GET autenticado como o GERENTE do cenário. */
  private Response get(String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(path)
        .then()
        .extract()
        .response();
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
                            new BigDecimal(PRICE),
                            null)));
    productIds.add(id);
    return id;
  }

  /** Estoque inicial do produto pelo caminho de verdade (passo 703), em transação própria. */
  private void seedStock() throws SQLException {
    stockService.applyMovement(
        new ApplyStockMovementCommand(
            productId,
            StockMovementType.INITIAL,
            new BigDecimal(INITIAL_STOCK),
            null,
            null,
            null,
            null,
            userId(username)));
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.vendas." + SUFFIX + "." + UUID.randomUUID();
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

  /** Id do usuário criado pelo teste, para o {@code created_by_user_id} da fixture. */
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

  /** Status, contado e esperado de {@code cash_sessions}, como o banco os guardou. */
  private SessionRow storedSession(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, counted_amount::text as counted_amount,"
                    + " expected_amount::text as expected_amount"
                    + " from cash_sessions where id = ?")) {
      statement.setObject(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão de caixa %s gravada", sessionId).isTrue();
        return new SessionRow(
            resultSet.getString("status"),
            resultSet.getString("counted_amount"),
            resultSet.getString("expected_amount"));
      }
    }
  }

  /** Eventos da ação para a sessão: um por fechamento efetivado, zero por tentativa bloqueada. */
  private int countAuditEvents(UUID sessionId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where entity_id = ? and action = ?")) {
      statement.setObject(1, sessionId);
      statement.setString(2, action);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal money(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  /** Mapa {@code totalsByType} do corpo. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> totalsByType(Map<String, Object> body) {
    Map<String, Object> totals = (Map<String, Object>) body.get("totalsByType");
    assertThat(totals).as("totalsByType no corpo").isNotNull();
    return totals;
  }

  /** Mapa {@code paymentsByMethod} do corpo, com as chaves das formas como o JSON as traz. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> paymentsByMethod(Map<String, Object> body) {
    Map<String, Object> payments = (Map<String, Object>) body.get("paymentsByMethod");
    assertThat(payments).as("paymentsByMethod no corpo").isNotNull();
    return payments;
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

  /** Linha de {@code cash_sessions} com a conferência como o banco a guardou. */
  private record SessionRow(String status, String countedAmount, String expectedAmount) {}
}
