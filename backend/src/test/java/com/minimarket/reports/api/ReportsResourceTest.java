package com.minimarket.reports.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ApplyStockMovementCommand;
import com.minimarket.inventory.application.StockService;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.sales.api.SalesResource;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Relatórios na API (passo 1212a, §9.3) contra PostgreSQL real (Dev Services): o resumo de vendas
 * por período com os três {@code groupBy} e o relatório de estoque baixo, pela rota de verdade com
 * atores reais — OPERADOR (403), GERENTE (200) e o ADMIN da fixture.
 *
 * <p>As vendas do cenário nascem pela API, como o PDV as cria (sessão de caixa aberta, item,
 * pagamentos), e o {@code completed_at} é fixado por SQL para o período do relatório não depender
 * do relógio — mesmo precedente do {@code SalesQueryResourceTest}. A trilha de auditoria, os
 * movimentos de caixa e os de estoque não entram: a venda é marcada {@code COMPLETED} direto no
 * banco, porque quem conclui de verdade tem teste próprio ({@code CompleteSaleResourceTest}) e aqui
 * o alvo é a agregação. Os produtos saem da porta do catálogo e o estoque do {@code StockService}
 * (passo 703), os dois em transação própria.
 *
 * <p>O request HTTP comita, então a limpeza do {@code @AfterEach} apaga o que a classe criou na
 * ordem das FKs {@code restrict}: chaves, pagamentos, itens, vendas, sessão e movimentos do caixa,
 * série da venda, movimentos/saldos de estoque, produtos, eventos e por fim as sessões de auth,
 * papéis e usuários — o banco é compartilhado com os demais testes do fork.
 */
@QuarkusTest
class ReportsResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String SUMMARY_PATH = ReportsResource.PATH + "/sales-summary";

  private static final String LOW_STOCK_PATH = ReportsResource.PATH + "/low-stock";

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da sessão que abre as vendas. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Janela do faturamento: três vendas concluídas em três dias e uma aberta com data na janela. */
  private static final Instant WINDOW_FROM = Instant.parse("2026-01-10T00:00:00Z");

  private static final Instant WINDOW_TO = Instant.parse("2026-01-13T00:00:00Z");
  private static final Instant DAY_ONE = Instant.parse("2026-01-10T12:00:00Z");
  private static final Instant DAY_TWO = Instant.parse("2026-01-11T12:00:00Z");
  private static final Instant DAY_THREE = Instant.parse("2026-01-12T12:00:00Z");

  /**
   * Janela do limite do período: uma venda exatamente no {@code from} e outra exatamente no {@code
   * to}.
   */
  private static final Instant BOUNDARY_FROM = Instant.parse("2026-03-01T10:00:00Z");

  private static final Instant BOUNDARY_TO = Instant.parse("2026-03-02T10:00:00Z");

  /** Janela sem venda nenhuma: o shape vazio do resumo. */
  private static final Instant EMPTY_FROM = Instant.parse("2026-09-01T00:00:00Z");

  private static final Instant EMPTY_TO = Instant.parse("2026-09-02T00:00:00Z");

  /** Caso de uso da criação de usuário (passo 107): os atores são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: os produtos do cenário nascem por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Caminho de escrita do saldo (passo 703): o mínimo configurado do relatório tem saldo real. */
  @Inject StockService stockService;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Chaves de idempotência do cenário: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste (com pagamentos, itens e eventos delas). */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários do cenário (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Produtos semeados pelo teste (com movimentos e saldos deles). */
  private final List<UUID> productIds = new ArrayList<>();

  private UUID registerId;

  private UUID operatorAId;

  private UUID operatorBId;

  private String operatorAToken;

  private String operatorBToken;

  private String managerToken;

  /** Sessão de caixa da fixture: aberta no primeiro cenário que vende. */
  private UUID cashSessionId;

  private UUID storeId;

  /**
   * Três atores reais com os papéis do §4.5: os OPERADORES (um por grupo de operador) logam
   * vinculados ao {@code CAIXA-01} do seed e o GERENTE (tem {@code report.read}) loga sem caixa —
   * ele só consulta. A sessão de caixa abre no primeiro cenário que vende.
   */
  @BeforeEach
  void prepareActors() throws SQLException {
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    operatorAId = createUser("relatorios.a." + SUFFIX, "OPERADOR");
    operatorBId = createUser("relatorios.b." + SUFFIX, "OPERADOR");
    createUser("relatorios.gerente." + SUFFIX, "GERENTE");
    operatorAToken = login("relatorios.a." + SUFFIX, registerId);
    operatorBToken = login("relatorios.b." + SUFFIX, registerId);
    managerToken = login("relatorios.gerente." + SUFFIX, null);
  }

  @Test
  @DisplayName(
      "groupBy=day e operator agregam as vendas concluídas do período; a venda aberta fica fora")
  void groupsByDayAndOperator() throws SQLException {
    prepareBillingWindow();

    Response day = salesSummary(managerToken, WINDOW_FROM, WINDOW_TO, "day");

    assertThat(day.statusCode()).as("resposta: %s", day.asString()).isEqualTo(200);
    assertThat(day.contentType()).contains("application/json");
    Map<String, Object> body = day.jsonPath().getMap("$");
    assertThat(body)
        .as("contrato do resumo: sem storeId nem entidade")
        .containsOnlyKeys(
            "from", "to", "groupBy", "salesCount", "total", "ticketAverage", "groups");
    assertThat(Instant.parse((String) body.get("from"))).isEqualTo(WINDOW_FROM);
    assertThat(Instant.parse((String) body.get("to"))).isEqualTo(WINDOW_TO);
    assertThat(body.get("groupBy")).isEqualTo("day");
    assertThat(day.jsonPath().getLong("salesCount"))
        .as("a venda aberta não é faturamento, mesmo com completed_at na janela")
        .isEqualTo(3);
    assertThat(decimal(day, "total")).isEqualByComparingTo("40.03");
    assertThat(decimal(day, "ticketAverage")).isEqualByComparingTo("13.34");

    List<ReportGroup> dayGroups = groupsOf(day);
    assertThat(dayGroups)
        .extracting(ReportGroup::key)
        .as("dias com venda, em ordem crescente")
        .containsExactly("2026-01-10", "2026-01-11", "2026-01-12");
    assertGroup(dayGroups.get(0), 1, "10.01");
    assertGroup(dayGroups.get(1), 1, "10.02");
    assertGroup(dayGroups.get(2), 1, "20.00");

    Response operator = salesSummary(managerToken, WINDOW_FROM, WINDOW_TO, "operator");

    assertThat(operator.statusCode()).as("resposta: %s", operator.asString()).isEqualTo(200);
    assertThat(operator.jsonPath().getString("groupBy")).isEqualTo("operator");
    List<ReportGroup> operatorGroups = groupsOf(operator);
    assertThat(operatorGroups)
        .extracting(ReportGroup::key)
        .containsExactlyInAnyOrder(operatorAId.toString(), operatorBId.toString());
    ReportGroup groupA = groupOf(operatorGroups, operatorAId.toString());
    assertGroup(groupA, 2, "20.03");
    ReportGroup groupB = groupOf(operatorGroups, operatorBId.toString());
    assertGroup(groupB, 1, "20.00");
  }

  @Test
  @DisplayName(
      "groupBy=paymentMethod quebra pelos pagamentos aprovados, com as cinco formas na ordem do enum")
  void groupsByPaymentMethod() throws SQLException {
    prepareBillingWindow();

    Response response = salesSummary(managerToken, WINDOW_FROM, WINDOW_TO, "paymentMethod");

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getString("groupBy")).isEqualTo("paymentMethod");
    assertThat(response.jsonPath().getLong("salesCount"))
        .as("os totais do resumo são das vendas, não dos grupos")
        .isEqualTo(3);
    List<ReportGroup> groups = groupsOf(response);
    assertThat(groups)
        .extracting(ReportGroup::key)
        .as("as cinco formas do enum, inclusive zeradas")
        .containsExactly("CASH", "PIX", "DEBIT", "CREDIT", "VOUCHER");
    assertGroup(groups.get(0), 2, "15.01");
    assertGroup(groups.get(1), 1, "15.00");
    assertGroup(groups.get(2), 0, "0.00");
    assertGroup(groups.get(3), 1, "10.02");
    assertGroup(groups.get(4), 0, "0.00");
  }

  @Test
  @DisplayName("ticket médio: 20,03 em 2 vendas é 10,02, na escala 2 com HALF_UP")
  void roundsTicketAverageHalfUp() throws SQLException {
    UUID saleA = saleWithOneItem(operatorAToken, seedProduct("Relatorio media a", "10.01", null));
    pay(operatorAToken, saleA, "CASH", "10.01", "10.01");
    markCompleted(saleA, DAY_ONE);
    UUID saleB = saleWithOneItem(operatorAToken, seedProduct("Relatorio media b", "10.02", null));
    pay(operatorAToken, saleB, "PIX", "10.02", null);
    markCompleted(saleB, DAY_TWO);

    Response response = salesSummary(managerToken, WINDOW_FROM, WINDOW_TO, "day");

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getLong("salesCount")).isEqualTo(2);
    assertThat(decimal(response, "total")).isEqualByComparingTo("20.03");
    assertThat(decimal(response, "ticketAverage"))
        .as("20,03 / 2 = 10,015 → 10,02")
        .isEqualByComparingTo("10.02");
  }

  @Test
  @DisplayName("período: from é inclusivo e to é exclusivo sobre o completed_at")
  void periodIsInclusiveAtFromAndExclusiveAtTo() throws SQLException {
    UUID included = saleWithOneItem(operatorAToken, seedProduct("Relatorio limite", "20.00", null));
    pay(operatorAToken, included, "PIX", "20.00", null);
    markCompleted(included, BOUNDARY_FROM);
    UUID excluded =
        saleWithOneItem(operatorBToken, seedProduct("Relatorio limite fora", "1.00", null));
    pay(operatorBToken, excluded, "PIX", "1.00", null);
    markCompleted(excluded, BOUNDARY_TO);

    Response response = salesSummary(managerToken, BOUNDARY_FROM, BOUNDARY_TO, "day");

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getLong("salesCount"))
        .as("a venda do limite to fica fora")
        .isEqualTo(1);
    assertThat(decimal(response, "total")).isEqualByComparingTo("20.00");
    List<ReportGroup> groups = groupsOf(response);
    assertThat(groups).extracting(ReportGroup::key).containsExactly("2026-03-01");
    assertGroup(groups.getFirst(), 1, "20.00");
  }

  @Test
  @DisplayName("parâmetros ausentes, inválidos ou período vazio respondem 400 citando o campo")
  void rejectsMissingAndInvalidParameters() {
    List<InvalidParam> cases =
        List.of(
            new InvalidParam("from", "to=%s&groupBy=day".formatted(WINDOW_TO)),
            new InvalidParam("to", "from=%s&groupBy=day".formatted(WINDOW_FROM)),
            new InvalidParam("groupBy", "from=%s&to=%s".formatted(WINDOW_FROM, WINDOW_TO)),
            new InvalidParam("from", "from=abc&to=%s&groupBy=day".formatted(WINDOW_TO)),
            new InvalidParam("to", "from=%s&to=31/12/2026&groupBy=day".formatted(WINDOW_FROM)),
            new InvalidParam(
                "groupBy", "from=%s&to=%s&groupBy=week".formatted(WINDOW_FROM, WINDOW_TO)),
            new InvalidParam("from", "from=%s&to=%s&groupBy=day".formatted(WINDOW_TO, WINDOW_FROM)),
            new InvalidParam(
                "from", "from=%s&to=%s&groupBy=day".formatted(WINDOW_FROM, WINDOW_FROM)));

    for (InvalidParam invalid : cases) {
      Response response = rawSummary(managerToken, invalid.query());

      assertThat(response.statusCode())
          .as("%s: %s", invalid.query(), response.asString())
          .isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
      assertThat(response.jsonPath().getList("errors.field", String.class))
          .as("o 400 cita o campo %s", invalid.field())
          .containsExactly(invalid.field());
    }
  }

  @Test
  @DisplayName("report.read: OPERADOR recebe 403 nas duas rotas; GERENTE e ADMIN recebem 200")
  void requiresReportRead() {
    String query = "from=%s&to=%s&groupBy=paymentMethod".formatted(EMPTY_FROM, EMPTY_TO);

    for (String path : List.of(SUMMARY_PATH + "?" + query, LOW_STOCK_PATH)) {
      Response denied = get(operatorAToken, path);

      assertThat(denied.statusCode()).as("%s para o OPERADOR", path).isEqualTo(403);
      assertThat(denied.contentType()).contains("application/problem+json");
      assertThat(denied.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
      assertThat(denied.jsonPath().getString("detail"))
          .as("a recusa do porteiro da rota cita a permissão de relatório")
          .contains("report.read");
    }

    Response emptySummary = rawSummary(managerToken, query);

    assertThat(emptySummary.statusCode())
        .as("resposta: %s", emptySummary.asString())
        .isEqualTo(200);
    assertThat(emptySummary.jsonPath().getLong("salesCount")).isZero();
    assertThat(decimal(emptySummary, "total")).isEqualByComparingTo("0.00");
    assertThat(decimal(emptySummary, "ticketAverage"))
        .as("período sem venda não divide por zero")
        .isEqualByComparingTo("0.00");
    List<ReportGroup> emptyGroups = groupsOf(emptySummary);
    assertThat(emptyGroups)
        .extracting(ReportGroup::key)
        .as("mesmo sem pagamento, as cinco formas vêm na ordem do enum")
        .containsExactly("CASH", "PIX", "DEBIT", "CREDIT", "VOUCHER");
    emptyGroups.forEach(group -> assertGroup(group, 0, "0.00"));

    assertThat(get(managerToken, LOW_STOCK_PATH).statusCode())
        .as("GERENTE tem report.read")
        .isEqualTo(200);
    assertThat(asAdmin().when().get(LOW_STOCK_PATH).then().extract().response().statusCode())
        .as("ADMIN tem report.read")
        .isEqualTo(200);
    assertThat(
            asAdmin()
                .when()
                .get(SUMMARY_PATH + "?" + query)
                .then()
                .extract()
                .response()
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @DisplayName(
      "low-stock lista só mínimo configurado com saldo no mínimo ou abaixo, em ordem de nome")
  void listsOnlyLowStockProducts() {
    UUID atMinimum = seedLowStockProduct("Relatorio baixo A", "9.90", "5.000", "5.000");
    UUID below = seedLowStockProduct("Relatorio baixo B", "9.90", "1.000", "2.000");
    seedLowStockProduct("Relatorio alto", "9.90", "10.000", "1.000");
    seedLowStockProduct("Relatorio sem minimo", "9.90", null, null);

    Response response = get(managerToken, LOW_STOCK_PATH);

    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getInt("page")).isZero();
    assertThat(response.jsonPath().getInt("size")).isEqualTo(20);

    List<Map<String, Object>> mine =
        itemsOf(response).stream()
            .filter(item -> String.valueOf(item.get("name")).contains(SUFFIX))
            .toList();
    assertThat(mine)
        .extracting(item -> item.get("name"))
        .as("ordem por nome como a lista de estoque")
        .containsExactly(name("Relatorio baixo A"), name("Relatorio baixo B"));
    assertThat(mine.get(0))
        .as("contrato do item: sem o flag derivado nem storeId")
        .containsOnlyKeys("productId", "name", "barcode", "unit", "quantity", "minQuantity");
    assertThat(mine.get(0).get("productId")).isEqualTo(atMinimum.toString());
    assertThat(mine.get(1).get("productId")).isEqualTo(below.toString());
    assertThat(mine.get(0).get("barcode")).isNull();
    assertThat(mine.get(0).get("unit")).isEqualTo("UN");
    assertThat(decimal(mine.get(0), "quantity")).isEqualByComparingTo("5.000");
    assertThat(decimal(mine.get(0), "minQuantity")).isEqualByComparingTo("5.000");
    assertThat(response.jsonPath().getList("items.name", String.class))
        .as("acima do mínimo e produto sem mínimo ficam fora")
        .doesNotContain(name("Relatorio alto"), name("Relatorio sem minimo"));
  }

  @Test
  @DisplayName(
      "low-stock: size acima do teto é limitado a 100 e page/size fora da regra respondem 400")
  void capsAndValidatesLowStockPagination() {
    Response capped = get(managerToken, LOW_STOCK_PATH + "?size=250");

    assertThat(capped.statusCode()).as("resposta: %s", capped.asString()).isEqualTo(200);
    assertThat(capped.jsonPath().getInt("size")).as("teto de 100 do §9.1").isEqualTo(100);

    for (String query : List.of("?page=-1", "?size=0")) {
      Response response = get(managerToken, LOW_STOCK_PATH + query);

      assertThat(response.statusCode()).as("%s: %s", query, response.asString()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    Response notANumber = get(managerToken, LOW_STOCK_PATH + "?size=abc");

    assertThat(notANumber.statusCode()).isEqualTo(400);
    assertThat(notANumber.jsonPath().getList("errors.field", String.class))
        .as("o 400 cita o campo do tamanho")
        .containsExactly("size");
  }

  /**
   * O faturamento da janela: três vendas concluídas em dias distintos — a primeira com um pagamento
   * cancelado antes do pagamento que fica, para provar o filtro de aprovados — e uma venda aberta
   * com {@code completed_at} na janela, que não pode entrar em grupo nenhum.
   */
  private void prepareBillingWindow() throws SQLException {
    UUID saleA = saleWithOneItem(operatorAToken, seedProduct("Relatorio arroz", "10.01", null));
    UUID debit = pay(operatorAToken, saleA, "DEBIT", "1.00", null);
    cancelPayment(operatorAToken, saleA, debit);
    pay(operatorAToken, saleA, "CASH", "10.01", "20.00");
    markCompleted(saleA, DAY_ONE);

    UUID saleB = saleWithOneItem(operatorAToken, seedProduct("Relatorio feijao", "10.02", null));
    pay(operatorAToken, saleB, "CREDIT", "10.02", null);
    markCompleted(saleB, DAY_TWO);

    UUID saleC = saleWithOneItem(operatorBToken, seedProduct("Relatorio cafe", "20.00", null));
    pay(operatorBToken, saleC, "CASH", "5.00", "5.00");
    pay(operatorBToken, saleC, "PIX", "15.00", null);
    markCompleted(saleC, DAY_THREE);

    UUID openSale = saleWithOneItem(operatorBToken, seedProduct("Relatorio aberta", "1.00", null));
    pay(operatorBToken, openSale, "PIX", "1.00", null);
    setCompletedAt(openSale, DAY_THREE);
  }

  /** Venda de um item pela API, na sessão de caixa da fixture: a base das vendas do cenário. */
  private UUID saleWithOneItem(String token, UUID productId) {
    cashSession();
    UUID saleId = createSale(token);
    given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"productId\": \"%s\", \"quantity\": 1}".formatted(productId))
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .statusCode(200);
    return saleId;
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

  /** Registra o pagamento pela rota do 905 com chave nova e devolve o id do pagamento. */
  private UUID pay(String token, UUID saleId, String method, String amount, String tenderedAmount) {
    String tendered = tenderedAmount == null ? "" : ", \"tenderedAmount\": " + tenderedAmount;
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .contentType("application/json")
            .body("{\"method\": \"%s\", \"amount\": %s%s}".formatted(method, amount, tendered))
            .when()
            .post(SALES_PATH + "/" + saleId + "/payments")
            .then()
            .statusCode(201)
            .extract()
            .response();
    // O 201 devolve a venda inteira (passo 905): o pagamento recém-registrado é o último da lista.
    List<String> paymentIds = response.jsonPath().getList("payments.id", String.class);
    return UUID.fromString(paymentIds.getLast());
  }

  /** Cancela o pagamento pela rota do 905: o status vira {@code CANCELLED} e sai dos agregados. */
  private void cancelPayment(String token, UUID saleId, UUID paymentId) {
    given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/payments/" + paymentId)
        .then()
        .statusCode(200);
  }

  /** Sessão de caixa da fixture, aberta no primeiro cenário que vende (BR-06). */
  private UUID cashSession() {
    if (cashSessionId == null) {
      Response response =
          given()
              .header(AUTHORIZATION, "Bearer " + operatorAToken)
              .header(IdempotencyGuard.KEY_HEADER, newKey())
              .contentType("application/json")
              .body("{\"openingAmount\": 100.00}")
              .when()
              .post(OPEN_PATH.formatted(registerId))
              .then()
              .statusCode(201)
              .extract()
              .response();
      cashSessionId = UUID.fromString(response.jsonPath().getString("id"));
      cashSessionIds.add(cashSessionId);
    }
    return cashSessionId;
  }

  /** Venda concluída no instante fixo: quem conclui de verdade tem teste próprio. */
  private void markCompleted(UUID saleId, Instant completedAt) throws SQLException {
    updateCompletedAt(
        saleId,
        "update sales set status = 'COMPLETED', completed_at = ?::timestamptz where id = ?",
        completedAt);
  }

  /**
   * Só o {@code completed_at}, com a venda ainda aberta: o filtro de status do relatório é o alvo.
   */
  private void setCompletedAt(UUID saleId, Instant completedAt) throws SQLException {
    updateCompletedAt(
        saleId, "update sales set completed_at = ?::timestamptz where id = ?", completedAt);
  }

  private void updateCompletedAt(UUID saleId, String sql, Instant completedAt) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, completedAt.toString());
      statement.setObject(2, saleId);
      statement.executeUpdate();
    }
  }

  /** Produto do cenário pela porta do catálogo, com mínimo opcional. */
  private UUID seedProduct(String base, String price, String minQuantity) {
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(),
                            name(base),
                            null,
                            null,
                            null,
                            "UN",
                            new BigDecimal(price),
                            minQuantity == null ? null : new BigDecimal(minQuantity))));
    productIds.add(id);
    return id;
  }

  /**
   * Produto com mínimo e saldo pelo {@code StockService}: o filtro de estoque baixo compara os
   * dois. {@code stock} nulo = produto sem linha de saldo — a lista o mostra com zero, como o
   * produto que nunca teve movimento.
   */
  private UUID seedLowStockProduct(String base, String price, String stock, String minQuantity) {
    UUID id = seedProduct(base, price, minQuantity);
    if (stock != null) {
      stockService.applyMovement(
          new ApplyStockMovementCommand(
              id,
              StockMovementType.INITIAL,
              new BigDecimal(stock),
              null,
              null,
              null,
              null,
              operatorAId));
    }
    return id;
  }

  /** GET do resumo com o período e o {@code groupBy} informados. */
  private Response salesSummary(String token, Instant from, Instant to, String groupBy) {
    return rawSummary(token, "from=%s&to=%s&groupBy=%s".formatted(from, to, groupBy));
  }

  /** GET do resumo com a query crua: os casos inválidos montam a string na mão. */
  private static Response rawSummary(String token, String query) {
    return get(token, SUMMARY_PATH + "?" + query);
  }

  private static Response get(String token, String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(path)
        .then()
        .extract()
        .response();
  }

  /** Cria o usuário pelo caso de uso (passo 107) com o papel pedido e devolve o id. */
  private UUID createUser(String username, String roleCode) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)))
            .id();
    userIds.add(id);
    return id;
  }

  /** Login pela API (passo 205), com ou sem o caixa vinculado — o vínculo é da sessão do PDV. */
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

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "relatorios." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Id da loja do seed, resolvido pela porta sem importar infrastructure alheia. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      storeLookup
                          .findByCode(defaultStoreCode)
                          .orElseThrow(
                              () -> new IllegalStateException("loja do seed da V1 ausente"))
                          .id());
    }
    return storeId;
  }

  /** Nome de produto com o sufixo da classe, para a limpeza e o filtro do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
  }

  /** Grupo do resumo como o JSON o devolveu. */
  private record ReportGroup(String key, long salesCount, BigDecimal total) {}

  private static List<ReportGroup> groupsOf(Response response) {
    List<Map<String, Object>> groups = response.jsonPath().getList("groups");
    return groups.stream()
        .map(
            group ->
                new ReportGroup(
                    String.valueOf(group.get("key")),
                    ((Number) group.get("salesCount")).longValue(),
                    new BigDecimal(String.valueOf(group.get("total")))))
        .toList();
  }

  private static ReportGroup groupOf(List<ReportGroup> groups, String key) {
    return groups.stream()
        .filter(group -> key.equals(group.key()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("grupo %s não está no resumo: %s".formatted(key, groups)));
  }

  private static void assertGroup(ReportGroup group, long salesCount, String total) {
    assertThat(group.salesCount()).as("vendas do grupo %s", group.key()).isEqualTo(salesCount);
    assertThat(group.total()).as("total do grupo %s", group.key()).isEqualByComparingTo(total);
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal decimal(Response response, String path) {
    String value = response.jsonPath().getString(path);
    assertThat(value).as("campo %s no corpo", path).isNotNull();
    return new BigDecimal(value);
  }

  private static BigDecimal decimal(Map<String, Object> item, String field) {
    assertThat(item.get(field)).as("campo %s no item", field).isNotNull();
    return new BigDecimal(String.valueOf(item.get(field)));
  }

  private static List<Map<String, Object>> itemsOf(Response response) {
    return response.jsonPath().getList("items");
  }

  /** Parâmetro inválido do resumo: a query crua e o campo que o 400 precisa citar. */
  private record InvalidParam(String field, String query) {}

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), pagamentos, itens, vendas e os eventos deles;
   * movimentos e a sessão de caixa; a série da venda; movimentos e saldos de estoque antes do
   * produto; e, por fim, eventos, sessões de auth, papéis e usuários.
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
      for (UUID id : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", id);
        execute(connection, "delete from cash_sessions where id = ?", id);
        execute(connection, "delete from audit_events where entity_id = ?", id);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
      for (UUID id : productIds) {
        execute(connection, "delete from stock_movements where product_id = ?", id);
        execute(connection, "delete from product_stocks where product_id = ?", id);
        execute(connection, "delete from products where id = ?", id);
      }
      for (UUID id : userIds) {
        execute(
            connection,
            "delete from audit_events where actor_user_id = ? or entity_id = ?",
            id,
            id);
        execute(
            connection,
            "delete from audit_events where entity_id in"
                + " (select id from auth_sessions where user_id = ?)",
            id);
        execute(connection, "delete from auth_sessions where user_id = ?", id);
        execute(connection, "delete from user_roles where user_id = ?", id);
        execute(connection, "delete from users where id = ?", id);
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
}
