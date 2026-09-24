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
 * Ajuste manual de estoque na API (passo 705) contra PostgreSQL real (Dev Services): a rota {@code
 * POST /api/v1/stock/{productId}/adjustments} com o movimento {@code ADJUSTMENT} no ledger, o saldo
 * materializado, o evento {@code STOCK_ADJUSTED} e os erros do contrato.
 *
 * <p>O caminho feliz ajusta para cima e para baixo com o ADMIN da fixture e confere as três
 * verdades: a linha do ledger com {@code balance_after}, o saldo de {@code product_stocks} e o
 * evento com {@code before}/{@code after} do saldo, o motivo em {@code reason} e o ator do token. A
 * matriz de permissão usa um OPERADOR real ({@code CreateUserUseCase} + login de verdade, como no
 * {@code CashPermissionsAuditTest}): {@code stock.read} é dele, mas {@code stock.adjust} não — o
 * interceptor responde 403 {@code ACCESS_DENIED} citando a permissão antes do caso de uso e nada é
 * gravado (nem movimento, nem saldo, nem evento, nem chave de idempotência).
 *
 * <p>Ajuste move estoque, então a rota é idempotente por contrato (§8, passo 607a): cada chamada
 * leva uma {@code Idempotency-Key} nova e o teste cobre o replay (mesma resposta, {@code
 * Idempotency-Replayed: true}, um único movimento) e a reutilização com corpo diferente (409 {@code
 * IDEMPOTENCY_KEY_REUSED}, ainda um movimento). O 422 sai da flag da loja ({@code
 * allow_negative_stock=false}, desligada por SQL e restaurada no {@code @AfterEach} — a loja é
 * compartilhada) e o 404 do produto inexistente ou soft-deletado.
 *
 * <p>O request HTTP comita, então a limpeza no {@code @AfterEach} apaga, na ordem das FKs, as
 * chaves, os eventos dos produtos, o ledger, os saldos e os produtos do sufixo da classe; depois os
 * eventos, as sessões de auth, os papéis e o usuário OPERADOR — o ADMIN da fixture é apagado pelo
 * {@code TestAdmin} depois (as tentativas negadas gravam {@code ACCESS_DENIED} do ator do teste).
 */
@QuarkusTest
class StockAdjustmentResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String AUTHORIZATION = "Authorization";

  /** Rota do ajuste (§9.3, passo 705). */
  private static final String ADJUSTMENTS_PATH = "/api/v1/stock/%s/adjustments";

  /** Rota do detalhe (passo 704): é onde o efeito do ajuste aparece e para onde o 201 aponta. */
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

  private boolean storeFlagFlipped;

  @Test
  @DisplayName(
      "ajuste positivo e negativo: 201, movimento ADJUSTMENT com balance_after, saldo e dois eventos")
  void adjustsUpAndDownWithLedgerAndAudit() throws SQLException {
    UUID productId = seedProduct("Ajuste", "901" + SUFFIX);
    String token = adminToken();
    UUID adminId = adminUserId();

    Response up = adjust(productId, newKey(), "10.000", "contagem de abertura", token);

    assertThat(up.statusCode()).as("resposta: %s", up.asString()).isEqualTo(201);
    assertThat(up.header("Location"))
        .as("o Location aponta para onde o efeito é visível (passo 704)")
        .endsWith(STOCK_DETAIL_PATH.formatted(productId));
    Map<String, Object> upBody = up.jsonPath().getMap("$");
    assertThat(upBody)
        .as("contrato do 201: nem storeId nem entidade do ledger vazam")
        .containsOnlyKeys(
            "movementId", "productId", "quantityDelta", "balanceBefore", "balanceAfter");
    assertThat(upBody.get("productId")).isEqualTo(productId.toString());
    assertThat(number(upBody.get("quantityDelta"))).isEqualByComparingTo("10.000");
    assertThat(number(upBody.get("balanceBefore"))).isEqualByComparingTo("0");
    assertThat(number(upBody.get("balanceAfter"))).isEqualByComparingTo("10.000");

    Response down = adjust(productId, newKey(), "-3.000", "avaria no transporte", token);

    assertThat(down.statusCode()).as("resposta: %s", down.asString()).isEqualTo(201);
    Map<String, Object> downBody = down.jsonPath().getMap("$");
    assertThat(downBody.get("movementId")).isNotEqualTo(upBody.get("movementId"));
    assertThat(number(downBody.get("quantityDelta"))).isEqualByComparingTo("-3.000");
    assertThat(number(downBody.get("balanceBefore"))).isEqualByComparingTo("10.000");
    assertThat(number(downBody.get("balanceAfter"))).isEqualByComparingTo("7.000");

    // Ledger: um movimento ADJUSTMENT por chamada, com o delta assinado e o balance_after correto.
    List<MovementRow> ledger = ledgerRows(productId);

    assertThat(ledger).hasSize(2);
    assertThat(ledger).extracting(MovementRow::type).containsOnly("ADJUSTMENT");
    assertThat(ledger.get(0).delta()).isEqualByComparingTo("10.000");
    assertThat(ledger.get(0).balanceAfter()).isEqualByComparingTo("10.000");
    assertThat(ledger.get(0).reason()).isEqualTo("contagem de abertura");
    assertThat(ledger.get(1).delta()).isEqualByComparingTo("-3.000");
    assertThat(ledger.get(1).balanceAfter()).isEqualByComparingTo("7.000");
    assertThat(ledger.get(1).reason()).isEqualTo("avaria no transporte");
    assertThat(ledger)
        .extracting(MovementRow::createdByUserId)
        .as("o movimento é do ator do token")
        .containsOnly(adminId);

    // O saldo materializado é o balance_after do último movimento.
    assertThat(stockQuantity(productId)).isEqualByComparingTo("7.000");

    // Auditoria: um STOCK_ADJUSTED por ajuste, com before/after do saldo e o motivo.
    List<AuditEvent> events = eventsOf(productId, "STOCK_ADJUSTED");

    assertThat(events).hasSize(2);
    assertThat(events).extracting(AuditEvent::entityType).containsOnly("PRODUCT");
    assertThat(events)
        .extracting(AuditEvent::source)
        .as("origem da sessão autenticada (o ADMIN da fixture loga sem X-Client: WEB), não SYSTEM")
        .containsOnly("WEB");
    assertThat(events).extracting(AuditEvent::actorUsername).containsOnly(TestAdmin.USERNAME);
    assertThat(events.get(0).reason()).isEqualTo("contagem de abertura");
    JsonPath upDetails = JsonPath.from(events.get(0).details());
    assertThat(number(upDetails.get("before.quantity"))).isEqualByComparingTo("0");
    assertThat(number(upDetails.get("after.quantity"))).isEqualByComparingTo("10.000");
    assertThat(events.get(1).reason()).isEqualTo("avaria no transporte");
    JsonPath downDetails = JsonPath.from(events.get(1).details());
    assertThat(number(downDetails.get("before.quantity"))).isEqualByComparingTo("10.000");
    assertThat(number(downDetails.get("after.quantity"))).isEqualByComparingTo("7.000");

    // O detalhe do estoque (passo 704) mostra o mesmo saldo que o ajuste deixou.
    Response detail =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .get(STOCK_DETAIL_PATH.formatted(productId))
            .then()
            .extract()
            .response();

    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(number(detail.jsonPath().getMap("$").get("quantity"))).isEqualByComparingTo("7.000");
  }

  @Test
  @DisplayName("delta ausente, delta zero ou motivo vazio respondem 400 e nada é gravado")
  void rejectsInvalidBodyWithoutWritingAnything() throws SQLException {
    UUID productId = seedProduct("Invalido", "902" + SUFFIX);
    String missingDeltaKey = newKey();
    String zeroDeltaKey = newKey();
    String blankReasonKey = newKey();

    Response missingDelta =
        postAdjustment(productId, missingDeltaKey, "{\"reason\": \"contagem\"}", adminToken());
    assertBadRequest(missingDelta);

    Response zeroDelta = adjust(productId, zeroDeltaKey, "0", "contagem", adminToken());
    assertBadRequest(zeroDelta);

    Response blankReason = adjust(productId, blankReasonKey, "1.000", "   ", adminToken());
    assertBadRequest(blankReason);

    // Entrada inválida não chega ao caso de uso: nada de ledger, saldo, evento ou chave.
    assertThat(ledgerRows(productId)).isEmpty();
    assertThat(hasStockRow(productId)).isFalse();
    assertThat(eventsOf(productId, "STOCK_ADJUSTED")).isEmpty();
    assertThat(countIdempotencyKeys(missingDeltaKey)).isZero();
    assertThat(countIdempotencyKeys(zeroDeltaKey)).isZero();
    assertThat(countIdempotencyKeys(blankReasonKey)).isZero();
  }

  @Test
  @DisplayName("OPERADOR não tem stock.adjust: 403 ACCESS_DENIED e nada é gravado")
  void operatorIsDeniedAndNothingIsWritten() throws SQLException {
    String username = "estoque.operador." + SUFFIX;
    String token = loginNewUser(username, "OPERADOR");
    UUID productId = seedProduct("Negado", "903" + SUFFIX);
    String key = newKey();

    Response denied = adjust(productId, key, "5.000", "tentativa do operador", token);

    assertAccessDenied(denied, "stock.adjust");

    // O 403 barra no interceptor, antes do caso de uso e da idempotência.
    assertThat(ledgerRows(productId)).isEmpty();
    assertThat(hasStockRow(productId)).isFalse();
    assertThat(eventsOf(productId, "STOCK_ADJUSTED")).isEmpty();
    assertThat(countIdempotencyKeys(key)).as("o 403 barra antes do IdempotencyGuard").isZero();
  }

  @Test
  @DisplayName(
      "idempotência: mesma chave devolve a resposta gravada sem repetir o ajuste; corpo diferente é 409")
  void replaysSameKeyAndRejectsReuseWithDifferentBody() throws SQLException {
    UUID productId = seedProduct("Idempotente", "904" + SUFFIX);
    String key = newKey();

    Response first = adjust(productId, key, "5.000", "contagem", adminToken());

    assertThat(first.statusCode()).as("resposta: %s", first.asString()).isEqualTo(201);
    Map<String, Object> firstBody = first.jsonPath().getMap("$");

    Response replay = adjust(productId, key, "5.000", "contagem", adminToken());

    assertThat(replay.statusCode()).as("o replay devolve o mesmo 201").isEqualTo(201);
    assertThat(replay.header(IdempotencyGuard.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("a resposta é a gravada, sem reexecutar o ajuste")
        .isEqualTo(firstBody);
    assertThat(ledgerRows(productId)).as("um ajuste, um movimento").hasSize(1);
    assertThat(eventsOf(productId, "STOCK_ADJUSTED")).hasSize(1);

    Response reused = adjust(productId, key, "7.000", "contagem", adminToken());

    assertThat(reused.statusCode()).isEqualTo(409);
    assertThat(reused.contentType()).contains("application/problem+json");
    assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(ledgerRows(productId)).as("a chave reutilizada não move o saldo").hasSize(1);

    // Sem o header não há como reconhecer o retry: 400 e nada gravado.
    Response withoutKey =
        postAdjustment(
            productId, null, "{\"quantityDelta\": 1.000, \"reason\": \"sem chave\"}", adminToken());

    assertThat(withoutKey.statusCode()).isEqualTo(400);
    assertThat(withoutKey.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(ledgerRows(productId)).hasSize(1);
  }

  @Test
  @DisplayName(
      "com allow_negative_stock=false, ajuste que deixaria o saldo negativo responde 422 e nada muda")
  void rejectsAdjustmentThatWouldGoNegativeWhenStoreForbidsIt() throws SQLException {
    setAllowNegativeStock(false);
    UUID productId = seedProduct("SemNegativo", "905" + SUFFIX);

    Response up = adjust(productId, newKey(), "5.000", "contagem de abertura", adminToken());
    assertThat(up.statusCode()).as("resposta: %s", up.asString()).isEqualTo(201);

    String key = newKey();
    Response denied = adjust(productId, key, "-6.000", "quebra", adminToken());

    assertThat(denied.statusCode()).as("BR-09: a loja não permite saldo negativo").isEqualTo(422);
    assertThat(denied.contentType()).contains("application/problem+json");
    assertThat(denied.jsonPath().getString("code")).isEqualTo("INSUFFICIENT_STOCK");

    // A exceção derruba a transação: nem o saldo nem o ledger mudam, e o erro não vira chave.
    assertThat(stockQuantity(productId)).isEqualByComparingTo("5.000");
    assertThat(ledgerRows(productId)).hasSize(1);
    assertThat(eventsOf(productId, "STOCK_ADJUSTED")).hasSize(1);
    assertThat(countIdempotencyKeys(key)).isZero();
  }

  @Test
  @DisplayName("produto inexistente ou soft-deletado responde 404 PRODUCT_NOT_FOUND")
  void rejectsUnknownOrSoftDeletedProduct() throws SQLException {
    Response unknown = adjust(UUID.randomUUID(), newKey(), "1.000", "contagem", adminToken());

    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.contentType()).contains("application/problem+json");
    assertThat(unknown.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");

    UUID deleted = seedProduct("Deletado", "906" + SUFFIX);
    QuarkusTransaction.requiringNew().run(() -> productStore.softDelete(deleted));

    String key = newKey();
    Response softDeleted = adjust(deleted, key, "1.000", "contagem", adminToken());

    assertThat(softDeleted.statusCode()).as("soft-deletado conta como inexistente").isEqualTo(404);
    assertThat(softDeleted.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(ledgerRows(deleted)).isEmpty();
    assertThat(countIdempotencyKeys(key)).isZero();
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
    if (storeFlagFlipped) {
      setAllowNegativeStock(true);
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

  /** POST do ajuste com a chave, o delta e o motivo informados; {@code key} nulo omite o header. */
  private static Response adjust(
      UUID productId, String key, String quantityDelta, String reason, String token) {
    return postAdjustment(
        productId,
        key,
        "{\"quantityDelta\": %s, \"reason\": \"%s\"}".formatted(quantityDelta, reason),
        token);
  }

  /** POST do ajuste com o corpo cru: é por ele que os casos de forma inválida entram. */
  private static Response postAdjustment(UUID productId, String key, String body, String token) {
    var request =
        given().header(AUTHORIZATION, "Bearer " + token).contentType("application/json").body(body);
    if (key != null) {
      request.header(KEY_HEADER, key);
    }
    return request.when().post(ADJUSTMENTS_PATH.formatted(productId)).then().extract().response();
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
    String key = "estoque.ajuste." + SUFFIX + "." + UUID.randomUUID();
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

  /**
   * Desliga a flag da loja direto no banco (não há porta de escrita da loja no MVP) e marca para o
   * {@code @AfterEach} restaurar — a loja é compartilhada com os outros testes do fork.
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
                    + " reason, created_by_user_id from stock_movements where product_id = ?"
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
        return new BigDecimal(resultSet.getString(1));
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

  /** Registros da chave de idempotência: um por ajuste efetivado, zero por tentativa barrada. */
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

  /**
   * Linha de {@code stock_movements} como o banco a guardou; valor no formato textual do numeric.
   */
  private record MovementRow(
      String type,
      BigDecimal delta,
      BigDecimal balanceAfter,
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
