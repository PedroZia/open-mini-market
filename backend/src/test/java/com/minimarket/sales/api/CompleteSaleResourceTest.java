package com.minimarket.sales.api;

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
import java.sql.Timestamp;
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
 * Conclusão da venda na API (passo 907) contra PostgreSQL real (Dev Services): o OPERADOR do
 * cenário nasce pelo caso de uso (tem {@code sale.complete} pelo seed da {@code V3__rbac.sql}), o
 * login vincula a sessão ao {@code CAIXA-01} do seed, o caixa e a venda abrem pela própria API
 * (passos 607 e 807) e item e pagamentos entram pelas rotas do 809b e do 905 — o alvo é o contrato
 * de {@code POST /sales/{id}/complete}, não os passos já cobertos. O produto é semeado pela porta
 * do catálogo com estoque inicial pelo {@code StockService} (passo 703), para o {@code
 * balance_after} do ledger ser determinístico: 10 unidades menos as 2 vendidas dão 8. O 401 sem
 * token é do {@code RouteSecurityTest}; as regras da conclusão (pagamento cobrindo o total, posse,
 * estado, baixa de estoque, movimento de caixa e auditoria) são do caso de uso do 906, que tem
 * integração própria — aqui se prova o contrato HTTP e a idempotência ponta a ponta.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (status de {@code
 * sales}, ledger de {@code stock_movements}, {@code cash_movements} e o evento {@code
 * SALE_COMPLETED}) e limpa tudo o que comitou ao final, na ordem que as FKs {@code restrict}
 * exigem: pagamentos (antes da venda), itens, venda, eventos, movimentos e saldos de estoque,
 * movimentos de caixa, sessão, série, chaves de idempotência (que referenciam o usuário), sessões
 * de auth, papéis, usuários, produtos e os caixas que o cenário do 403 criou.
 */
@QuarkusTest
class CompleteSaleResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "20";

  private static final String PRODUCT_NAME = "Arroz 5kg";

  /** Estoque inicial do produto: o SALE_OUT de 2 unidades fecha em 8,000 no saldo. */
  private static final String INITIAL_STOCK = "10.000";

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Porta única de alteração de saldo (passo 703): o estoque inicial da fixture nasce por aqui. */
  @Inject StockService stockService;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste. */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Produtos semeados pelo teste (e os movimentos/saldos deles). */
  private final List<UUID> productIds = new ArrayList<>();

  /** Caixas extras criados pelo cenário do 403. */
  private final List<UUID> cashRegisterIds = new ArrayList<>();

  /** Operador dono da venda: o token é o da sessão vinculada ao {@code CAIXA-01}. */
  private String username;

  private String token;

  private UUID saleId;

  private UUID productId;

  @BeforeEach
  void openSaleWithItem() throws SQLException {
    username = "vendas.conclusao." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(username, List.of("OPERADOR"));
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, token));
    saleId = createSale(token);
    productId = seedProduct();
    seedStock();
    assertThat(addItem(token, "2").statusCode()).as("item da fixture").isEqualTo(200);
  }

  @Test
  @DisplayName(
      "POST complete: 200 com a venda concluída e a baixa de estoque, o movimento SALE e o evento")
  void completesSale() throws SQLException {
    payFully();

    Response response = complete(token, newKey());

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.getHeader("Location")).as("concluir não cria recurso novo").isNull();

    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("status")).isEqualTo("COMPLETED");
    assertThat(body.get("completedAt")).as("o instante da conclusão vem no detalhe").isNotNull();
    assertThat(((Number) body.get("number")).longValue())
        .as("o número alocado na abertura volta no corpo")
        .isEqualTo(saleRow(saleId).number());
    assertThat(decimal(body, "subtotal")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "total")).isEqualByComparingTo("19.80");
    assertThat(decimal(body, "paidAmount"))
        .as("BR-05: o pago cobre o total")
        .isEqualByComparingTo("19.80");
    assertThat(decimal(body, "changeAmount"))
        .as("troco do dinheiro calculado pelo servidor: 20,00 − 10,00 (BR-05)")
        .isEqualByComparingTo("10.00");
    assertThat(response.jsonPath().getList("items")).hasSize(1);

    List<Map<String, Object>> payments = response.jsonPath().getList("payments");

    assertThat(payments).hasSize(2);
    assertThat(payments.getFirst().get("method")).isEqualTo("CASH");
    assertThat(payments.getFirst().get("status")).isEqualTo("APPROVED");
    assertThat(payments.get(1).get("method")).isEqualTo("CREDIT");
    assertThat(payments.get(1).get("status")).isEqualTo("APPROVED");

    SaleRow stored = saleRow(saleId);

    assertThat(stored.status()).isEqualTo("COMPLETED");
    assertThat(stored.completedAt()).isNotNull();
    assertThat(stored.paidAmount()).isEqualTo("19.80");
    assertThat(stored.changeAmount()).isEqualTo("10.00");

    List<MovementRow> ledger = stockMovements(productId);

    assertThat(ledger).extracting(MovementRow::type).containsExactly("INITIAL", "SALE_OUT");

    MovementRow out = ledger.get(1);

    assertThat(out.delta()).isEqualByComparingTo("-2.000");
    assertThat(out.balanceAfter()).as("10 − 2 do item vendido").isEqualByComparingTo("8.000");
    assertThat(out.referenceType()).isEqualTo("SALE");
    assertThat(out.referenceId()).isEqualTo(saleId);
    assertThat(out.createdByUserId()).isEqualTo(userId(username));
    assertThat(quantity(productId))
        .as("o saldo materializado segue o ledger")
        .isEqualByComparingTo("8.000");

    List<CashMovementRow> movements = cashMovements();

    assertThat(movements)
        .extracting(CashMovementRow::type)
        .as("a venda entra no caixa só pela parcela em dinheiro")
        .containsExactly("OPENING", "SALE");

    CashMovementRow saleMovement = movements.get(1);

    assertThat(saleMovement.amount())
        .as("nunca o valor entregue (20,00)")
        .isEqualByComparingTo("10.00");
    assertThat(saleMovement.paymentMethod()).isEqualTo("CASH");
    assertThat(saleMovement.referenceType()).isEqualTo("SALE");
    assertThat(saleMovement.referenceId()).isEqualTo(saleId);
    assertThat(saleMovement.createdByUserId()).isEqualTo(userId(username));
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "mesma Idempotency-Key: replay do mesmo corpo, sem segunda baixa de estoque nem movimento")
  void replaysSameKeyWithoutSecondStockMovement() throws SQLException {
    payFully();
    String key = newKey();

    Response first = complete(token, key);

    assertThat(first.statusCode()).as("conclusão do cenário: %s", first.asString()).isEqualTo(200);

    BigDecimal balanceAfterFirst = quantity(productId);
    List<MovementRow> ledgerAfterFirst = stockMovements(productId);
    List<CashMovementRow> cashAfterFirst = cashMovements();

    Response replay = complete(token, key);

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 200").isEqualTo(200);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem concluir de novo")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));

    assertThat(quantity(productId))
        .as("o saldo depois do replay é o da primeira conclusão")
        .isEqualByComparingTo(balanceAfterFirst);
    assertThat(stockMovements(productId))
        .as("a baixa de estoque não se repete")
        .hasSize(ledgerAfterFirst.size());
    assertThat(cashMovements()).as("o movimento SALE não se repete").hasSize(cashAfterFirst.size());
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).as("um evento, não dois").isEqualTo(1);
    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
  }

  @Test
  @DisplayName("chave nova em venda concluída: 200 no-op, sem segundo movimento nem evento")
  void treatsNewKeyOnCompletedSaleAsNoOp() throws SQLException {
    payFully();
    Response first = complete(token, newKey());

    assertThat(first.statusCode()).as("conclusão do cenário: %s", first.asString()).isEqualTo(200);

    SaleRow afterFirst = saleRow(saleId);
    List<MovementRow> ledgerAfterFirst = stockMovements(productId);
    List<CashMovementRow> cashAfterFirst = cashMovements();

    Response again = complete(token, newKey());

    assertThat(again.statusCode()).as("o no-op também é 200: %s", again.asString()).isEqualTo(200);
    assertThat(again.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("não é replay do registro: é o estado da venda que não transita de novo")
        .isNull();
    assertThat(again.jsonPath().getString("status")).isEqualTo("COMPLETED");
    assertThat(Instant.parse(again.jsonPath().getString("completedAt")))
        .as("o instante é o da primeira conclusão, gravado no banco")
        .isEqualTo(afterFirst.completedAt());
    assertThat(again.jsonPath().getList("payments")).hasSize(2);
    assertThat(saleRow(saleId).completedAt()).isEqualTo(afterFirst.completedAt());
    assertThat(stockMovements(productId))
        .as("sem segunda baixa de estoque")
        .hasSize(ledgerAfterFirst.size());
    assertThat(cashMovements()).as("sem segundo movimento de caixa").hasSize(cashAfterFirst.size());
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).as("um evento só").isEqualTo(1);
  }

  @Test
  @DisplayName("pagamento insuficiente: 422 PAYMENT_INSUFFICIENT e nada muda")
  void refusesUnpaidSale() throws SQLException {
    assertThat(pay(token, newKey(), "{\"method\": \"PIX\", \"amount\": 10.00}").statusCode())
        .as("pagamento parcial do cenário")
        .isEqualTo(201);

    Response response = complete(token, newKey());

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(422);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PAYMENT_INSUFFICIENT");

    SaleRow stored = saleRow(saleId);

    assertThat(stored.status())
        .as("BR-05: sem pagamento que cubra, a venda segue aberta")
        .isEqualTo("OPEN");
    assertThat(stored.completedAt()).isNull();
    assertThat(quantity(productId))
        .as("o estoque não foi tocado")
        .isEqualByComparingTo(INITIAL_STOCK);
    assertThat(stockMovements(productId)).extracting(MovementRow::type).containsExactly("INITIAL");
    assertThat(cashMovements()).extracting(CashMovementRow::type).containsExactly("OPENING");
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).isZero();
  }

  @Test
  @DisplayName("venda de outro caixa: 403 ACCESS_DENIED sem concluir")
  void deniesSaleOfAnotherRegister() throws SQLException {
    payFully();
    UUID otherRegister = insertCashRegister();
    String otherUsername = "conclusao.outro." + SUFFIX;
    createUser(otherUsername, List.of("OPERADOR"));
    String otherToken = login(otherUsername, otherRegister);

    Response response = complete(otherToken, newKey());

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail"))
        .as("a recusa é da guarda de posse (BR-11), não do porteiro da rota")
        .contains(saleId.toString())
        .doesNotContain("sale.complete");

    assertThat(saleRow(saleId).status()).isEqualTo("OPEN");
    assertThat(stockMovements(productId)).extracting(MovementRow::type).containsExactly("INITIAL");
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).isZero();
  }

  @Test
  @DisplayName("venda inexistente: 404 SALE_NOT_FOUND")
  void rejectsUnknownSale() {
    Response response = complete(token, newKey(), UUID.randomUUID());

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_FOUND");
  }

  @Test
  @DisplayName("venda cancelada: 409 SALE_NOT_OPEN sem concluir")
  void rejectsCancelledSale() throws SQLException {
    payFully();
    cancelSaleBySql();

    Response response = complete(token, newKey());

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");

    SaleRow stored = saleRow(saleId);

    assertThat(stored.status()).isEqualTo("CANCELLED");
    assertThat(stored.completedAt()).isNull();
    assertThat(stockMovements(productId)).extracting(MovementRow::type).containsExactly("INITIAL");
    assertThat(cashMovements()).extracting(CashMovementRow::type).containsExactly("OPENING");
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).isZero();
  }

  @Test
  @DisplayName("sem Idempotency-Key: 400 IDEMPOTENCY_KEY_REQUIRED sem concluir")
  void requiresIdempotencyKey() throws SQLException {
    payFully();

    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .post(SALES_PATH + "/" + saleId + "/complete")
            .then()
            .extract()
            .response();

    assertThat(response.statusCode())
        .as("a operação é idempotente por contrato (§8): %s", response.asString())
        .isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

    assertThat(saleRow(saleId).status()).as("sem a chave nada acontece").isEqualTo("OPEN");
    assertThat(stockMovements(productId)).extracting(MovementRow::type).containsExactly("INITIAL");
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).isZero();
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}:
   * pagamentos (antes da venda), itens, venda, eventos, movimentos e saldos de estoque (antes do
   * produto), movimentos de caixa, sessão, série, chaves de idempotência (que referenciam o
   * usuário), sessões de auth, papéis, usuários, produtos e caixas extras, nessa ordem.
   */
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

  /** Paga os 19,80 do cenário: 10,00 em dinheiro (entregue 20,00) e 9,80 no crédito. */
  private void payFully() {
    assertThat(
            pay(
                    token,
                    newKey(),
                    "{\"method\": \"CASH\", \"amount\": 10.00, \"tenderedAmount\": 20.00}")
                .statusCode())
        .as("pagamento em dinheiro do cenário")
        .isEqualTo(201);
    assertThat(pay(token, newKey(), "{\"method\": \"CREDIT\", \"amount\": 9.80}").statusCode())
        .as("pagamento no crédito do cenário")
        .isEqualTo(201);
  }

  /** POST do pagamento na venda do cenário; o status fica com cada teste. */
  private Response pay(String token, String key, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, key)
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + saleId + "/payments")
        .then()
        .extract()
        .response();
  }

  /** POST da conclusão na venda do cenário; o status fica com cada teste. */
  private Response complete(String token, String key) {
    return complete(token, key, saleId);
  }

  /**
   * POST da conclusão na venda informada, <em>sem</em> corpo e sem {@code Content-Type} — o gesto
   * de concluir não tem contrato de entrada (caixa e operador saem da sessão). O status fica com
   * cada teste.
   */
  private Response complete(String token, String key, UUID id) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, key)
        .when()
        .post(SALES_PATH + "/" + id + "/complete")
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
      statement.setString(1, "CONCLUSAO." + SUFFIX);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        UUID id = resultSet.getObject("id", UUID.class);
        cashRegisterIds.add(id);
        return id;
      }
    }
  }

  /**
   * Cancela a venda direto no banco: o cenário é a recusa por estado (409 da conclusão), não o
   * cancelamento pela API — o OPERADOR da fixture nem tem {@code sale.cancel} (passo 813). O autor
   * é obrigatório porque é por ele que o agregado é rehidratado ({@code Sale.cancel}).
   */
  private void cancelSaleBySql() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "update sales set status = 'CANCELLED', cancelled_at = now(), cancelled_by_user_id = ?,"
              + " cancel_reason = ? where id = ?",
          userId(username),
          "cliente desistiu",
          saleId);
    }
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.conclusao." + SUFFIX + "." + UUID.randomUUID();
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

  /**
   * Status, número, pago, troco e instante da conclusão de {@code sales}, como o banco os guardou.
   */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select number, status, paid_amount::text as paid_amount, change_amount::text as"
                    + " change_amount, completed_at from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getLong("number"),
            resultSet.getString("status"),
            resultSet.getString("paid_amount"),
            resultSet.getString("change_amount"),
            instant(resultSet.getTimestamp("completed_at")));
      }
    }
  }

  /** Ledger do produto em ordem de aplicação ({@code created_at}, id do UUIDv7). */
  private List<MovementRow> stockMovements(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, quantity_delta::text as delta, balance_after::text as balance_after,"
                    + " reference_type, reference_id, created_by_user_id from stock_movements"
                    + " where product_id = ? order by created_at, id")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<MovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new MovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("delta")),
                  new BigDecimal(resultSet.getString("balance_after")),
                  resultSet.getString("reference_type"),
                  resultSet.getObject("reference_id", UUID.class),
                  resultSet.getObject("created_by_user_id", UUID.class)));
        }
        return rows;
      }
    }
  }

  /** Saldo materializado do produto; nulo quando o produto não tem linha de saldo. */
  private BigDecimal quantity(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? new BigDecimal(resultSet.getString(1)) : null;
      }
    }
  }

  /** Movimentos do ledger do caixa da sessão em ordem cronológica. */
  private List<CashMovementRow> cashMovements() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, amount::text as amount, payment_method, reference_type, reference_id,"
                    + " created_by_user_id from cash_movements where cash_session_id = ?"
                    + " order by created_at, id")) {
      statement.setObject(1, cashSessionIds.getFirst());
      try (ResultSet resultSet = statement.executeQuery()) {
        List<CashMovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new CashMovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("amount")),
                  resultSet.getString("payment_method"),
                  resultSet.getString("reference_type"),
                  resultSet.getObject("reference_id", UUID.class),
                  resultSet.getObject("created_by_user_id", UUID.class)));
        }
        return rows;
      }
    }
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

  /** Instante do banco como o {@code Clock} o gravou; nulo quando a coluna é nula. */
  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
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

  /** Linha de {@code sales} com os campos que a suíte confere. */
  private record SaleRow(
      long number, String status, String paidAmount, String changeAmount, Instant completedAt) {}

  /** Linha de {@code stock_movements} como o banco a guardou. */
  private record MovementRow(
      String type,
      BigDecimal delta,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId,
      UUID createdByUserId) {}

  /** Linha de {@code cash_movements} como o banco a guardou. */
  private record CashMovementRow(
      String type,
      BigDecimal amount,
      String paymentMethod,
      String referenceType,
      UUID referenceId,
      UUID createdByUserId) {}
}
