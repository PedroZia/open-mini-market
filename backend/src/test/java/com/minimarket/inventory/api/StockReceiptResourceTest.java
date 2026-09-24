package com.minimarket.inventory.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.api.AuthResource;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.support.TestAdmin;
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
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Entrada de mercadoria na API (passo 706) contra PostgreSQL real (Dev Services): a rota {@code
 * POST /api/v1/stock/{productId}/receipts} com o movimento {@code PURCHASE_IN} no ledger, o saldo
 * materializado, o custo do produto e o evento {@code STOCK_RECEIVED}.
 *
 * <p>O caminho feliz dá entrada com custo e sem custo com o ADMIN da fixture e confere as quatro
 * verdades: a linha do ledger com {@code balance_after} e {@code unit_cost}, o saldo de {@code
 * product_stocks}, o {@code cost_price} de {@code products} — atualizado só quando o custo vem
 * informado — e o evento com {@code before}/{@code after} do saldo, a quantidade e o custo. A
 * matriz de permissão usa um OPERADOR real ({@code CreateUserUseCase} + login de verdade, como no
 * {@code StockAdjustmentResourceTest}): {@code stock.read} é dele, mas {@code stock.receive} não —
 * o interceptor responde 403 {@code ACCESS_DENIED} citando a permissão antes do caso de uso e nada
 * é gravado (nem movimento, nem saldo, nem custo, nem evento, nem chave de idempotência).
 *
 * <p>A entrada move estoque, então a rota é idempotente por contrato (§8, passo 607a): cada chamada
 * leva uma {@code Idempotency-Key} nova e o teste cobre o replay (mesma resposta, {@code
 * Idempotency-Replayed: true}, um único movimento) e a reutilização com corpo diferente (409 {@code
 * IDEMPOTENCY_KEY_REUSED}, ainda um movimento). Quantidade não positiva e custo negativo são 400
 * {@code VALIDATION_ERROR} do caso de uso; produto inexistente ou soft-deletado é 404 {@code
 * PRODUCT_NOT_FOUND}.
 *
 * <p>O request HTTP comita, então a limpeza no {@code @AfterEach} apaga, na ordem das FKs, as
 * chaves, os eventos dos produtos, o ledger, os saldos e os produtos do sufixo da classe; depois os
 * eventos, as sessões de auth, os papéis e o usuário OPERADOR — o ADMIN da fixture é apagado pelo
 * {@code TestAdmin} depois (as tentativas negadas gravam {@code ACCESS_DENIED} do ator do teste).
 */
@QuarkusTest
class StockReceiptResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String AUTHORIZATION = "Authorization";

  /** Rota da entrada (§9.3, passo 706). */
  private static final String RECEIPTS_PATH = "/api/v1/stock/%s/receipts";

  /** Rota do detalhe (passo 704): é onde o efeito da entrada aparece e para onde o 201 aponta. */
  private static final String STOCK_DETAIL_PATH = "/api/v1/stock/%s";

  private static final String LOGIN_PATH = "/api/v1/auth/login";

  /** Cliente da sessão do OPERADOR: a TUI é quem opera o estoque. */
  private static final String CLIENT = "TUI";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Produto da fixture pela porta do catálogo, sem caso de uso nem auditoria. */
  @Inject ProductStore productStore;

  /** Caso de uso da criação de usuário (passo 107): o OPERADOR da matriz é fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Id da loja do seed da V1, resolvido no primeiro uso. */
  private UUID storeId;

  @Test
  @DisplayName("entrada com e sem custo: 201, movimento PURCHASE_IN, saldo, cost_price e eventos")
  void receivesWithAndWithoutCost() throws SQLException {
    UUID productId = seedProduct("Entrada", "911" + SUFFIX);
    String token = adminToken();
    UUID adminId = adminUserId();

    Response withCost =
        postReceipt(productId, newKey(), receiptBody("10.000", "4.50", "nota 123"), token);

    assertThat(withCost.statusCode()).as("resposta: %s", withCost.asString()).isEqualTo(201);
    assertThat(withCost.header("Location"))
        .as("o Location aponta para onde o efeito é visível (passo 704)")
        .endsWith(STOCK_DETAIL_PATH.formatted(productId));
    Map<String, Object> withCostBody = withCost.jsonPath().getMap("$");
    assertThat(withCostBody)
        .as("contrato do 201: nem storeId nem entidade do ledger vazam")
        .containsOnlyKeys(
            "movementId", "productId", "quantity", "unitCost", "balanceBefore", "balanceAfter");
    assertThat(withCostBody.get("productId")).isEqualTo(productId.toString());
    assertThat(number(withCostBody.get("quantity"))).isEqualByComparingTo("10.000");
    assertThat(number(withCostBody.get("unitCost"))).isEqualByComparingTo("4.50");
    assertThat(number(withCostBody.get("balanceBefore"))).isEqualByComparingTo("0");
    assertThat(number(withCostBody.get("balanceAfter"))).isEqualByComparingTo("10.000");

    Response withoutCost =
        postReceipt(productId, newKey(), receiptBody("2.500", null, null), token);

    assertThat(withoutCost.statusCode()).as("resposta: %s", withoutCost.asString()).isEqualTo(201);
    Map<String, Object> withoutCostBody = withoutCost.jsonPath().getMap("$");
    assertThat(withoutCostBody.get("movementId")).isNotEqualTo(withCostBody.get("movementId"));
    assertThat(number(withoutCostBody.get("quantity"))).isEqualByComparingTo("2.500");
    assertThat(withoutCostBody.get("unitCost"))
        .as("sem custo informado o campo vem nulo, não zerado")
        .isNull();
    assertThat(number(withoutCostBody.get("balanceBefore"))).isEqualByComparingTo("10.000");
    assertThat(number(withoutCostBody.get("balanceAfter"))).isEqualByComparingTo("12.500");

    // Ledger: um movimento PURCHASE_IN por chamada, com o delta positivo e o balance_after correto.
    List<MovementRow> ledger = ledgerRows(productId);

    assertThat(ledger).hasSize(2);
    assertThat(ledger).extracting(MovementRow::type).containsOnly("PURCHASE_IN");
    assertThat(ledger.get(0).delta()).isEqualByComparingTo("10.000");
    assertThat(ledger.get(0).balanceAfter()).isEqualByComparingTo("10.000");
    assertThat(ledger.get(0).unitCost()).isEqualByComparingTo("4.50");
    assertThat(ledger.get(0).reason()).isEqualTo("nota 123");
    assertThat(ledger.get(1).delta()).isEqualByComparingTo("2.500");
    assertThat(ledger.get(1).balanceAfter()).isEqualByComparingTo("12.500");
    assertThat(ledger.get(1).unitCost()).as("sem custo informado o ledger grava nulo").isNull();
    assertThat(ledger.get(1).reason()).isNull();
    assertThat(ledger)
        .extracting(MovementRow::createdByUserId)
        .as("o movimento é do ator do token")
        .containsOnly(adminId);

    // O saldo materializado é o balance_after do último movimento.
    assertThat(stockQuantity(productId)).isEqualByComparingTo("12.500");

    // O custo do produto é o da última entrada que o informou; a sem custo não o toca.
    assertThat(costPrice(productId)).isEqualByComparingTo("4.50");

    // Auditoria: um STOCK_RECEIVED por entrada, com before/after do saldo, quantidade e custo.
    List<AuditEvent> events = eventsOf(productId, "STOCK_RECEIVED");

    assertThat(events).hasSize(2);
    assertThat(events).extracting(AuditEvent::entityType).containsOnly("PRODUCT");
    assertThat(events)
        .extracting(AuditEvent::source)
        .as("origem da sessão autenticada (o ADMIN da fixture loga sem X-Client: WEB), não SYSTEM")
        .containsOnly("WEB");
    assertThat(events).extracting(AuditEvent::actorUsername).containsOnly(TestAdmin.USERNAME);
    assertThat(events.get(0).reason()).isEqualTo("nota 123");
    JsonPath withCostDetails = JsonPath.from(events.get(0).details());
    assertThat(number(withCostDetails.get("before.quantity"))).isEqualByComparingTo("0");
    assertThat(number(withCostDetails.get("after.quantity"))).isEqualByComparingTo("10.000");
    assertThat(number(withCostDetails.get("quantity"))).isEqualByComparingTo("10.000");
    assertThat(number(withCostDetails.get("unitCost"))).isEqualByComparingTo("4.50");
    JsonPath withoutCostDetails = JsonPath.from(events.get(1).details());
    assertThat(number(withoutCostDetails.get("before.quantity"))).isEqualByComparingTo("10.000");
    assertThat(number(withoutCostDetails.get("after.quantity"))).isEqualByComparingTo("12.500");
    assertThat(withoutCostDetails.getString("unitCost"))
        .as("o evento registra a ausência de custo")
        .isNull();

    // O detalhe do estoque (passo 704) mostra o mesmo saldo que a entrada deixou.
    Response detail =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .get(STOCK_DETAIL_PATH.formatted(productId))
            .then()
            .extract()
            .response();

    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(number(detail.jsonPath().getMap("$").get("quantity")))
        .isEqualByComparingTo("12.500");
  }

  @Test
  @DisplayName("quantidade e custo entram nas escalas do projeto com HALF_UP")
  void normalizesQuantityAndCostScales() throws SQLException {
    UUID productId = seedProduct("Escala", "912" + SUFFIX);

    Response receipt =
        postReceipt(productId, newKey(), receiptBody("1.2345", "4.567", "nota"), adminToken());

    assertThat(receipt.statusCode()).as("resposta: %s", receipt.asString()).isEqualTo(201);
    Map<String, Object> body = receipt.jsonPath().getMap("$");
    assertThat(number(body.get("quantity"))).isEqualByComparingTo("1.235");
    assertThat(number(body.get("unitCost"))).isEqualByComparingTo("4.57");
    assertThat(ledgerRows(productId).getFirst().delta()).isEqualByComparingTo("1.235");
    assertThat(costPrice(productId)).isEqualByComparingTo("4.57");
  }

  @Test
  @DisplayName("quantidade ausente, não positiva ou custo negativo respondem 400 e nada é gravado")
  void rejectsInvalidBodyWithoutWritingAnything() throws SQLException {
    UUID productId = seedProduct("Invalido", "913" + SUFFIX);
    String missingQuantityKey = newKey();
    String zeroQuantityKey = newKey();
    String negativeQuantityKey = newKey();
    String negativeCostKey = newKey();

    Response missingQuantity =
        postReceipt(productId, missingQuantityKey, "{\"unitCost\": 4.50}", adminToken());
    assertBadRequest(missingQuantity);

    Response zeroQuantity =
        postReceipt(productId, zeroQuantityKey, receiptBody("0", "4.50", "nota"), adminToken());
    assertBadRequest(zeroQuantity);

    Response negativeQuantity =
        postReceipt(productId, negativeQuantityKey, receiptBody("-1", null, null), adminToken());
    assertBadRequest(negativeQuantity);

    Response negativeCost =
        postReceipt(productId, negativeCostKey, receiptBody("1.000", "-0.01", null), adminToken());
    assertBadRequest(negativeCost);

    // Entrada inválida não chega ao caso de uso: nada de ledger, saldo, custo, evento ou chave.
    assertThat(ledgerRows(productId)).isEmpty();
    assertThat(hasStockRow(productId)).isFalse();
    assertThat(costPrice(productId)).isNull();
    assertThat(eventsOf(productId, "STOCK_RECEIVED")).isEmpty();
    assertThat(countIdempotencyKeys(missingQuantityKey)).isZero();
    assertThat(countIdempotencyKeys(zeroQuantityKey)).isZero();
    assertThat(countIdempotencyKeys(negativeQuantityKey)).isZero();
    assertThat(countIdempotencyKeys(negativeCostKey)).isZero();
  }

  @Test
  @DisplayName("OPERADOR não tem stock.receive: 403 ACCESS_DENIED e nada é gravado")
  void operatorIsDeniedAndNothingIsWritten() throws SQLException {
    String username = "estoque.recebedor." + SUFFIX;
    String token = loginNewUser(username, "OPERADOR");
    UUID productId = seedProduct("Negado", "914" + SUFFIX);
    String key = newKey();

    Response denied = postReceipt(productId, key, receiptBody("5.000", "2.00", "tentativa"), token);

    assertAccessDenied(denied, "stock.receive");

    // O 403 barra no interceptor, antes do caso de uso e da idempotência.
    assertThat(ledgerRows(productId)).isEmpty();
    assertThat(hasStockRow(productId)).isFalse();
    assertThat(costPrice(productId)).isNull();
    assertThat(eventsOf(productId, "STOCK_RECEIVED")).isEmpty();
    assertThat(countIdempotencyKeys(key)).as("o 403 barra antes do IdempotencyGuard").isZero();
  }

  @Test
  @DisplayName("produto inexistente ou soft-deletado responde 404 PRODUCT_NOT_FOUND")
  void rejectsUnknownOrSoftDeletedProduct() throws SQLException {
    Response unknown =
        postReceipt(UUID.randomUUID(), newKey(), receiptBody("1.000", null, null), adminToken());

    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.contentType()).contains("application/problem+json");
    assertThat(unknown.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");

    UUID deleted = seedProduct("Deletado", "915" + SUFFIX);
    QuarkusTransaction.requiringNew().run(() -> productStore.softDelete(deleted));

    String key = newKey();
    Response softDeleted =
        postReceipt(deleted, key, receiptBody("1.000", null, null), adminToken());

    assertThat(softDeleted.statusCode()).as("soft-deletado conta como inexistente").isEqualTo(404);
    assertThat(softDeleted.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(ledgerRows(deleted)).isEmpty();
    assertThat(countIdempotencyKeys(key)).isZero();
  }

  @Test
  @DisplayName(
      "idempotência: mesma chave devolve a resposta gravada sem repetir a entrada; corpo diferente é 409")
  void replaysSameKeyAndRejectsReuseWithDifferentBody() throws SQLException {
    UUID productId = seedProduct("Idempotente", "916" + SUFFIX);
    String key = newKey();
    String body = receiptBody("5.000", "3.25", "nota 9");

    Response first = postReceipt(productId, key, body, adminToken());

    assertThat(first.statusCode()).as("resposta: %s", first.asString()).isEqualTo(201);
    Map<String, Object> firstBody = first.jsonPath().getMap("$");

    Response replay = postReceipt(productId, key, body, adminToken());

    assertThat(replay.statusCode()).as("o replay devolve o mesmo 201").isEqualTo(201);
    assertThat(replay.header(IdempotencyGuard.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("a resposta é a gravada, sem reexecutar a entrada")
        .isEqualTo(firstBody);
    assertThat(ledgerRows(productId)).as("uma entrada, um movimento").hasSize(1);
    assertThat(eventsOf(productId, "STOCK_RECEIVED")).hasSize(1);
    assertThat(stockQuantity(productId))
        .as("o replay não move o saldo de novo")
        .isEqualByComparingTo("5.000");

    Response reused =
        postReceipt(productId, key, receiptBody("7.000", "3.25", "nota 9"), adminToken());

    assertThat(reused.statusCode()).isEqualTo(409);
    assertThat(reused.contentType()).contains("application/problem+json");
    assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(ledgerRows(productId)).as("a chave reutilizada não move o saldo").hasSize(1);

    // Sem o header não há como reconhecer o retry: 400 e nada gravado.
    Response withoutKey =
        postReceipt(productId, null, receiptBody("1.000", null, null), adminToken());

    assertThat(withoutKey.statusCode()).isEqualTo(400);
    assertThat(withoutKey.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(ledgerRows(productId)).hasSize(1);
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), eventos dos produtos, ledger, saldos e produtos do
   * sufixo; depois os eventos, as sessões de auth, os papéis e os usuários OPERADOR criados. O
   * ADMIN da fixture é do {@code IntegrationTestBase}, que roda depois.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      String suffixLike = "%" + SUFFIX;
      execute(
          connection,
          "delete from audit_events where entity_id in (select id from products where name like ?)",
          suffixLike);
      execute(
          connection,
          "delete from stock_movements where product_id in"
              + " (select id from products where name like ?)",
          suffixLike);
      execute(
          connection,
          "delete from product_stocks where product_id in"
              + " (select id from products where name like ?)",
          suffixLike);
      execute(connection, "delete from products where name like ?", suffixLike);
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

  /** Cria o usuário pelo caso de uso (passo 107), loga de verdade e devolve o token da sessão. */
  private String loginNewUser(String username, String roleCode) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)))
            .id();
    userIds.add(id);
    return given()
        .contentType("application/json")
        .header(AuthResource.CLIENT_HEADER, CLIENT)
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(username, PASSWORD))
        .when()
        .post(LOGIN_PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** Corpo da entrada com os campos informados; {@code unitCost}/{@code reason} nulos os omitem. */
  private static String receiptBody(String quantity, String unitCost, String reason) {
    StringBuilder json = new StringBuilder("{\"quantity\": ").append(quantity);
    if (unitCost != null) {
      json.append(", \"unitCost\": ").append(unitCost);
    }
    if (reason != null) {
      json.append(", \"reason\": \"").append(reason).append("\"");
    }
    return json.append("}").toString();
  }

  /** POST da entrada com o corpo cru: é por ele que os casos de forma inválida entram. */
  private static Response postReceipt(UUID productId, String key, String body, String token) {
    var request =
        given().header(AUTHORIZATION, "Bearer " + token).contentType("application/json").body(body);
    if (key != null) {
      request.header(KEY_HEADER, key);
    }
    return request.when().post(RECEIPTS_PATH.formatted(productId)).then().extract().response();
  }

  /** O 403 padrão do {@code RequirePermission}, citando a permissão que faltou. */
  private static void assertAccessDenied(Response response, String permission) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains(permission);
  }

  /** O 400 padrão de entrada inválida: problem+json com {@code VALIDATION_ERROR}. */
  private static void assertBadRequest(Response response) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "estoque.entrada." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Produto da fixture pela porta, sem caso de uso nem auditoria. */
  private UUID seedProduct(String base, String barcode) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        base + "-" + SUFFIX,
                        barcode,
                        null,
                        null,
                        "UN",
                        new BigDecimal("9.90"),
                        null)));
  }

  /** Id do ADMIN da fixture: é o ator gravado no ledger e nos eventos do caminho feliz. */
  private UUID adminUserId() throws SQLException {
    return queryUuid("select id from users where username = ?", TestAdmin.USERNAME);
  }

  /** Id da loja configurada pela porta {@code StoreLookup}, sem importar infrastructure alheia. */
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

  /** Linhas do ledger do produto em ordem de aplicação ({@code created_at}, id do UUIDv7). */
  private List<MovementRow> ledgerRows(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, quantity_delta::text as delta, balance_after::text as balance_after,"
                    + " unit_cost::text as unit_cost, reason, created_by_user_id"
                    + " from stock_movements where product_id = ? order by created_at, id")) {
      statement.setObject(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<MovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new MovementRow(
                  resultSet.getString("type"),
                  decimal(resultSet.getString("delta")),
                  decimal(resultSet.getString("balance_after")),
                  decimal(resultSet.getString("unit_cost")),
                  resultSet.getString("reason"),
                  resultSet.getObject("created_by_user_id", UUID.class)));
        }
        return rows;
      }
    }
  }

  /** Saldo materializado do par (loja, produto); falha quando a linha não existe. */
  private BigDecimal stockQuantity(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("linha de saldo do produto presente").isTrue();
        return decimal(resultSet.getString(1));
      }
    }
  }

  /** Se o produto tem linha de saldo: o 403 e a entrada inválida não podem criar nem isso. */
  private boolean hasStockRow(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select 1 from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  /** Custo do produto como o banco o guardou; nulo enquanto nenhuma entrada informou custo. */
  private BigDecimal costPrice(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select cost_price::text from products where id = ?")) {
      statement.setObject(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto presente").isTrue();
        return decimal(resultSet.getString(1));
      }
    }
  }

  /**
   * Eventos da ação pelo alvo do produto, em ordem de gravação, com {@code details} no texto do
   * jsonb: a conferência é sempre por {@code entity_id} + {@code action}.
   */
  private List<AuditEvent> eventsOf(UUID productId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id::text as entity_id, source, actor_username, reason,"
                    + " details::text as details from audit_events"
                    + " where action = ? and entity_id = ? order by id")) {
      statement.setString(1, action);
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<AuditEvent> events = new ArrayList<>();
        while (resultSet.next()) {
          events.add(
              new AuditEvent(
                  resultSet.getString("entity_type"),
                  resultSet.getString("entity_id"),
                  resultSet.getString("source"),
                  resultSet.getString("actor_username"),
                  resultSet.getString("reason"),
                  resultSet.getString("details")));
        }
        return events;
      }
    }
  }

  /** Registros da chave de idempotência: um por entrada efetivada, zero por tentativa barrada. */
  private int countIdempotencyKeys(String key) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from idempotency_keys where key = ?")) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private UUID queryUuid(String sql, String parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("linha para %s", parameter).isTrue();
        return resultSet.getObject(1, UUID.class);
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

  /**
   * Valor numérico do JSON como BigDecimal: o parser pode devolver Integer, Double ou BigDecimal.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no corpo da resposta").isNotNull();
    return new BigDecimal(value.toString());
  }

  /** Valor do banco no formato textual do numeric; nulo quando a coluna está vazia. */
  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  /**
   * Linha de {@code stock_movements} como o banco a guardou; valor no formato textual do numeric.
   */
  private record MovementRow(
      String type,
      BigDecimal delta,
      BigDecimal balanceAfter,
      BigDecimal unitCost,
      String reason,
      UUID createdByUserId) {}

  /** Linha de {@code audit_events} com o {@code details} no texto do jsonb, pronto para o GPath. */
  private record AuditEvent(
      String entityType,
      String entityId,
      String source,
      String actorUsername,
      String reason,
      String details) {}
}
