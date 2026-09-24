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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concorrência da conclusão da venda na API (passo 908) contra PostgreSQL real (Dev Services): as
 * garantias do §8 sob disputa de verdade — duas vendas concluindo ao mesmo tempo sobre a última
 * unidade do produto, com a loja proibindo e permitindo saldo negativo, dois pagamentos simultâneos
 * na mesma venda e dois {@code complete} simultâneos da mesma venda.
 *
 * <p>Cada thread faz a própria requisição HTTP — transação e contexto próprios, como dois caixas de
 * verdade — disparada por um {@link CountDownLatch} comum: sem {@code sleep} e sem
 * {@code @Disabled}, com timeout de worker para o teste falhar, nunca travar. O que o teste prova
 * são as invariantes no banco e a <em>contagem de efeitos</em> — saldo materializado, encadeamento
 * de {@code balance_after} do ledger, uma linha por efeito, rollback total da perdedora —, nunca a
 * ordem em que as threads chegaram: ordem é do escalonador, efeito é do lock.
 *
 * <p>As fixtures saem dos caminhos reais: o OPERADOR nasce pelo caso de uso (o papel tem {@code
 * sale.create}, {@code payment.add} e {@code sale.complete} pelo seed da {@code V3__rbac.sql}), o
 * login vincula a sessão ao {@code CAIXA-01} do seed, o caixa e as vendas abrem pela própria API
 * (passos 607/807/809) e o produto é semeado pela porta do catálogo com estoque inicial pelo {@code
 * StockService} (passo 703), para o {@code balance_after} do ledger ser determinístico. As duas
 * vendas da última unidade são do mesmo operador e da mesma sessão de caixa (decisão do passo): a
 * disputa é pela linha de saldo do mesmo produto.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco e limpa tudo o que
 * comitou no {@code @AfterEach}, na ordem que as FKs {@code restrict} exigem: chaves de
 * idempotência, pagamentos, itens, vendas, eventos, movimentos e saldos de estoque, produtos,
 * movimentos de caixa, sessão, série e usuários — o banco é compartilhado e o teste que desliga
 * {@code allow_negative_stock} restaura a flag no {@code @AfterEach}, como o {@code
 * StockServiceIntegrationTest}.
 */
@QuarkusTest
class CompleteSaleConcurrencyResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture das vendas do teste. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PRODUCT_NAME = "Última unidade";

  /** Preço unitário do produto: a venda de 1 unidade paga exatamente 9,90. */
  private static final String PRICE = "9.90";

  /** Espera máxima de cada worker: o teste falha, nunca trava, se a corrida não terminar. */
  private static final long WORKER_TIMEOUT_SECONDS = 30;

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Porta única de alteração de saldo (passo 703): o estoque inicial da fixture nasce por aqui. */
  @Inject StockService stockService;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

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

  /** Operador do cenário: o autor dos movimentos de estoque e dos pagamentos. */
  private UUID operatorId;

  private String username;

  private String token;

  private UUID registerId;

  private UUID cashSessionId;

  private UUID productId;

  private boolean storeFlagFlipped;

  @BeforeEach
  void openCashSessionAndProduct() throws SQLException {
    username =
        "vendas.conclusao.concorrencia."
            + SUFFIX
            + "."
            + UUID.randomUUID().toString().substring(0, 6);
    operatorId =
        createUserUseCase
            .execute(
                new CreateUserCommand(
                    username, "Operador da concorrência", PASSWORD, List.of("OPERADOR")))
            .id();
    userIds.add(operatorId);
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionId = open(registerId, token);
    cashSessionIds.add(cashSessionId);
    productId = seedProduct();
  }

  @Test
  @DisplayName(
      "última unidade, loja sem saldo negativo: um 200 e um 422 INSUFFICIENT_STOCK, saldo 0 e nenhum"
          + " rastro da venda perdedora")
  void refusesSecondSaleOfTheLastUnit() throws Exception {
    setAllowNegativeStock(false);
    seedStock("1.000");
    UUID firstSale = openSaleWithItem();
    UUID secondSale = openSaleWithItem();
    payInFull(firstSale);
    payInFull(secondSale);
    String firstKey = newKey();
    String secondKey = newKey();

    List<Response> responses =
        race(List.of(() -> complete(firstSale, firstKey), () -> complete(secondSale, secondKey)));

    assertThat(statuses(responses))
        .as("o lock da linha de saldo deixa uma venda vencer e a outra receber o 422")
        .containsExactlyInAnyOrder(200, 422);

    Response refused = withStatus(responses, 422);

    assertThat(refused.contentType()).contains("application/problem+json");
    assertThat(refused.jsonPath().getString("code")).isEqualTo("INSUFFICIENT_STOCK");

    UUID winnerSale = saleIdOf(withStatus(responses, 200));
    UUID loserSale = winnerSale.equals(firstSale) ? secondSale : firstSale;

    assertThat(saleRow(winnerSale).status()).isEqualTo("COMPLETED");
    assertThat(saleRow(winnerSale).completedAt()).isNotNull();

    SaleRow loser = saleRow(loserSale);

    assertThat(loser.status()).as("a perdedora segue aberta").isEqualTo("OPEN");
    assertThat(loser.completedAt()).isNull();

    List<MovementRow> ledger = stockLedger();

    assertThat(ledger)
        .extracting(MovementRow::type)
        .as("uma baixa para a vencedora e nenhuma para a perdedora")
        .containsExactlyInAnyOrder("INITIAL", "SALE_OUT");

    MovementRow sold = ledgerRow(ledger, "SALE_OUT");

    assertThat(sold.delta()).isEqualByComparingTo("-1.000");
    assertThat(sold.balanceAfter()).as("1 − 1 da última unidade").isEqualByComparingTo("0");
    assertThat(sold.referenceId())
        .as("o SALE_OUT é da venda vencedora, nunca da que voltou atrás")
        .isEqualTo(winnerSale);
    assertThat(quantity()).as("o saldo materializado segue o ledger").isEqualByComparingTo("0");

    List<CashMovementRow> movements = cashMovements();

    assertThat(movements)
        .extracting(CashMovementRow::type)
        .as("o rollback da perdedora não deixa movimento de caixa")
        .containsExactly("OPENING", "SALE");
    assertThat(movements.getLast().referenceId()).isEqualTo(winnerSale);

    assertThat(auditEventCount(winnerSale, "SALE_COMPLETED"))
        .as("uma conclusão, um evento")
        .isEqualTo(1);
    assertThat(auditEventCount(loserSale, "SALE_COMPLETED"))
        .as("a perdedora não audita conclusão")
        .isZero();
    assertThat(countIdempotencyKeys(firstKey, secondKey))
        .as("a resposta 422 não é gravada na chave: só o vencedor tem registro")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "última unidade, loja com saldo negativo: os dois 200, saldo −1 e os dois SALE_OUT no ledger")
  void completesBothSalesLeavingTheNegativeBalanceAudited() throws Exception {
    seedStock("1.000");
    UUID firstSale = openSaleWithItem();
    UUID secondSale = openSaleWithItem();
    payInFull(firstSale);
    payInFull(secondSale);
    String firstKey = newKey();
    String secondKey = newKey();

    List<Response> responses =
        race(List.of(() -> complete(firstSale, firstKey), () -> complete(secondSale, secondKey)));

    assertThat(statuses(responses))
        .as("com a loja permitindo saldo negativo as duas vendas concluem")
        .containsExactly(200, 200);
    assertThat(saleRow(firstSale).status()).isEqualTo("COMPLETED");
    assertThat(saleRow(secondSale).status()).isEqualTo("COMPLETED");

    List<MovementRow> ledger = stockLedger();
    List<MovementRow> outs = ledger.stream().filter(row -> row.type().equals("SALE_OUT")).toList();

    assertThat(ledger)
        .extracting(MovementRow::type)
        .containsExactlyInAnyOrder("INITIAL", "SALE_OUT", "SALE_OUT");
    assertThat(outs).hasSize(2);
    assertThat(outs).extracting(row -> row.delta().intValueExact()).containsOnly(-1);
    assertThat(outs)
        .extracting(row -> row.balanceAfter().intValueExact())
        .as("0 na primeira baixa e −1 na segunda: a ordem é do escalonador, o saldo é do lock")
        .containsExactlyInAnyOrder(0, -1);
    assertThat(outs)
        .extracting(MovementRow::referenceId)
        .as("uma baixa por venda concluída")
        .containsExactlyInAnyOrder(firstSale, secondSale);
    assertThat(quantity())
        .as("1 no estoque − 2 vendidas: o saldo negativo fica registrado")
        .isEqualByComparingTo("-1");
    assertThat(ledgerSum(ledger))
        .as("a soma dos deltas do ledger é o saldo materializado")
        .isEqualByComparingTo("-1");

    List<CashMovementRow> movements = cashMovements();

    assertThat(movements)
        .extracting(CashMovementRow::type)
        .as("as duas vendas em dinheiro entraram no caixa")
        .containsExactly("OPENING", "SALE", "SALE");
    assertThat(movements.subList(1, movements.size()))
        .extracting(CashMovementRow::referenceId)
        .containsExactlyInAnyOrder(firstSale, secondSale);

    assertThat(auditEventCount(firstSale, "SALE_COMPLETED")).isEqualTo(1);
    assertThat(auditEventCount(secondSale, "SALE_COMPLETED")).isEqualTo(1);
    assertThat(countIdempotencyKeys(firstKey, secondKey))
        .as("as duas operações efetivadas gravam a chave")
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "dois pagamentos simultâneos na mesma venda: um 201, um 409/422 e nenhum pagamento órfão")
  void addsOnlyOneOfTwoSimultaneousPayments() throws Exception {
    UUID saleId = openSaleWithItem();
    long versionBefore = saleRow(saleId).version();
    String firstKey = newKey();
    String secondKey = newKey();
    String body = "{\"method\": \"PIX\", \"amount\": %s}".formatted(PRICE);

    List<Response> responses =
        race(List.of(() -> pay(saleId, firstKey, body), () -> pay(saleId, secondKey, body)));

    assertThat(withStatus(responses, 201).statusCode())
        .as("exatamente um pagamento aceito")
        .isEqualTo(201);

    Response refused =
        responses.stream()
            .filter(response -> response.statusCode() != 201)
            .findFirst()
            .orElseThrow();

    assertThat(refused.statusCode())
        .as(
            "quem perdeu o flush otimista recebe 409; quem leu depois do commit vê o restante zerado e"
                + " recebe 422")
        .isIn(409, 422);
    assertThat(refused.contentType()).contains("application/problem+json");
    assertThat(refused.jsonPath().getString("code"))
        .isIn("CONCURRENT_MODIFICATION", "PAYMENT_EXCEEDS_TOTAL");

    List<PaymentRow> rows = paymentRows(saleId);

    assertThat(rows).as("o pagamento do perdedor não fica órfão: uma linha só").hasSize(1);
    assertThat(rows.getFirst().status()).isEqualTo("APPROVED");
    assertThat(rows.getFirst().amount()).isEqualByComparingTo(PRICE);

    SaleRow stored = saleRow(saleId);

    assertThat(stored.paidAmount()).isEqualByComparingTo(PRICE);
    assertThat(stored.version())
        .as("a venda foi gravada exatamente uma vez: o perdedor voltou atrás no rollback")
        .isEqualTo(versionBefore + 1);
    assertThat(auditEventCount(saleId, "PAYMENT_ADDED"))
        .as("um pagamento efetivado, um evento")
        .isEqualTo(1);
    assertThat(countIdempotencyKeys(firstKey, secondKey)).as("só o 201 grava a chave").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "dois completes simultâneos da mesma venda com chaves novas: um conclui, o outro é 200 no-op,"
          + " com uma baixa e um evento")
  void completesOnceWhenTwoCompletesRace() throws Exception {
    seedStock("5.000");
    UUID saleId = openSaleWithItem();
    payInFull(saleId);
    long versionBefore = saleRow(saleId).version();
    String firstKey = newKey();
    String secondKey = newKey();

    List<Response> responses =
        race(List.of(() -> complete(saleId, firstKey), () -> complete(saleId, secondKey)));

    assertThat(statuses(responses))
        .as("o lock da venda serializa: o segundo vê COMPLETED e é no-op de estado")
        .containsExactly(200, 200);
    assertThat(responses)
        .extracting(response -> response.jsonPath().getString("status"))
        .containsOnly("COMPLETED");
    assertThat(Instant.parse(responses.get(0).jsonPath().getString("completedAt")))
        .as(
            "as duas respostas descrevem a mesma conclusão — o no-op relê do banco e o relógio da"
                + " aplicação trunca no microssegundo gravado, então o instante bate exato")
        .isEqualTo(Instant.parse(responses.get(1).jsonPath().getString("completedAt")));

    assertThat(stockLedger())
        .extracting(MovementRow::type)
        .as("a baixa de estoque não se repete")
        .containsExactly("INITIAL", "SALE_OUT");
    assertThat(cashMovements())
        .extracting(CashMovementRow::type)
        .as("o movimento SALE não se repete")
        .containsExactly("OPENING", "SALE");
    assertThat(auditEventCount(saleId, "SALE_COMPLETED")).as("um evento, não dois").isEqualTo(1);
    assertThat(saleRow(saleId).version())
        .as("o no-op não regrava a venda")
        .isEqualTo(versionBefore + 1);
  }

  /**
   * Dispara as ações no mesmo instante — o latch é a largada comum — e devolve as respostas, na
   * ordem em que foram submetidas. Cada thread faz a própria requisição HTTP, com transação e
   * contexto próprios, e o timeout garante que a corrida falhe em vez de travar.
   */
  private static List<Response> race(List<Callable<Response>> actions) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(actions.size());
    try {
      List<Future<Response>> futures =
          actions.stream().map(action -> workers.submit(awaiting(start, action))).toList();
      start.countDown();
      List<Response> responses = new ArrayList<>();
      for (Future<Response> future : futures) {
        responses.add(future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }
  }

  /** Envolve a ação para ela só começar quando o latch da largada abrir. */
  private static Callable<Response> awaiting(CountDownLatch start, Callable<Response> action) {
    return () -> {
      start.await();
      return action.call();
    };
  }

  /** Status das respostas, para a contagem de efeitos: um vencedor e um perdedor. */
  private static List<Integer> statuses(List<Response> responses) {
    return responses.stream().map(Response::statusCode).toList();
  }

  /** A resposta com o status esperado; o teste falha se não houver exatamente uma. */
  private static Response withStatus(List<Response> responses, int status) {
    return responses.stream()
        .filter(response -> response.statusCode() == status)
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "nenhuma resposta com status %d: %s".formatted(status, statuses(responses))));
  }

  /** Id da venda do corpo da resposta de sucesso. */
  private static UUID saleIdOf(Response response) {
    return UUID.fromString(response.jsonPath().getString("id"));
  }

  /** Abre a venda pela API (passo 807) e inclui 1 unidade do produto (passo 809b). */
  private UUID openSaleWithItem() {
    Response opened =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .when()
            .post(SALES_PATH)
            .then()
            .statusCode(201)
            .extract()
            .response();
    UUID saleId = UUID.fromString(opened.jsonPath().getString("id"));
    saleIds.add(saleId);
    Response item =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .contentType("application/json")
            .body("{\"productId\": \"%s\", \"quantity\": 1}".formatted(productId))
            .when()
            .post(SALES_PATH + "/" + saleId + "/items")
            .then()
            .extract()
            .response();
    assertThat(item.statusCode()).as("item da fixture: %s", item.asString()).isEqualTo(200);
    return saleId;
  }

  /** Paga a venda da fixture em dinheiro pelo valor exato: 1 unidade a 9,90, sem troco. */
  private void payInFull(UUID saleId) {
    Response paid =
        pay(
            saleId,
            newKey(),
            "{\"method\": \"CASH\", \"amount\": %s, \"tenderedAmount\": %s}"
                .formatted(PRICE, PRICE));
    assertThat(paid.statusCode()).as("pagamento da fixture: %s", paid.asString()).isEqualTo(201);
  }

  /** POST do pagamento na venda informada; o status fica com cada thread. */
  private Response pay(UUID saleId, String key, String body) {
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

  /** POST da conclusão na venda informada; sem corpo, como o gesto de concluir (passo 907). */
  private Response complete(UUID saleId, String key) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, key)
        .when()
        .post(SALES_PATH + "/" + saleId + "/complete")
        .then()
        .extract()
        .response();
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

  /** Produto do cenário pela porta do catálogo, em transação própria como o cadastro o grava. */
  private UUID seedProduct() throws SQLException {
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(),
                            PRODUCT_NAME,
                            null,
                            null,
                            null,
                            "UN",
                            new BigDecimal(PRICE),
                            null)));
    productIds.add(id);
    return id;
  }

  /** Estoque inicial do produto pelo caminho de verdade (passo 703), em transação própria. */
  private void seedStock(String initialStock) {
    stockService.applyMovement(
        new ApplyStockMovementCommand(
            productId,
            StockMovementType.INITIAL,
            new BigDecimal(initialStock),
            null,
            null,
            null,
            null,
            operatorId));
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

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.conclusao.concorrencia." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /**
   * Desliga/liga a flag da loja direto no banco (não há porta de escrita da loja no MVP) antes de
   * qualquer leitura da loja no teste, e marca a flag para o {@code @AfterEach} restaurar — a loja
   * é compartilhada com os outros testes do fork.
   */
  private void setAllowNegativeStock(boolean allowed) throws SQLException {
    storeFlagFlipped = true;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update stores set allow_negative_stock = ? where code = ?")) {
      statement.setBoolean(1, allowed);
      statement.setString(2, defaultStoreCode);
      statement.executeUpdate();
    }
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves,
   * pagamentos (antes da venda), itens, vendas, eventos, movimentos e saldos de estoque (antes do
   * produto), movimentos de caixa, sessão, série e usuários, nessa ordem.
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
      for (UUID id : productIds) {
        execute(connection, "delete from stock_movements where product_id = ?", id);
        execute(connection, "delete from product_stocks where product_id = ?", id);
        execute(connection, "delete from products where id = ?", id);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(
            connection,
            "delete from audit_events where entity_id = ? or cash_session_id = ?",
            sessionId,
            sessionId);
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
    if (storeFlagFlipped) {
      setAllowNegativeStock(true);
    }
  }

  /** Id da loja configurada, direto do banco: saldo, produto e venda vivem dentro de uma loja. */
  private UUID storeId() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from stores where code = ?")) {
      statement.setString(1, defaultStoreCode);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("loja %s do seed presente", defaultStoreCode).isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /** Saldo materializado do produto, como o banco o guardou. */
  private BigDecimal quantity() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("linha de saldo do produto presente").isTrue();
        return new BigDecimal(resultSet.getString(1));
      }
    }
  }

  /** Ledger do produto em ordem de aplicação ({@code created_at}, id do UUIDv7). */
  private List<MovementRow> stockLedger() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, quantity_delta::text as delta, balance_after::text as balance_after,"
                    + " reference_id from stock_movements where product_id = ?"
                    + " order by created_at, id")) {
      statement.setObject(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<MovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new MovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("delta")),
                  new BigDecimal(resultSet.getString("balance_after")),
                  resultSet.getObject("reference_id", UUID.class)));
        }
        return rows;
      }
    }
  }

  /** A única linha do ledger do tipo informado; falha se houver zero ou mais de uma. */
  private static MovementRow ledgerRow(List<MovementRow> ledger, String type) {
    List<MovementRow> rows = ledger.stream().filter(row -> row.type().equals(type)).toList();
    assertThat(rows).as("uma única linha %s no ledger", type).hasSize(1);
    return rows.getFirst();
  }

  /** Soma dos deltas do ledger: é o saldo que a soma dos movimentos explica. */
  private static BigDecimal ledgerSum(List<MovementRow> ledger) {
    return ledger.stream().map(MovementRow::delta).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Movimentos do ledger do caixa da sessão em ordem cronológica. */
  private List<CashMovementRow> cashMovements() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, amount::text as amount, reference_id from cash_movements"
                    + " where cash_session_id = ? order by created_at, id")) {
      statement.setObject(1, cashSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<CashMovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new CashMovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("amount")),
                  resultSet.getObject("reference_id", UUID.class)));
        }
        return rows;
      }
    }
  }

  /** Pagamentos da venda na ordem de criação, como o banco os guardou. */
  private List<PaymentRow> paymentRows(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, amount::text as amount from payments where sale_id = ?"
                    + " order by created_at, id")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<PaymentRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new PaymentRow(
                  resultSet.getString("status"), new BigDecimal(resultSet.getString("amount"))));
        }
        return rows;
      }
    }
  }

  /** Status, pago, instante da conclusão e versão de {@code sales}, como o banco os guardou. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, paid_amount::text as paid_amount, completed_at, version"
                    + " from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            new BigDecimal(resultSet.getString("paid_amount")),
            instant(resultSet.getTimestamp("completed_at")),
            resultSet.getLong("version"));
      }
    }
  }

  /** Eventos da ação para o alvo: um por efeito efetivado, zero por tentativa barrada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
  }

  /** Registros de idempotência das duas chaves da corrida: um por operação efetivada. */
  private int countIdempotencyKeys(String firstKey, String secondKey) throws SQLException {
    return queryInt(
        "select count(*) from idempotency_keys where key in (?, ?)", firstKey, secondKey);
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
  private record SaleRow(String status, BigDecimal paidAmount, Instant completedAt, long version) {}

  /** Linha de {@code stock_movements} como o banco a guardou. */
  private record MovementRow(
      String type, BigDecimal delta, BigDecimal balanceAfter, UUID referenceId) {}

  /** Linha de {@code cash_movements} como o banco a guardou. */
  private record CashMovementRow(String type, BigDecimal amount, UUID referenceId) {}

  /** Linha de {@code payments} como o banco a guardou. */
  private record PaymentRow(String status, BigDecimal amount) {}
}
