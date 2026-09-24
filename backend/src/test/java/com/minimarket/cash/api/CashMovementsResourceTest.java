package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sangria e suprimento na API (passo 610) contra PostgreSQL real (Dev Services): as duas rotas de
 * movimento de dinheiro do caixa com o ADMIN da fixture e com OPERADOR/GERENTE criados pelo caminho
 * de aplicação e login de verdade, como no {@code CatalogPermissionsAuditTest} — é o que fecha o
 * aceite de permissão do passo 609 (OPERADOR tem {@code cash.open}, mas não {@code
 * cash.withdrawal}/{@code cash.supply}; o GERENTE tem as duas). O 401 sem token é do {@code
 * RouteSecurityTest}; a regra de cada caso de uso tem unitário próprio.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (movimento assinado,
 * evento de auditoria e registro de idempotência) e limpa tudo o que comitou ao final — chaves,
 * movimentos, sessões, eventos, sessões de auth, papéis e usuários, nessa ordem, porque o banco é
 * compartilhado, o caixa do seed é o mesmo de outros testes e as FKs são {@code restrict}. O {@link
 * TestAdmin} apaga as sessões e o usuário dele depois dos {@code @AfterEach} desta classe.
 */
@QuarkusTest
class CashMovementsResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String WITHDRAWALS_PATH = "/api/v1/cash-registers/%s/withdrawals";

  private static final String SUPPLIES_PATH = "/api/v1/cash-registers/%s/supplies";

  private static final String CURRENT_SESSION_PATH = "/api/v1/cash-registers/%s/current-session";

  /** Caixa do seed da V11: existe sempre, é a fixture dos movimentos. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";

  /** Caso de uso da criação de usuário (passo 107): os atores da matriz são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  @Test
  @DisplayName(
      "sangria: 201 com movimento negativo no banco e o esperado atualizado na sessão atual")
  void withdrawalMovesMoneyOutOfTheRegister() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00", adminToken());

    Response response =
        movement(
            WITHDRAWALS_PATH, registerId, newKey(), "30.00", "depósito bancário", adminToken());

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.contentType()).contains("application/json");
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(body)
        .as("contrato do movimento: nem storeId, nem id de movimento")
        .containsOnlyKeys(
            "sessionId",
            "type",
            "amount",
            "reason",
            "expectedBefore",
            "expectedAfter",
            "aboveExpected");
    assertThat(body.get("sessionId")).isEqualTo(sessionId.toString());
    assertThat(body.get("type")).isEqualTo("WITHDRAWAL");
    assertThat(money(body, "amount"))
        .as("o corpo traz o valor informado, positivo")
        .isEqualByComparingTo("30.00");
    assertThat(body.get("reason")).isEqualTo("depósito bancário");
    assertThat(money(body, "expectedBefore")).isEqualByComparingTo("100.00");
    assertThat(money(body, "expectedAfter")).isEqualByComparingTo("70.00");
    assertThat(body.get("aboveExpected")).isEqualTo(false);
    assertThat(URI.create(response.getHeader("Location")).getPath())
        .as("o 201 aponta para a sessão atual, onde o efeito aparece")
        .isEqualTo("/api/v1/cash-registers/%s/current-session".formatted(registerId));

    StoredMovement movement = storedMovement(sessionId, "WITHDRAWAL");

    assertThat(movement.amount()).as("sangria entra negativa no ledger").isEqualTo("-30.00");
    assertThat(movement.reason()).isEqualTo("depósito bancário");
    assertThat(movement.createdByUserId()).isEqualTo(adminUserId());

    AuditEvent event = auditEvent(sessionId, "CASH_WITHDRAWAL");

    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.source())
        .as("requisição autenticada, não operação de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);
    assertThat(event.reason()).isEqualTo("depósito bancário");
    assertThat(JsonPath.from(event.details()).getString("aboveExpected")).isEqualTo("false");

    Map<String, Object> current = currentSession(registerId, adminToken()).jsonPath().getMap("$");

    assertThat(money(current, "expectedAmount"))
        .as("100 − 30 no servidor")
        .isEqualByComparingTo("70.00");
    assertThat(total(current, "WITHDRAWAL")).isEqualByComparingTo("-30.00");
  }

  @Test
  @DisplayName("suprimento: 201 com movimento positivo no banco e auditoria CASH_SUPPLY")
  void supplyMovesMoneyIntoTheRegister() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "50.00", adminToken());

    Response response =
        movement(SUPPLIES_PATH, registerId, newKey(), "20.00", "troco extra", adminToken());

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("sessionId")).isEqualTo(sessionId.toString());
    assertThat(body.get("type")).isEqualTo("SUPPLY");
    assertThat(money(body, "amount")).isEqualByComparingTo("20.00");
    assertThat(body.get("reason")).isEqualTo("troco extra");
    assertThat(money(body, "expectedBefore")).isEqualByComparingTo("50.00");
    assertThat(money(body, "expectedAfter")).isEqualByComparingTo("70.00");
    assertThat(body.get("aboveExpected"))
        .as("o alerta acima do esperado é só da sangria")
        .isEqualTo(false);

    StoredMovement movement = storedMovement(sessionId, "SUPPLY");

    assertThat(movement.amount()).as("suprimento entra positivo no ledger").isEqualTo("20.00");
    assertThat(movement.reason()).isEqualTo("troco extra");
    assertThat(movement.createdByUserId()).isEqualTo(adminUserId());

    AuditEvent event = auditEvent(sessionId, "CASH_SUPPLY");
    JsonPath details = JsonPath.from(event.details());

    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.reason()).isEqualTo("troco extra");
    assertThat(money(details.getMap("$"), "amount")).isEqualByComparingTo("20.00");
    assertThat(money(details.getMap("$"), "expectedBefore")).isEqualByComparingTo("50.00");
    assertThat(money(details.getMap("$"), "expectedAfter")).isEqualByComparingTo("70.00");
    assertThat(details.getString("aboveExpected"))
        .as("evento do suprimento não tem alerta")
        .isNull();

    Map<String, Object> current = currentSession(registerId, adminToken()).jsonPath().getMap("$");

    assertThat(money(current, "expectedAmount")).isEqualByComparingTo("70.00");
    assertThat(total(current, "SUPPLY")).isEqualByComparingTo("20.00");
  }

  @Test
  @DisplayName("sangria acima do esperado: 201 com aboveExpected e o movimento não é bloqueado")
  void withdrawalAboveExpectedAlertsWithoutBlocking() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "20.00", adminToken());

    Response response =
        movement(WITHDRAWALS_PATH, registerId, newKey(), "50.00", "fornecedor", adminToken());

    assertThat(response.statusCode()).as("acima do esperado é alerta, não bloqueio").isEqualTo(201);
    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("aboveExpected")).isEqualTo(true);
    assertThat(money(body, "expectedAfter")).isEqualByComparingTo("-30.00");
    assertThat(storedMovement(sessionId, "WITHDRAWAL").amount()).isEqualTo("-50.00");
    assertThat(
            JsonPath.from(auditEvent(sessionId, "CASH_WITHDRAWAL").details())
                .getString("aboveExpected"))
        .isEqualTo("true");
  }

  @Test
  @DisplayName(
      "mesma Idempotency-Key: replay com o mesmo corpo e um só movimento, sem duplicar auditoria")
  void replaysSameKeyWithoutDuplicatingTheMovement() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00", adminToken());
    String key = newKey();

    Response first =
        movement(WITHDRAWALS_PATH, registerId, key, "40.00", "depósito bancário", adminToken());
    assertThat(first.statusCode()).isEqualTo(201);

    Response replay =
        movement(WITHDRAWALS_PATH, registerId, key, "40.00", "depósito bancário", adminToken());

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 201").isEqualTo(201);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem sangrar de novo")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));

    assertThat(countMovements(sessionId, "WITHDRAWAL")).as("uma sangria, não duas").isEqualTo(1);
    assertThat(countAuditEvents(sessionId, "CASH_WITHDRAWAL"))
        .as("um evento, não dois")
        .isEqualTo(1);
    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "sem Idempotency-Key nas duas rotas: 400 IDEMPOTENCY_KEY_REQUIRED sem mover dinheiro")
  void rejectsMissingIdempotencyKey() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00", adminToken());

    for (String path : List.of(WITHDRAWALS_PATH, SUPPLIES_PATH)) {
      Response response = movement(path, registerId, null, "10.00", "motivo", adminToken());

      assertThat(response.statusCode()).as("%s sem chave", path).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }
    assertThat(countMovements(sessionId, "WITHDRAWAL")).isZero();
    assertThat(countMovements(sessionId, "SUPPLY")).isZero();
  }

  @Test
  @DisplayName("valor ausente/zero ou motivo em branco nas duas rotas: 400 VALIDATION_ERROR")
  void rejectsInvalidBody() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00", adminToken());

    for (String path : List.of(WITHDRAWALS_PATH, SUPPLIES_PATH)) {
      for (String body :
          List.of("{}", "{\"amount\": 0, \"reason\": \"motivo\"}", "{\"amount\": 10.00}")) {
        Response response = movementWithBody(path, registerId, newKey(), body, adminToken());

        assertThat(response.statusCode()).as("%s com corpo %s", path, body).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(response.jsonPath().getList("errors"))
            .as("a validação diz qual campo falhou")
            .isNotEmpty();
      }
    }
    assertThat(countMovements(sessionId, "WITHDRAWAL")).isZero();
    assertThat(countMovements(sessionId, "SUPPLY")).isZero();
  }

  @Test
  @DisplayName("caixa fechado e caixa inexistente nas duas rotas: 404 CASH_SESSION_NOT_OPEN")
  void rejectsRegisterWithoutOpenSession() {
    UUID closedRegister = registerId();

    for (String path : List.of(WITHDRAWALS_PATH, SUPPLIES_PATH)) {
      for (UUID register : List.of(closedRegister, UUID.randomUUID())) {
        Response response = movement(path, register, newKey(), "10.00", "motivo", adminToken());

        assertThat(response.statusCode()).as("%s no caixa %s", path, register).isEqualTo(404);
        assertThat(response.contentType()).contains("application/problem+json");
        assertThat(response.jsonPath().getString("code")).isEqualTo("CASH_SESSION_NOT_OPEN");
      }
    }
  }

  @Test
  @DisplayName(
      "OPERADOR abre o caixa mas não sangra nem supre: 403 ACCESS_DENIED nas duas rotas, sem efeito")
  void deniesOperatorOnMoneyRoutes() throws SQLException {
    String username = "caixa.operador." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    String token = login(username);
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00", token);
    String withdrawalKey = newKey();
    String supplyKey = newKey();

    Response withdrawal =
        movement(WITHDRAWALS_PATH, registerId, withdrawalKey, "10.00", "depósito", token);

    assertAccessDenied(withdrawal, "cash.withdrawal");

    Response supply = movement(SUPPLIES_PATH, registerId, supplyKey, "10.00", "troco extra", token);

    assertAccessDenied(supply, "cash.supply");

    assertThat(countMovements(sessionId, "WITHDRAWAL"))
        .as("o 403 barra antes do caso de uso")
        .isZero();
    assertThat(countMovements(sessionId, "SUPPLY")).isZero();
    assertThat(countAuditEvents(sessionId, "CASH_WITHDRAWAL")).isZero();
    assertThat(countAuditEvents(sessionId, "CASH_SUPPLY")).isZero();
    assertThat(countIdempotencyKeys(withdrawalKey))
        .as("o 403 barra antes da idempotência")
        .isZero();
    assertThat(countIdempotencyKeys(supplyKey)).isZero();
  }

  @Test
  @DisplayName("GERENTE sangra e supre: 201 nas duas rotas, com ator e auditoria dele")
  void allowsManagerOnMoneyRoutes() throws SQLException {
    String username = "caixa.gerente." + SUFFIX;
    createUser(username, List.of("GERENTE"));
    String token = login(username);
    UUID managerId = userId(username);
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00", token);

    Response supply = movement(SUPPLIES_PATH, registerId, newKey(), "30.00", "troco extra", token);

    assertThat(supply.statusCode()).as("cash.supply não é exclusividade do ADMIN").isEqualTo(201);
    assertThat(supply.jsonPath().getString("type")).isEqualTo("SUPPLY");
    assertThat(storedMovement(sessionId, "SUPPLY").createdByUserId()).isEqualTo(managerId);

    Response withdrawal =
        movement(WITHDRAWALS_PATH, registerId, newKey(), "10.00", "depósito", token);

    assertThat(withdrawal.statusCode())
        .as("cash.withdrawal não é exclusividade do ADMIN")
        .isEqualTo(201);
    assertThat(withdrawal.jsonPath().getString("type")).isEqualTo("WITHDRAWAL");
    assertThat(storedMovement(sessionId, "WITHDRAWAL").createdByUserId()).isEqualTo(managerId);

    AuditEvent supplyEvent = auditEvent(sessionId, "CASH_SUPPLY");

    assertThat(supplyEvent.actorUsername()).as("o evento registra quem operou").isEqualTo(username);
    assertThat(supplyEvent.source()).isNotEqualTo("SYSTEM");
    assertThat(auditEvent(sessionId, "CASH_WITHDRAWAL").actorUsername()).isEqualTo(username);

    Map<String, Object> current = currentSession(registerId, token).jsonPath().getMap("$");

    assertThat(money(current, "expectedAmount"))
        .as("100 + 30 − 10, recalculado pelo servidor")
        .isEqualByComparingTo("120.00");
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), movimentos, sessões, eventos, sessões de auth, papéis
   * e usuários, nessa ordem.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
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
  private UUID open(UUID registerId, String openingAmount, String token) {
    String key = newKey();
    Response response =
        given()
            .header("Authorization", "Bearer " + token)
            .header(KEY_HEADER, key)
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

  /** POST de sangria/suprimento com a chave e o valor informados; chave nula omite o header. */
  private static Response movement(
      String path, UUID registerId, String key, String amount, String reason, String token) {
    return movementWithBody(
        path,
        registerId,
        key,
        "{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason),
        token);
  }

  /** POST de sangria/suprimento com o corpo cru; chave nula é o cenário do header ausente. */
  private static Response movementWithBody(
      String path, UUID registerId, String key, String body, String token) {
    RequestSpecification request =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .body(body);
    if (key != null) {
      request = request.header(KEY_HEADER, key);
    }
    return request.when().post(path.formatted(registerId)).then().extract().response();
  }

  /** GET /current-session com o token informado. */
  private static Response currentSession(UUID registerId, String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get(CURRENT_SESSION_PATH.formatted(registerId))
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.movimento." + SUFFIX + "." + UUID.randomUUID();
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

  /** Login pela API (passo 205), sem caixa no corpo, e devolve o token em claro da sessão nova. */
  private static String login(String username) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(username, PASSWORD))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** Id do caixa do seed lido da própria listagem: é a API quem o expõe (passo 602). */
  private UUID registerId() {
    Response listing =
        asAdmin()
            .when()
            .get(CashRegistersResource.PATH)
            .then()
            .statusCode(200)
            .extract()
            .response();
    List<Map<String, Object>> items = listing.jsonPath().getList("$");
    return items.stream()
        .filter(item -> SEEDED_REGISTER_CODE.equals(item.get("code")))
        .findFirst()
        .map(item -> UUID.fromString((String) item.get("id")))
        .orElseThrow(
            () -> new AssertionError("caixa %s não apareceu".formatted(SEEDED_REGISTER_CODE)));
  }

  /** O 403 padrão do {@code RequirePermission}, citando a permissão que faltou. */
  private static void assertAccessDenied(Response response, String permission) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains(permission);
  }

  /** Id do ADMIN da fixture: a FK de {@code created_by_user_id} aponta para o dono do token. */
  private UUID adminUserId() throws SQLException {
    return queryUuid("select id from users where username = ?", TestAdmin.USERNAME);
  }

  /** Id do usuário criado pelo teste. */
  private UUID userId(String username) throws SQLException {
    return queryUuid("select id from users where username = ?", username);
  }

  /** Movimento do tipo informado como o banco o guardou; falha quando não existe. */
  private StoredMovement storedMovement(UUID sessionId, String type) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select amount::text as amount, reason, created_by_user_id from cash_movements"
                    + " where cash_session_id = ? and type = ?")) {
      statement.setObject(1, sessionId);
      statement.setString(2, type);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento %s da sessão %s", type, sessionId).isTrue();
        return new StoredMovement(
            resultSet.getString("amount"),
            resultSet.getString("reason"),
            resultSet.getObject("created_by_user_id", UUID.class));
      }
    }
  }

  /**
   * Evento da ação para a sessão, com {@code details} no texto do jsonb; falha se houver zero ou
   * mais de um — a conferência é sempre por {@code entity_id} + {@code action}.
   */
  private AuditEvent auditEvent(UUID sessionId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, source, actor_username, reason, details::text as details"
                    + " from audit_events where entity_id = ? and action = ?")) {
      statement.setObject(1, sessionId);
      statement.setString(2, action);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s da sessão %s", action, sessionId).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("reason"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma operação, um evento %s", action).isFalse();
        return event;
      }
    }
  }

  /** Movimentos do tipo na sessão: o replay e o 403 não podem deixar linha a mais. */
  private int countMovements(UUID sessionId, String type) throws SQLException {
    return queryInt(
        "select count(*) from cash_movements where cash_session_id = ? and type = ?",
        sessionId,
        type);
  }

  /** Eventos da ação para a sessão: um por operação efetivada. */
  private int countAuditEvents(UUID sessionId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", sessionId, action);
  }

  /** Registros da chave de idempotência: um por operação, não um por tentativa. */
  private int countIdempotencyKeys(String key) throws SQLException {
    return queryInt("select count(*) from idempotency_keys where key = ?", key);
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal money(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  /** Total de um tipo de movimento no mapa {@code totalsByType}, como número do JSON. */
  @SuppressWarnings("unchecked")
  private static BigDecimal total(Map<String, Object> body, String type) {
    Map<String, Object> totals = (Map<String, Object>) body.get("totalsByType");
    assertThat(totals).as("totalsByType no corpo").isNotNull();
    return new BigDecimal(String.valueOf(totals.get(type)));
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

  /**
   * Linha de {@code cash_movements} como o banco a guardou; valor no formato textual do numeric.
   */
  private record StoredMovement(String amount, String reason, UUID createdByUserId) {}

  /** Linha de {@code audit_events} com o {@code details} no texto do jsonb, pronto para o GPath. */
  private record AuditEvent(
      String entityType, String source, String actorUsername, String reason, String details) {}
}
