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
 * Trilha da sessão de caixa (passo 1006) contra PostgreSQL real (Dev Services): o fluxo de verdade
 * pela API — abrir o caixa, vender, concluir e fechar — e a consulta do §7.3 por {@code
 * cashSessionId}, que agora encontra os eventos das operações do fluxo.
 *
 * <p>O aceite é este teste: a abertura, a venda (criação, item, pagamento e conclusão) e o
 * fechamento aparecem na consulta filtrada, na ordem do fluxo, todos com {@code cashSessionId}
 * igual à sessão — antes do passo 1006 a coluna ficava nula e o filtro não encontrava nada do
 * fluxo. O ator é um GERENTE porque desconto e conclusão não são do OPERADOR (§4.5), e o login é de
 * verdade, vinculado ao {@code CAIXA-01} do seed (BR-11).
 *
 * <p>A leitura é do ADMIN da suíte, o dono de {@code audit.read} (passo 1001). O request HTTP
 * comita, então a limpeza espelha o {@code AuditSaleTimelineTest} (1002): chaves de idempotência,
 * pagamentos, itens, venda e seus eventos; estoque e produto; os movimentos, a sessão e os eventos
 * dela; a série e, por fim, os eventos, as sessões, os papéis e o usuário do ator.
 */
@QuarkusTest
class AuditCashSessionTrailTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String AUDIT_EVENTS_PATH = AuditEventsResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String CLOSE_PATH = "/api/v1/cash-registers/%s/close";

  /** Caixa do seed da V11: existe sempre, é a fixture do fluxo. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /** Cliente da sessão: a TUI opera o PDV e é a origem dos eventos do fluxo. */
  private static final String CLIENT = "TUI";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Bloco aleatório do barcode: sem colidir com o produto de outra execução. */
  private static final String BARCODE_BLOCK =
      String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));

  private static final String OPENING_AMOUNT = "100.00";

  private static final String PRICE = "10.00";

  private static final String INITIAL_STOCK = "50.000";

  /** Contagem do fechamento: 100,00 de abertura + 10,00 da venda em dinheiro (BR-10). */
  private static final String COUNTED_AMOUNT = "110.00";

  /** Caso de uso da criação de usuário (passo 107): o ator do teste é fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
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

  private String actorUsername;

  private String actorToken;

  private UUID actorId;

  private UUID registerId;

  private String barcode;

  @BeforeEach
  void prepareActorAndProduct() throws SQLException {
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    actorUsername = "trilha.sessao.gerente." + SUFFIX;
    actorId =
        createUserUseCase
            .execute(
                new CreateUserCommand(actorUsername, actorUsername, PASSWORD, List.of("GERENTE")))
            .id();
    userIds.add(actorId);
    actorToken = login(actorUsername, registerId);
    barcode = "789103" + BARCODE_BLOCK + "90";
    productIds.add(
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(),
                            "Feijão 1kg",
                            barcode,
                            null,
                            null,
                            "UN",
                            new BigDecimal(PRICE),
                            null))));
    stockService.applyMovement(
        new ApplyStockMovementCommand(
            productIds.getFirst(),
            StockMovementType.INITIAL,
            new BigDecimal(INITIAL_STOCK),
            null,
            null,
            null,
            null,
            actorId));
  }

  @Test
  @DisplayName(
      "GET /audit-events?cashSessionId: o fluxo abrir → vender → concluir → fechar aparece inteiro na consulta")
  void returnsTheCashSessionEventsOfTheRealFlow() {
    // 1. Abrir o caixa: a sessão autenticada já está vinculada ao CAIXA-01 pelo login e a operação
    //    idempotente (§8) leva Idempotency-Key nova.
    Response opened = openCashRegister();
    assertThat(opened.statusCode()).as("resposta: %s", opened.asString()).isEqualTo(201);
    UUID cashSessionId = UUID.fromString(opened.jsonPath().getString("id"));
    cashSessionIds.add(cashSessionId);

    // 2. Vender: venda, item bipado (o servidor resolve o barcode, BR-14), pagamento em dinheiro e
    //    conclusão — as operações do turno que precisam aparecer na trilha da sessão.
    Response created = createSale();
    assertThat(created.statusCode()).as("resposta: %s", created.asString()).isEqualTo(201);
    UUID saleId = UUID.fromString(created.jsonPath().getString("id"));
    saleIds.add(saleId);

    Response withItem = addItem(saleId);
    assertThat(withItem.statusCode()).as("resposta: %s", withItem.asString()).isEqualTo(200);

    Response payment = payCash(saleId);
    assertThat(payment.statusCode()).as("resposta: %s", payment.asString()).isEqualTo(201);

    Response completed = complete(saleId);
    assertThat(completed.statusCode()).as("resposta: %s", completed.asString()).isEqualTo(200);

    // 3. Fechar a sessão: dinheiro da venda já está no ledger (BR-10) e a conferência passa.
    Response closed = closeCashRegister();
    assertThat(closed.statusCode()).as("resposta: %s", closed.asString()).isEqualTo(200);
    assertThat(closed.jsonPath().getString("status")).isEqualTo("CLOSED");

    // 4. A consulta por sessão do §7.3, do jeito que a investigação a faz.
    Response trail = sessionTrail(cashSessionId);
    assertThat(trail.statusCode()).as("resposta: %s", trail.asString()).isEqualTo(200);
    assertThat(trail.jsonPath().getLong("totalItems"))
        .as("a sessão inteira: abertura, venda, pagamento, conclusão e fechamento")
        .isEqualTo(6);

    List<Map<String, Object>> events = trail.jsonPath().getList("items");
    assertThat(fieldOf(events, "action"))
        .as("na ordem do fluxo e sem o LOGIN_SUCCESS, que não tem sessão de caixa")
        .containsExactly(
            "CASH_SESSION_OPENED",
            "SALE_CREATED",
            "SALE_ITEM_ADDED",
            "PAYMENT_ADDED",
            "SALE_COMPLETED",
            "CASH_SESSION_CLOSED");
    assertThat(fieldOf(events, "cashSessionId"))
        .as("a coluna preenchida em todos os eventos do turno (passo 1006)")
        .containsOnly(cashSessionId.toString());
    assertThat(fieldOf(events, "actorUsername"))
        .as("um turno, um operador")
        .containsOnly(actorUsername);
    assertThat(fieldOf(events, "entityType")).containsOnly("CASH_SESSION", "SALE");

    // 5. O filtro recorta de verdade: sessão que não existe não devolve evento nenhum.
    Response unknown = sessionTrail(UUID.randomUUID());
    assertThat(unknown.jsonPath().getLong("totalItems")).isZero();
  }

  /**
   * Remove o que o fluxo comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves de
   * idempotência, pagamentos, itens, venda e os eventos dela; estoque e produto; movimentos, sessão
   * e os eventos dela; a série; e, por fim, os eventos, as sessões, os papéis e o usuário do ator.
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
  private Response addItem(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .contentType("application/json")
        .body("{\"barcode\": \"%s\", \"quantity\": 1}".formatted(barcode))
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
  }

  /** Registra o pagamento em dinheiro pela rota do 905 com chave nova (BR-05). */
  private Response payCash(UUID saleId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"method\": \"CASH\", \"amount\": %s, \"tenderedAmount\": 20.00}".formatted(PRICE))
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
   * Fecha o caixa com a contagem do cenário (passo 611) e chave nova; o status fica com o teste.
   */
  private Response closeCashRegister() {
    return given()
        .header(AUTHORIZATION, "Bearer " + actorToken)
        .header(IdempotencyGuard.KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"countedAmount\": %s}".formatted(COUNTED_AMOUNT))
        .when()
        .post(CLOSE_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /**
   * A consulta do §7.3 filtrada por sessão de caixa, com o token do ADMIN da suíte — o dono de
   * {@code audit.read}: a linha do tempo do turno, do começo para o fim.
   */
  private Response sessionTrail(UUID cashSessionId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .queryParam("cashSessionId", cashSessionId.toString())
        .queryParam("sort", "occurredat,asc")
        .queryParam("size", "100")
        .when()
        .get(AUDIT_EVENTS_PATH)
        .then()
        .extract()
        .response();
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
    String key = "trilha.sessao." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
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

  /** Campo de texto dos eventos da página, na ordem em que vieram. */
  private static List<String> fieldOf(List<Map<String, Object>> events, String field) {
    return events.stream().map(event -> (String) event.get(field)).toList();
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
