package com.minimarket.sales.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.api.AuthResource;
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
import io.restassured.path.json.JsonPath;
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
 * Documentação executável do fluxo do PDV (passo 910), fechamento da Fase 9 contra PostgreSQL real
 * (Dev Services): o teste percorre o MVP inteiro pela API, na ordem em que a TUI (Fase 11) e o Web
 * (Fase 12) vão operá-lo, e serve de referência para os dois clientes.
 *
 * <p>Dois atores reais, cada um com o seu token e o seu papel no §4.5: o OPERADOR (login vinculado
 * ao {@code CAIXA-01} do seed) abre o caixa, abre a venda, bipa os três itens por <em>barcode</em>,
 * registra os dois pagamentos e conclui — é ele quem opera o caixa; e o GERENTE (mesmo caixa, outra
 * sessão) é quem aplica o desconto, porque o OPERADOR não tem {@code sale.discount.apply}: a rota
 * responde 403 {@code ACCESS_DENIED} para ele antes de o corpo rodar, e o teste registra a recusa
 * antes de o gerente assinar a operação. Como a posse da venda (BR-11, §9.4) é do <em>caixa</em> e
 * não do usuário, os dois operam a mesma venda.
 *
 * <p>Os números do cenário são redondos de propósito (contas conferíveis de cabeça): 2 × 10,00 + 1
 * × 5,50 + 2 × 2,25 = 30,00 de subtotal; 10% de desconto = 3,00 de desconto e 27,00 de total; 10,00
 * em dinheiro (20,00 entregues → 10,00 de troco) + 17,00 no crédito fecham a conta. O caixa abre
 * com 100,00 e o esperado fecha em 110,00 — só a parcela em dinheiro da venda entra nele, o crédito
 * fica fora (passo 909). Cada produto nasce com 50,000 de estoque inicial pelo {@code StockService}
 * (passo 703), então o {@code balance_after} do ledger é verificável: 48,000, 49,000 e 48,000.
 *
 * <p>O request HTTP comita, então as conferências finais são por SQL, como no resto da suíte:
 * estado da venda (status, totais, troco, {@code completed_at}), ledger de estoque com {@code
 * SALE_OUT} por item, movimento {@code SALE} do caixa (a parcela em dinheiro, nunca o valor
 * entregue) e a trilha de auditoria completa — um evento por operação, na ordem do fluxo, com o
 * ator certo em cada um, conferida por {@code entity_id} e nunca por contagem global. A limpeza do
 * {@code @AfterEach} segue a ordem que as FKs {@code restrict} exigem: chaves, pagamentos, itens,
 * venda, eventos, movimentos e saldos de estoque, produtos, movimentos e sessão de caixa, série,
 * sessões de auth, papéis e os dois usuários — o banco é compartilhado com os demais testes do fork
 * e o {@code CAIXA-01} é fixture compartilhada.
 */
@QuarkusTest
class FullSaleFlowTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String CASH_SESSIONS_PATH = "/api/v1/cash-sessions";

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture do fluxo. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /**
   * Cliente da sessão: o fluxo documentado é o da TUI (Fase 11) — vira o {@code source} do evento.
   */
  private static final String CLIENT = "TUI";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Bloco aleatório comum aos três barcodes: sem colidir com o produto de outra execução. */
  private static final String BARCODE_BLOCK =
      String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));

  /** Abertura do caixa: o esperado do resumo fecha em 110,00 com a venda em dinheiro. */
  private static final String OPENING_AMOUNT = "100.00";

  /** Estoque inicial de cada produto: a venda baixa 2, 1 e 2 unidades. */
  private static final String INITIAL_STOCK = "50.000";

  /** Motivo do desconto: o rastro humano que vai no {@code reason} do evento (BR-04). */
  private static final String DISCOUNT_REASON = "cliente fidelidade";

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
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

  private String operatorUsername;

  private String operatorToken;

  private UUID operatorId;

  private String managerUsername;

  private String managerToken;

  private UUID managerId;

  private UUID registerId;

  /**
   * Prepara os dois atores e os três produtos: o OPERADOR (operação do caixa) e o GERENTE
   * (desconto) logam no mesmo {@code CAIXA-01} — é o caixa que é dono da venda (BR-11), não o
   * usuário —, com a origem {@code TUI} da sessão do PDV.
   */
  @BeforeEach
  void prepareActorsAndProducts() throws SQLException {
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    operatorUsername = "fluxo.completo.operador." + SUFFIX;
    operatorId = createUser(operatorUsername, List.of("OPERADOR"));
    managerUsername = "fluxo.completo.gerente." + SUFFIX;
    managerId = createUser(managerUsername, List.of("GERENTE"));
    operatorToken = login(operatorUsername, registerId);
    managerToken = login(managerUsername, registerId);
    // Produtos com barcode único e estoque inicial; a ordem é a do bipe no fluxo abaixo.
    products.add(seedProduct("Arroz 5kg", "10.00", "2", "48.000", 1));
    products.add(seedProduct("Feijão Carioca 1kg", "5.50", "1", "49.000", 2));
    products.add(seedProduct("Leite Integral 1L", "2.25", "2", "48.000", 3));
  }

  @Test
  @DisplayName(
      "fluxo do PDV ponta a ponta: o operador vende, o gerente desconta e a venda conclui com estoque, caixa e auditoria coerentes")
  void runsTheWholeSaleFlow() throws SQLException {
    // 1. Abrir o caixa: a sessão autenticada já está vinculada ao CAIXA-01 pelo login, e a operação
    //    idempotente (§8) leva Idempotency-Key nova.
    Response opened = open(operatorToken);
    assertThat(opened.statusCode()).as("resposta: %s", opened.asString()).isEqualTo(201);
    UUID cashSessionId = UUID.fromString(opened.jsonPath().getString("id"));
    cashSessionIds.add(cashSessionId);

    // 2. Abrir a venda: caixa, operador e loja saem da sessão autenticada (BR-11/BR-06) — o corpo
    // da
    //    requisição não escolhe nenhum deles — e o número sai do alocador do servidor.
    Response created = createSale(operatorToken);
    assertThat(created.statusCode()).as("resposta: %s", created.asString()).isEqualTo(201);
    UUID saleId = UUID.fromString(created.jsonPath().getString("id"));
    saleIds.add(saleId);

    // 3. Três itens por barcode: o leitor manda a string bruta e quem resolve o produto é o
    //    servidor (BR-14) — o cliente nunca interpreta código.
    Response withItems = null;
    for (ProductFixture product : products) {
      String body =
          "{\"barcode\": \"%s\", \"quantity\": %s}"
              .formatted(product.barcode(), product.quantity());
      Response item = addItem(operatorToken, saleId, body);
      assertThat(item.statusCode())
          .as("item %s: %s", product.name(), item.asString())
          .isEqualTo(200);
      withItems = item;
    }
    assertThat(withItems).as("venda com os três itens bipados").isNotNull();
    assertThat(withItems.jsonPath().getList("items")).hasSize(3);
    assertThat(decimal(withItems.jsonPath().getMap("$"), "subtotal"))
        .as("10,00 × 2 + 5,50 + 2,25 × 2, recalculado pelo servidor")
        .isEqualByComparingTo("30.00");

    // 4. Desconto: o OPERADOR não tem sale.discount.apply (§4.5) e a rota recusa com 403 antes de o
    //    corpo rodar; quem aplica é o GERENTE — o desconto é o gesto de gestão do fluxo.
    Response refused = putDiscount(operatorToken, saleId);
    assertThat(refused.statusCode()).as("resposta: %s", refused.asString()).isEqualTo(403);
    assertThat(refused.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(refused.jsonPath().getString("detail"))
        .as("a recusa do porteiro da rota cita a permissão que falta ao operador")
        .contains("sale.discount.apply");
    assertThat(saleRow(saleId).discountAmount())
        .as("a recusa não toca na venda: o desconto ainda não foi aplicado")
        .isEqualTo("0.00");

    Response discounted = putDiscount(managerToken, saleId);
    assertThat(discounted.statusCode()).as("resposta: %s", discounted.asString()).isEqualTo(200);
    Map<String, Object> discountedBody = discounted.jsonPath().getMap("$");
    assertThat(decimal(discountedBody, "subtotal")).isEqualByComparingTo("30.00");
    assertThat(discountedBody.get("discountReason")).isEqualTo(DISCOUNT_REASON);
    assertThat(decimal(discountedBody, "discountAmount"))
        .as("10% de 30,00, calculado pelo servidor (BR-03)")
        .isEqualByComparingTo("3.00");
    assertThat(decimal(discountedBody, "total")).isEqualByComparingTo("27.00");

    // 5. Pagamentos: 10,00 em dinheiro com 20,00 entregues (troco de 10,00 pelo servidor, BR-05) e
    //    os 17,00 restantes no crédito — a soma cobre exatamente o total.
    Response cash = pay(operatorToken, saleId, "CASH", "10.00", "20.00");
    assertThat(cash.statusCode()).as("resposta: %s", cash.asString()).isEqualTo(201);
    assertThat(decimal(cash.jsonPath().getMap("$"), "paidAmount"))
        .as("parcial até que o segundo pagamento entre")
        .isEqualByComparingTo("10.00");
    assertThat(decimal(cash.jsonPath().getMap("$"), "changeAmount"))
        .as("20,00 − 10,00, calculado pelo servidor")
        .isEqualByComparingTo("10.00");

    Response credit = pay(operatorToken, saleId, "CREDIT", "17.00", null);
    assertThat(credit.statusCode()).as("resposta: %s", credit.asString()).isEqualTo(201);
    assertThat(decimal(credit.jsonPath().getMap("$"), "paidAmount"))
        .as("BR-05: os dois pagamentos cobrem o total de 27,00")
        .isEqualByComparingTo("27.00");

    // 6. Concluir: transição de estado (200, sem corpo de requisição) que baixa estoque, entra no
    //    caixa e fecha a trilha de auditoria na mesma transação (passo 906).
    Response completed = complete(operatorToken, saleId);
    assertThat(completed.statusCode()).as("resposta: %s", completed.asString()).isEqualTo(200);
    assertThat(completed.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("primeira chamada da chave, não replay")
        .isNull();
    Map<String, Object> completedBody = completed.jsonPath().getMap("$");
    assertThat(completedBody.get("status")).isEqualTo("COMPLETED");
    assertThat(completedBody.get("completedAt"))
        .as("o instante da conclusão vem no detalhe")
        .isNotNull();
    assertThat(decimal(completedBody, "paidAmount")).isEqualByComparingTo("27.00");
    assertThat(decimal(completedBody, "changeAmount")).isEqualByComparingTo("10.00");

    // 7. As conferências finais, do jeito que o banco guardou (o request comita).
    SaleRow sale = saleRow(saleId);
    assertThat(sale.status()).isEqualTo("COMPLETED");
    assertThat(sale.completedAt()).as("venda concluída tem instante de conclusão").isNotNull();
    assertThat(sale.subtotal()).as("o numeric do banco com a escala da coluna").isEqualTo("30.00");
    assertThat(sale.discountAmount()).isEqualTo("3.00");
    assertThat(sale.total()).isEqualTo("27.00");
    assertThat(sale.paidAmount()).isEqualTo("27.00");
    assertThat(sale.changeAmount()).isEqualTo("10.00");
    assertThat(sale.itemCount()).as("item_count é o número de linhas da venda").isEqualTo(3);

    // Estoque: um SALE_OUT por item no ledger, com o saldo corrente coerente, e o saldo
    // materializado que o StockService manteve — ninguém fora dele altera saldo (BR-07).
    for (ProductFixture product : products) {
      List<MovementRow> ledger = stockMovements(product.id());
      assertThat(ledger).extracting(MovementRow::type).containsExactly("INITIAL", "SALE_OUT");
      MovementRow out = ledger.get(1);
      assertThat(out.delta())
          .as("saída das %s unidades de %s", product.quantity(), product.name())
          .isEqualByComparingTo("-" + product.quantity() + ".000");
      assertThat(out.balanceAfter())
          .as("50,000 iniciais menos o que a venda levou")
          .isEqualByComparingTo(product.balanceAfter());
      assertThat(out.referenceType()).isEqualTo("SALE");
      assertThat(out.referenceId()).isEqualTo(saleId);
      assertThat(out.createdByUserId())
          .as("quem operou a venda movimenta o estoque")
          .isEqualTo(operatorId);
      assertThat(stockQuantity(product.id())).isEqualByComparingTo(product.balanceAfter());
    }

    // Caixa: um movimento SALE com a parcela em dinheiro — nunca o valor entregue (o troco não é
    // receita) — e nenhum do cartão: só o dinheiro entra na gaveta (passo 909).
    List<CashMovementRow> movements = cashMovements(cashSessionId);
    assertThat(movements).extracting(CashMovementRow::type).containsExactly("OPENING", "SALE");
    CashMovementRow saleMovement = movements.get(1);
    assertThat(saleMovement.amount())
        .as("a parcela em dinheiro, não os 20,00 entregues nem os 27,00 da venda")
        .isEqualByComparingTo("10.00");
    assertThat(saleMovement.paymentMethod()).isEqualTo("CASH");
    assertThat(saleMovement.referenceType()).isEqualTo("SALE");
    assertThat(saleMovement.referenceId()).isEqualTo(saleId);
    assertThat(saleMovement.createdByUserId()).isEqualTo(operatorId);

    // Trilha de auditoria: um evento por operação — nenhuma operação grava dois nem nenhuma
    // operação some —, na ordem do fluxo e com o ator certo: o GERENTE assina o desconto e o
    // OPERADOR assina todo o resto (matriz do §4.5).
    List<AuditRow> trail = auditTrail(saleId);
    assertThat(trail)
        .extracting(AuditRow::action)
        .as("três itens = três SALE_ITEM_ADDED; dois pagamentos = dois PAYMENT_ADDED")
        .containsExactly(
            "SALE_CREATED",
            "SALE_ITEM_ADDED",
            "SALE_ITEM_ADDED",
            "SALE_ITEM_ADDED",
            "SALE_DISCOUNT_APPLIED",
            "PAYMENT_ADDED",
            "PAYMENT_ADDED",
            "SALE_COMPLETED");
    assertThat(trail)
        .extracting(AuditRow::actorUserId)
        .as("só o SALE_DISCOUNT_APPLIED é do gerente")
        .containsExactly(
            operatorId,
            operatorId,
            operatorId,
            operatorId,
            managerId,
            operatorId,
            operatorId,
            operatorId);
    assertThat(trail).extracting(AuditRow::entityType).containsOnly("SALE");
    assertThat(trail).extracting(AuditRow::source).containsOnly(CLIENT);
    assertThat(trail.get(4).reason())
        .as("o motivo do desconto é o rastro humano do evento")
        .isEqualTo(DISCOUNT_REASON);
    assertThat(trail.get(4).actorUsername())
        .as("o desconto é assinado pelo gerente, não por quem opera a venda")
        .isEqualTo(managerUsername);

    JsonPath cashPayment = JsonPath.from(trail.get(5).details());
    assertThat(cashPayment.getString("method")).isEqualTo("CASH");
    assertThat(number(cashPayment.get("amount"))).isEqualByComparingTo("10.00");
    assertThat(number(cashPayment.get("tenderedAmount"))).isEqualByComparingTo("20.00");
    assertThat(number(cashPayment.get("changeAmount")))
        .as("o troco calculado pelo servidor fica no evento")
        .isEqualByComparingTo("10.00");

    JsonPath completion = JsonPath.from(trail.getLast().details());
    assertThat(number(completion.get("total"))).isEqualByComparingTo("27.00");
    assertThat(number(completion.get("paidAmount"))).isEqualByComparingTo("27.00");
    assertThat(number(completion.get("changeAmount"))).isEqualByComparingTo("10.00");
    Map<String, Object> paymentsByMethod = completion.getMap("paymentsByMethod");
    assertThat(number(paymentsByMethod.get("CASH"))).isEqualByComparingTo("10.00");
    assertThat(number(paymentsByMethod.get("CREDIT"))).isEqualByComparingTo("17.00");

    // Resumo do caixa (passo 909): o esperado é abertura + a parcela em dinheiro da venda; o
    // crédito aparece na quebra por forma, mas fora do dinheiro esperado.
    Response summary = get(operatorToken, CASH_SESSIONS_PATH + "/" + cashSessionId + "/summary");
    assertThat(summary.statusCode()).as("resposta: %s", summary.asString()).isEqualTo(200);
    Map<String, Object> summaryBody = summary.jsonPath().getMap("$");
    assertThat(decimal(summaryBody, "expectedAmount"))
        .as("100,00 de abertura + 10,00 da venda em dinheiro")
        .isEqualByComparingTo("110.00");
    Map<String, Object> summaryPayments = map(summaryBody, "paymentsByMethod");
    assertThat(decimal(summaryPayments, "CASH")).isEqualByComparingTo("10.00");
    assertThat(decimal(summaryPayments, "CREDIT"))
        .as("a venda no cartão aparece na quebra, mas não no esperado")
        .isEqualByComparingTo("17.00");
  }

  /**
   * Remove o que o fluxo comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves de
   * idempotência (que referenciam o usuário), pagamentos, itens, venda e os eventos dela;
   * movimentos e saldos de estoque antes do produto; movimentos e sessão de caixa; a série; e, por
   * fim, os eventos, as sessões de auth, os papéis e os dois usuários do cenário.
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

  /** Abre o caixa do cenário pela API (passo 607) com chave nova; o status fica com o chamador. */
  private Response open(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"openingAmount\": %s}".formatted(OPENING_AMOUNT))
        .when()
        .post(OPEN_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** Abre a venda no caixa da sessão (passo 807) com chave nova; o status fica com o chamador. */
  private Response createSale(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
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
  private Response addItem(String token, UUID saleId, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
  }

  /**
   * Aplica o desconto de 10% pela rota do 811b, com o token de quem pede: o OPERADOR não tem {@code
   * sale.discount.apply} e recebe 403; o GERENTE aplica. Não pede {@code Idempotency-Key} — o §8 só
   * a exige em {@code POST /sales}, pagamentos, conclusão, cancelamento e dinheiro.
   */
  private Response putDiscount(String token, UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(
            "{\"type\": \"PERCENT\", \"value\": 10, \"reason\": \"%s\"}".formatted(DISCOUNT_REASON))
        .when()
        .put(SALES_PATH + "/" + saleId + "/discount")
        .then()
        .extract()
        .response();
  }

  /** Registra o pagamento pela rota do 905 com chave nova; o status fica com o chamador. */
  private Response pay(
      String token, UUID saleId, String method, String amount, String tenderedAmount) {
    String tendered = tenderedAmount == null ? "" : ", \"tenderedAmount\": " + tenderedAmount;
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"method\": \"%s\", \"amount\": %s%s}".formatted(method, amount, tendered))
        .when()
        .post(SALES_PATH + "/" + saleId + "/payments")
        .then()
        .extract()
        .response();
  }

  /** Conclui a venda pela rota do 907 com chave nova; o status fica com o chamador. */
  private Response complete(String token, UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .when()
        .post(SALES_PATH + "/" + saleId + "/complete")
        .then()
        .extract()
        .response();
  }

  /** GET autenticado como o ator informado (resumo do caixa, passo 909). */
  private Response get(String token, String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(path)
        .then()
        .extract()
        .response();
  }

  /**
   * Semeia um produto do cenário pela porta do catálogo (em transação própria, como o cadastro o
   * grava) e o estoque inicial pelo {@code StockService} (passo 703), devolvendo a fixture que o
   * fluxo e as conferências usam. O barcode é {@code 789100 + bloco aleatório + índice + 90}: os 13
   * dígitos de um EAN-13, sem colidir com o produto de outra execução.
   */
  private ProductFixture seedProduct(
      String name, String price, String quantity, String balanceAfter, int index)
      throws SQLException {
    String barcode = "789100" + BARCODE_BLOCK + index + "90";
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
            operatorId));
    return new ProductFixture(id, name, barcode, quantity, balanceAfter);
  }

  /** Cria o usuário pelo caso de uso (passo 107) com o papel pedido e devolve o id. */
  private UUID createUser(String username, List<String> roleCodes) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, roleCodes))
            .id();
    userIds.add(id);
    return id;
  }

  /**
   * Login pela API (passo 205) vinculando a sessão ao caixa informado e declarando a origem {@code
   * TUI} do PDV — é ela que vira o {@code source} dos eventos do fluxo.
   */
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
    String key = "fluxo.completo." + SUFFIX + "." + UUID.randomUUID();
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

  /** A venda como o banco a guardou: estado, totais, troco e instante da conclusão. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, subtotal::text as subtotal, discount_amount::text as"
                    + " discount_amount, total::text as total, paid_amount::text as paid_amount,"
                    + " change_amount::text as change_amount, item_count, completed_at"
                    + " from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            resultSet.getString("subtotal"),
            resultSet.getString("discount_amount"),
            resultSet.getString("total"),
            resultSet.getString("paid_amount"),
            resultSet.getString("change_amount"),
            resultSet.getInt("item_count"),
            instant(resultSet.getTimestamp("completed_at")));
      }
    }
  }

  /** Ledger do produto em ordem de aplicação ({@code created_at}, id do UUIDv7). */
  private List<MovementRow> stockMovements(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, quantity_delta::text as delta, balance_after::text as balance_after,"
                    + " reference_type, reference_id, created_by_user_id from stock_movements"
                    + " where product_id = ? order by created_at, id")) {
      statement.setObject(1, productId);
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

  /**
   * Saldo materializado do produto: o cache que o último {@code balance_after} do ledger atualiza.
   */
  private BigDecimal stockQuantity(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("saldo do produto %s", productId).isTrue();
        return new BigDecimal(resultSet.getString(1));
      }
    }
  }

  /**
   * Movimentos do caixa da sessão em ordem cronológica: abertura, vendas, sangrias e suprimentos.
   */
  private List<CashMovementRow> cashMovements(UUID cashSessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, amount::text as amount, payment_method, reference_type, reference_id,"
                    + " created_by_user_id from cash_movements where cash_session_id = ?"
                    + " order by created_at, id")) {
      statement.setObject(1, cashSessionId);
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

  /**
   * A trilha da venda na ordem de gravação: a coluna {@code id} é identity justamente para dar a
   * ordem dos eventos (§5.3) e o filtro é sempre por {@code entity_id} — nunca contagem global, que
   * outras linhas do log inflariam.
   */
  private List<AuditRow> auditTrail(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action, actor_user_id, actor_username, source, entity_type, reason,"
                    + " details::text as details from audit_events where entity_id = ? order by id")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<AuditRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new AuditRow(
                  resultSet.getString("action"),
                  resultSet.getObject("actor_user_id", UUID.class),
                  resultSet.getString("actor_username"),
                  resultSet.getString("source"),
                  resultSet.getString("entity_type"),
                  resultSet.getString("reason"),
                  resultSet.getString("details")));
        }
        return rows;
      }
    }
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal decimal(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  /** Valor numérico do {@code details} do evento como BigDecimal, pelo mesmo motivo do corpo. */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no details do evento").isNotNull();
    return new BigDecimal(value.toString());
  }

  /** Submapa do corpo (ex.: {@code paymentsByMethod} do resumo do caixa). */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> body, String field) {
    Map<String, Object> nested = (Map<String, Object>) body.get(field);
    assertThat(nested).as("mapa %s no corpo", field).isNotNull();
    return nested;
  }

  /** Instante do banco como o {@code Clock} o gravou; nulo quando a coluna é nula. */
  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
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

  /** Produto do cenário: o que o fluxo bipa e o que as conferências esperam do ledger. */
  private record ProductFixture(
      UUID id, String name, String barcode, String quantity, String balanceAfter) {}

  /** Linha de {@code sales} com a conferência do fim do fluxo. */
  private record SaleRow(
      String status,
      String subtotal,
      String discountAmount,
      String total,
      String paidAmount,
      String changeAmount,
      int itemCount,
      Instant completedAt) {}

  /** Linha de {@code stock_movements}: o delta, o saldo resultante e de onde o movimento veio. */
  private record MovementRow(
      String type,
      BigDecimal delta,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId,
      UUID createdByUserId) {}

  /** Linha de {@code cash_movements}: o tipo, o valor e o documento que a originou. */
  private record CashMovementRow(
      String type,
      BigDecimal amount,
      String paymentMethod,
      String referenceType,
      UUID referenceId,
      UUID createdByUserId) {}

  /** Linha de {@code audit_events} com os campos que o fluxo confere. */
  private record AuditRow(
      String action,
      UUID actorUserId,
      String actorUsername,
      String source,
      String entityType,
      String reason,
      String details) {}
}
