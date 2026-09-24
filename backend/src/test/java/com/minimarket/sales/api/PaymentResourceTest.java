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
 * Rotas de pagamento da venda na API (passo 905) contra PostgreSQL real (Dev Services): o operador
 * nasce pelo caso de uso, o login vincula a sessão ao {@code CAIXA-01} do seed, o caixa e a venda
 * abrem pela própria API (passos 607 e 807) e o produto é semeado pela porta do catálogo — o alvo é
 * o contrato das rotas, não o cadastro. O 401 sem token é do {@code RouteSecurityTest}; as regras
 * do registro (restante, valor entregue, permissão) são do caso de uso do 904 e as do cancelamento,
 * do 905 — os dois têm unitários próprios.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (linhas de {@code
 * payments}, {@code paid_amount}/{@code change_amount} de {@code sales} e eventos de auditoria) e
 * limpa tudo o que comitou ao final, na ordem que as FKs {@code restrict} exigem: chaves (que
 * referenciam o usuário), pagamentos (antes da venda), itens, vendas, série, eventos, movimentos,
 * sessões de caixa, produtos, sessões de auth, papéis, usuários e os caixas que o cenário do 403
 * criou.
 */
@QuarkusTest
class PaymentResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "19";

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

  private String username;

  private String token;

  private UUID registerId;

  private UUID saleId;

  private UUID productId;

  @BeforeEach
  void openSaleWithItem() throws SQLException {
    username = "vendas.pagamento." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(username, List.of("OPERADOR"));
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, token));
    saleId = createSale(token);
    productId = seedProduct();
    assertThat(addItem(token, "2").statusCode()).as("item da fixture").isEqualTo(200);
  }

  @Test
  @DisplayName("POST dinheiro: 201 com troco, pago da venda e o pagamento no banco")
  void registersCashPaymentWithChange() throws SQLException {
    Response response =
        pay(
            token,
            newKey(),
            "{\"method\": \"CASH\", \"amount\": 19.80, \"tenderedAmount\": 50.00}");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.getHeader("Location"))
        .as("não existe rota de leitura de um pagamento isolado")
        .isNull();

    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(decimal(body, "total")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "paidAmount")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "changeAmount"))
        .as("troco calculado pelo servidor: 50,00 − 19,80 (BR-05)")
        .isEqualByComparingTo("30.20");

    Map<String, Object> payment = firstPayment(response);

    assertThat(payment)
        .as("contrato do pagamento no detalhe")
        .containsOnlyKeys(
            "id",
            "method",
            "amount",
            "tenderedAmount",
            "changeAmount",
            "status",
            "createdByUserId",
            "createdAt",
            "cancelledAt");
    assertThat(UUID.fromString((String) payment.get("id")).version())
        .as("id do pagamento é UUIDv7")
        .isEqualTo(7);
    assertThat(payment.get("method")).isEqualTo("CASH");
    assertThat(decimal(payment, "amount")).isEqualByComparingTo("19.80");
    assertThat(decimal(payment, "tenderedAmount")).isEqualByComparingTo("50.00");
    assertThat(decimal(payment, "changeAmount")).isEqualByComparingTo("30.20");
    assertThat(payment.get("status")).isEqualTo("APPROVED");
    assertThat(payment.get("createdByUserId"))
        .as("o autor é o ator do token, não o cliente (BR-11)")
        .isEqualTo(userId(username).toString());
    assertThat(payment.get("createdAt")).isNotNull();
    assertThat(payment.get("cancelledAt")).isNull();

    List<PaymentRow> rows = paymentRows(saleId);

    assertThat(rows).as("uma linha em payments").hasSize(1);
    assertThat(rows.getFirst().id()).as("a linha é o pagamento do 201").isNotNull();
    assertThat(rows.getFirst().method()).isEqualTo("CASH");
    assertThat(rows.getFirst().amount()).isEqualTo("19.80");
    assertThat(rows.getFirst().tenderedAmount()).isEqualTo("50.00");
    assertThat(rows.getFirst().changeAmount()).isEqualTo("30.20");
    assertThat(rows.getFirst().status()).isEqualTo("APPROVED");
    assertThat(rows.getFirst().createdByUserId()).isEqualTo(userId(username));
    assertThat(rows.getFirst().cancelledAt()).isNull();

    SaleRow stored = saleRow(saleId);

    assertThat(stored.status()).as("pagar não conclui a venda").isEqualTo("OPEN");
    assertThat(stored.paidAmount()).isEqualTo("19.80");
    assertThat(stored.changeAmount()).isEqualTo("30.20");
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).isEqualTo(1);
  }

  @Test
  @DisplayName("POST parcial: 201 com o pago menor que o total e a venda ainda OPEN")
  void registersPartialPayment() throws SQLException {
    Response response = pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}");

    assertThat(response.statusCode()).isEqualTo(201);

    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("status")).as("pagamento parcial não conclui a venda").isEqualTo("OPEN");
    assertThat(decimal(body, "paidAmount")).isEqualByComparingTo("10.00");
    assertThat(decimal(body, "changeAmount")).isEqualByComparingTo("0.00");

    Map<String, Object> payment = firstPayment(response);

    assertThat(payment.get("tenderedAmount")).as("fora do dinheiro não há valor entregue").isNull();
    assertThat(decimal(payment, "changeAmount")).isEqualByComparingTo("0.00");

    assertThat(saleRow(saleId).paidAmount()).isEqualTo("10.00");
    assertThat(paymentRows(saleId)).hasSize(1);
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).isEqualTo(1);
  }

  @Test
  @DisplayName("mesma Idempotency-Key: replay com o mesmo corpo, um único pagamento e um evento")
  void replaysSameKeyWithoutDuplicating() throws SQLException {
    String key = newKey();
    String body = "{\"method\": \"CASH\", \"amount\": 10.00, \"tenderedAmount\": 20.00}";

    Response first = pay(token, key, body);
    assertThat(first.statusCode()).isEqualTo(201);

    Response replay = pay(token, key, body);

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 201").isEqualTo(201);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem registrar de novo")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));

    Response reused =
        pay(token, key, "{\"method\": \"CASH\", \"amount\": 11.00, \"tenderedAmount\": 20.00}");
    assertThat(reused.statusCode()).as("mesma chave com outro corpo é 409").isEqualTo(409);
    assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(paymentRows(saleId)).as("um pagamento, não dois").hasSize(1);
    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).as("um evento, não dois").isEqualTo(1);
    assertThat(saleRow(saleId).paidAmount()).isEqualTo("10.00");
  }

  @Test
  @DisplayName("DELETE: 200 com o pagamento CANCELLED e o pago da venda recalculado")
  void cancelsPaymentRecalculatingPaidAmount() throws SQLException {
    UUID pixId = paymentId(pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}"));
    assertThat(
            pay(
                    token,
                    newKey(),
                    "{\"method\": \"CASH\", \"amount\": 9.80, \"tenderedAmount\": 20.00}")
                .statusCode())
        .as("segundo pagamento do cenário")
        .isEqualTo(201);
    assertThat(saleRow(saleId).paidAmount()).isEqualTo("19.80");

    Response response = cancelPayment(token, pixId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("status"))
        .as("cancelar pagamento não muda o status da venda")
        .isEqualTo("OPEN");
    assertThat(decimal(body, "paidAmount"))
        .as("o pago volta a ser a soma dos aprovados (BR-05)")
        .isEqualByComparingTo("9.80");
    assertThat(decimal(body, "changeAmount"))
        .as("só o troco do pagamento cancelado sai da venda")
        .isEqualByComparingTo("10.20");

    Map<String, Object> cancelled = paymentAt(response, 0);
    Map<String, Object> approved = paymentAt(response, 1);

    assertThat(cancelled.get("id")).isEqualTo(pixId.toString());
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    assertThat(cancelled.get("cancelledAt")).isNotNull();
    assertThat(approved.get("status")).isEqualTo("APPROVED");
    assertThat(approved.get("cancelledAt")).isNull();

    List<PaymentRow> rows = paymentRows(saleId);

    assertThat(rows.getFirst().id()).as("a linha cancelada é o pagamento do path").isEqualTo(pixId);
    assertThat(rows.getFirst().status()).isEqualTo("CANCELLED");
    assertThat(rows.getFirst().cancelledAt()).isNotNull();
    assertThat(rows.get(1).status()).isEqualTo("APPROVED");
    assertThat(saleRow(saleId).paidAmount()).isEqualTo("9.80");
    assertThat(saleRow(saleId).changeAmount()).isEqualTo("10.20");
    assertThat(auditEventCount(saleId, "PAYMENT_CANCELLED")).isEqualTo(1);
  }

  @Test
  @DisplayName("DELETE repetido: 200 no-op, sem novo evento")
  void treatsSecondCancellationAsNoOp() throws SQLException {
    UUID paymentId = paymentId(pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}"));
    assertThat(cancelPayment(token, paymentId).statusCode()).isEqualTo(200);

    Response again = cancelPayment(token, paymentId);

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(decimal(again.jsonPath().getMap("$"), "paidAmount")).isEqualByComparingTo("0.00");
    assertThat(paymentAt(again, 0).get("status")).isEqualTo("CANCELLED");
    assertThat(auditEventCount(saleId, "PAYMENT_CANCELLED")).as("um evento só").isEqualTo(1);
    assertThat(paymentRows(saleId)).hasSize(1);
  }

  @Test
  @DisplayName("cartão acima do restante: 422 PAYMENT_EXCEEDS_TOTAL sem gravar pagamento")
  void rejectsPaymentAboveRemaining() throws SQLException {
    assertThat(pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}").statusCode())
        .isEqualTo(201);

    Response response = pay(token, newKey(), "{\"method\": \"CREDIT\", \"amount\": 9.81}");

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PAYMENT_EXCEEDS_TOTAL");
    assertThat(paymentRows(saleId)).as("a recusa não grava pagamento").hasSize(1);
    assertThat(saleRow(saleId).paidAmount()).isEqualTo("10.00");
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).isEqualTo(1);
  }

  @Test
  @DisplayName("valor entregue inválido: 422 INVALID_TENDERED_AMOUNT nos três casos")
  void rejectsInvalidTenderedAmount() throws SQLException {
    Response cashWithoutTendered =
        pay(token, newKey(), "{\"method\": \"CASH\", \"amount\": 10.00}");
    Response cashBelowAmount =
        pay(token, newKey(), "{\"method\": \"CASH\", \"amount\": 10.00, \"tenderedAmount\": 9.99}");
    Response tenderedOutsideCash =
        pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00, \"tenderedAmount\": 50.00}");

    for (Response response : List.of(cashWithoutTendered, cashBelowAmount, tenderedOutsideCash)) {
      assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(422);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_TENDERED_AMOUNT");
    }

    assertThat(paymentRows(saleId)).as("nenhuma recusa grava pagamento").isEmpty();
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).isZero();
  }

  @Test
  @DisplayName("venda de outro caixa: 403 ACCESS_DENIED no POST e no DELETE, sem tocar na venda")
  void deniesSaleOfAnotherRegister() throws SQLException {
    UUID paymentId = paymentId(pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}"));
    UUID otherRegister = insertCashRegister();
    String otherUsername = "pagamento.outro." + SUFFIX;
    createUser(otherUsername, List.of("OPERADOR"));
    String otherToken = login(otherUsername, otherRegister);

    Response added = pay(otherToken, newKey(), "{\"method\": \"PIX\", \"amount\": 5.00}");
    Response cancelled = cancelPayment(otherToken, paymentId);

    for (Response response : List.of(added, cancelled)) {
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
      assertThat(response.jsonPath().getString("detail"))
          .as("a recusa é da guarda de posse (BR-11), não do porteiro da rota")
          .contains(saleId.toString())
          .doesNotContain("payment.add");
    }

    assertThat(paymentRows(saleId)).as("nada entra nem muda na venda alheia").hasSize(1);
    assertThat(paymentRows(saleId).getFirst().status()).isEqualTo("APPROVED");
    assertThat(saleRow(saleId).paidAmount()).isEqualTo("10.00");
    assertThat(auditEventCount(saleId, "PAYMENT_CANCELLED")).isZero();
  }

  @Test
  @DisplayName("venda concluída: 409 SALE_NOT_OPEN no POST e no DELETE, sem tocar na venda")
  void rejectsCompletedSale() throws SQLException {
    UUID paymentId = paymentId(pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}"));
    completeSale(saleId);

    Response added = pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 5.00}");
    Response cancelled = cancelPayment(token, paymentId);

    for (Response response : List.of(added, cancelled)) {
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");
    }

    assertThat(paymentRows(saleId)).as("o pagamento da venda concluída não é cancelado").hasSize(1);
    assertThat(paymentRows(saleId).getFirst().status()).isEqualTo("APPROVED");
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).isEqualTo(1);
    assertThat(auditEventCount(saleId, "PAYMENT_CANCELLED")).isZero();
  }

  @Test
  @DisplayName("pagamento fora da venda: 404 PAYMENT_NOT_FOUND no DELETE")
  void rejectsUnknownPayment() {
    Response response = cancelPayment(token, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PAYMENT_NOT_FOUND");
  }

  @Test
  @DisplayName("venda inexistente: 404 SALE_NOT_FOUND no POST")
  void rejectsUnknownSale() {
    Response response =
        pay(token, newKey(), UUID.randomUUID(), "{\"method\": \"PIX\", \"amount\": 10.00}");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_FOUND");
  }

  @Test
  @DisplayName("forma inválida: 400 VALIDATION_ERROR e 400 sem Idempotency-Key")
  void rejectsInvalidForm() throws SQLException {
    Response withoutMethod = pay(token, newKey(), "{\"amount\": 10.00}");
    Response withoutAmount = pay(token, newKey(), "{\"method\": \"PIX\"}");
    Response zeroAmount = pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 0.00}");
    Response withoutKey =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .contentType("application/json")
            .body("{\"method\": \"PIX\", \"amount\": 10.00}")
            .when()
            .post(SALES_PATH + "/" + saleId + "/payments")
            .then()
            .extract()
            .response();

    for (Response response : List.of(withoutMethod, withoutAmount, zeroAmount)) {
      assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    assertThat(withoutKey.statusCode())
        .as("a operação é idempotente por contrato (§8)")
        .isEqualTo(400);
    assertThat(withoutKey.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

    assertThat(paymentRows(saleId)).as("a forma inválida nem chega ao caso de uso").isEmpty();
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED")).isZero();
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), pagamentos (antes da venda), itens, vendas, série,
   * eventos, movimentos, sessões de caixa, produtos, sessões de auth, papéis, usuários e caixas
   * extras, nessa ordem.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID id : saleIds) {
        execute(connection, "delete from payments where sale_id = ?", id);
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

  /** Item da venda pela rota do 809b: 2 × 9,90 = 19,80 de total. */
  private Response addItem(String token, String quantity) {
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

  /** POST do pagamento na venda do cenário; o status fica com cada teste. */
  private Response pay(String token, String key, String body) {
    return pay(token, key, saleId, body);
  }

  /** POST do pagamento na venda informada; o status fica com cada teste. */
  private Response pay(String token, String key, UUID id, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, key)
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + id + "/payments")
        .then()
        .extract()
        .response();
  }

  /** DELETE do pagamento da venda do cenário; o status fica com cada teste. */
  private Response cancelPayment(String token, UUID paymentId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/payments/" + paymentId)
        .then()
        .extract()
        .response();
  }

  /** Id do pagamento criado pelo cenário, direto do corpo do 201. */
  private static UUID paymentId(Response response) {
    assertThat(response.statusCode())
        .as("pagamento do cenário: %s", response.asString())
        .isEqualTo(201);
    return UUID.fromString(response.jsonPath().getString("payments[0].id"));
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
      statement.setString(1, "PAGAMENTO." + SUFFIX);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        UUID id = resultSet.getObject("id", UUID.class);
        cashRegisterIds.add(id);
        return id;
      }
    }
  }

  /** Marca a venda como concluída direto no banco: a conclusão pela API só nasce no passo 906. */
  private void completeSale(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "update sales set status = 'COMPLETED', completed_at = now() where id = ?",
          id);
    }
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.pagamento." + SUFFIX + "." + UUID.randomUUID();
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

  /** Pagamentos da venda na ordem de criação, como o banco os guardou. */
  private List<PaymentRow> paymentRows(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id, method, amount::text as amount, tendered_amount::text as"
                    + " tendered_amount, change_amount::text as change_amount, status,"
                    + " created_by_user_id, cancelled_at from payments where sale_id = ?"
                    + " order by created_at, id")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<PaymentRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new PaymentRow(
                  resultSet.getObject("id", UUID.class),
                  resultSet.getString("method"),
                  resultSet.getString("amount"),
                  resultSet.getString("tendered_amount"),
                  resultSet.getString("change_amount"),
                  resultSet.getString("status"),
                  resultSet.getObject("created_by_user_id", UUID.class),
                  resultSet.getObject("cancelled_at")));
        }
        return rows;
      }
    }
  }

  /** Pago, troco e status de {@code sales} como o banco os guardou. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, paid_amount::text as paid_amount, change_amount::text as"
                    + " change_amount from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            resultSet.getString("paid_amount"),
            resultSet.getString("change_amount"));
      }
    }
  }

  /** Pagamento do corpo da resposta pelo índice, na ordem em que a API os devolveu. */
  private static Map<String, Object> paymentAt(Response response, int index) {
    List<Map<String, Object>> payments = response.jsonPath().getList("payments");
    assertThat(payments).as("pagamentos no corpo da resposta").hasSizeGreaterThan(index);
    return payments.get(index);
  }

  /** Primeiro pagamento do corpo da resposta. */
  private static Map<String, Object> firstPayment(Response response) {
    return paymentAt(response, 0);
  }

  /** Eventos da ação para o alvo: um por operação efetivada, zero por tentativa barrada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
  }

  /** Registros da chave de idempotência: um por operação, não um por tentativa. */
  private int countIdempotencyKeys(String key) throws SQLException {
    return queryInt("select count(*) from idempotency_keys where key = ?", key);
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

  /** Linha de {@code payments} como o banco a guardou. */
  private record PaymentRow(
      UUID id,
      String method,
      String amount,
      String tenderedAmount,
      String changeAmount,
      String status,
      UUID createdByUserId,
      Object cancelledAt) {}

  /** Pago, troco e status de {@code sales} como o banco os guardou. */
  private record SaleRow(String status, String paidAmount, String changeAmount) {}
}
