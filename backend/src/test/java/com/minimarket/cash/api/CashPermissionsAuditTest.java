package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.api.AuthResource;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fechamento do módulo de caixa (passo 614) contra PostgreSQL real (Dev Services): a suíte
 * consolidada que prova as duas garantias da fase — autorização e auditoria — nas rotas reais de
 * {@code /api/v1/cash-registers} e {@code /api/v1/cash-sessions}.
 *
 * <p>A matriz de permissões usa usuários reais criados pelo {@code CreateUserUseCase} e login de
 * verdade, como no {@code PermissionMatrixTest}: o OPERADOR tem {@code cash.read}, {@code
 * cash.open} e {@code cash.close} — abre (201) e fecha (200) o {@code CAIXA-01} e lê as quatro
 * rotas de leitura (200) — mas leva 403 {@code ACCESS_DENIED} em sangria e suprimento, citando
 * {@code cash.withdrawal} e {@code cash.supply}; o GERENTE tem todas as cinco e opera o caixa
 * inteiro (abrir, suprir, sangrar e fechar), o que prova que as permissões de dinheiro não são
 * exclusividade do ADMIN (seed da {@code V3__rbac.sql}).
 *
 * <p>A auditoria é conferida pelo par {@code entity_id} + {@code action} — nunca por contagem
 * global, que outras linhas do log poderiam inflar: no fluxo completo cada ação gera <em>um</em>
 * evento ({@code CASH_SESSION_OPENED}, {@code CASH_SUPPLY}, {@code CASH_WITHDRAWAL}, {@code
 * CASH_SESSION_CLOSED}) com a sessão em {@code entity_id}, a origem {@code TUI} da requisição
 * autenticada (o cliente do PDV) e os {@code details} essenciais de cada operação. As tentativas
 * negadas do OPERADOR não geram movimento nem evento de operação de caixa: o 403 barra no
 * interceptor, antes do caso de uso e da idempotência — nada de movimento, evento ou chave.
 *
 * <p>As duas rotas de dinheiro são idempotentes por contrato (§8), então cada chamada leva uma
 * {@code Idempotency-Key} nova. O request HTTP comita, então a limpeza no {@code @AfterEach} apaga,
 * na ordem que as FKs exigem, as chaves, os movimentos, as sessões e os eventos das sessões
 * criadas, e depois os eventos, sessões de auth, papéis e usuários OPERADOR/GERENTE — o {@code
 * CAIXA-01} é fixture compartilhada e não é tocado. O {@code TestAdmin} apaga a sessão e o usuário
 * dele depois (as tentativas negadas gravam {@code ACCESS_DENIED} do ator do teste).
 */
@QuarkusTest
class CashPermissionsAuditTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  /** Caixa do seed da V11: existe sempre, é a fixture das duas matrizes. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String CLOSE_PATH = "/api/v1/cash-registers/%s/close";

  private static final String WITHDRAWALS_PATH = "/api/v1/cash-registers/%s/withdrawals";

  private static final String SUPPLIES_PATH = "/api/v1/cash-registers/%s/supplies";

  private static final String CURRENT_SESSION_PATH = "/api/v1/cash-registers/%s/current-session";

  private static final String SESSION_DETAIL_PATH = "/api/v1/cash-sessions/%s";

  private static final String SESSION_SUMMARY_PATH = "/api/v1/cash-sessions/%s/summary";

  private static final String LOGIN_PATH = "/api/v1/auth/login";

  private static final String AUTHORIZATION = "Authorization";

  /** Cliente da sessão dos atores: a TUI é quem opera o caixa, e a origem do evento é dela. */
  private static final String CLIENT = "TUI";

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
      "OPERADOR abre e fecha o CAIXA-01 e lê as rotas de caixa, mas sangria e suprimento são 403")
  void operatorOpensAndClosesButIsDeniedOnMoneyRoutes() throws SQLException {
    String username = "caixa.operador." + SUFFIX;
    String token = loginNewUser(username, "OPERADOR");
    UUID operatorId = userId(username);
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);

    Response opened = open(registerId, newKey(), "100.00", token);

    assertThat(opened.statusCode()).as("cash.open é do OPERADOR").isEqualTo(201);
    UUID sessionId = sessionIdOf(opened);
    Map<String, Object> openedBody = opened.jsonPath().getMap("$");
    assertThat(openedBody.get("status")).isEqualTo("OPEN");
    assertThat(openedBody.get("openedByUserId"))
        .as("quem abriu é o ator do token, não o ADMIN da fixture")
        .isEqualTo(operatorId.toString());

    // Sangria e suprimento: 403 do interceptor, citando a permissão que falta (BR-10, passo 306).
    String withdrawalKey = newKey();
    Response withdrawal =
        movement(WITHDRAWALS_PATH, registerId, withdrawalKey, "10.00", "depósito", token);
    assertAccessDenied(withdrawal, "cash.withdrawal");

    String supplyKey = newKey();
    Response supply = movement(SUPPLIES_PATH, registerId, supplyKey, "10.00", "troco extra", token);
    assertAccessDenied(supply, "cash.supply");

    // O 403 barra antes do caso de uso e da idempotência: nada de movimento, evento ou chave.
    assertThat(countMovements(sessionId, "WITHDRAWAL"))
        .as("a sangria barrada não toca o ledger")
        .isZero();
    assertThat(countMovements(sessionId, "SUPPLY"))
        .as("o suprimento barrado não toca o ledger")
        .isZero();
    assertThat(countEvents(sessionId, "CASH_WITHDRAWAL")).isZero();
    assertThat(countEvents(sessionId, "CASH_SUPPLY")).isZero();
    assertThat(countIdempotencyKeys(withdrawalKey))
        .as("o 403 barra antes do IdempotencyGuard")
        .isZero();
    assertThat(countIdempotencyKeys(supplyKey)).isZero();

    // Leitura: cash.read é do OPERADOR nas quatro rotas de consulta.
    Response listing = get(token, CashRegistersResource.PATH);

    assertThat(listing.statusCode()).as("cash.read é do OPERADOR").isEqualTo(200);
    Map<String, Object> register = registerOf(listing, registerId);

    assertThat(register.get("code")).isEqualTo(SEEDED_REGISTER_CODE);
    assertThat(register.get("status")).as("a sessão aberta aparece na listagem").isEqualTo("OPEN");
    assertThat(register.get("operatorName")).isEqualTo(username);

    Response current = get(token, CURRENT_SESSION_PATH.formatted(registerId));

    assertThat(current.statusCode()).isEqualTo(200);
    Map<String, Object> currentBody = current.jsonPath().getMap("$");

    assertThat(currentBody.get("sessionId")).isEqualTo(sessionId.toString());
    assertThat(money(currentBody, "openingAmount")).isEqualByComparingTo("100.00");
    assertThat(money(currentBody, "expectedAmount"))
        .as("o OPENING entra uma vez só, e nenhum movimento foi gravado")
        .isEqualByComparingTo("100.00");

    Response detail = get(token, SESSION_DETAIL_PATH.formatted(sessionId));

    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(detail.jsonPath().getString("status")).isEqualTo("OPEN");

    Response summary = get(token, SESSION_SUMMARY_PATH.formatted(sessionId));

    assertThat(summary.statusCode()).isEqualTo(200);
    assertThat(money(summary.jsonPath().getMap("$"), "expectedAmount"))
        .isEqualByComparingTo("100.00");

    // Fechamento: cash.close é do OPERADOR, e a conferência sai do servidor (BR-12).
    Response closed = close(registerId, newKey(), "100.00", "conferência do operador", token);

    assertThat(closed.statusCode()).as("cash.close é do OPERADOR").isEqualTo(200);
    Map<String, Object> closedBody = closed.jsonPath().getMap("$");

    assertThat(closedBody.get("id")).isEqualTo(sessionId.toString());
    assertThat(closedBody.get("status")).isEqualTo("CLOSED");
    assertThat(closedBody.get("closedByUserId")).isEqualTo(operatorId.toString());
    assertThat(money(closedBody, "countedAmount")).isEqualByComparingTo("100.00");
    assertThat(money(closedBody, "expectedAmount")).isEqualByComparingTo("100.00");
    assertThat(money(closedBody, "differenceAmount")).isEqualByComparingTo("0.00");

    // Auditoria do OPERADOR: abertura e fechamento no entity_id da sessão, um evento de cada.
    AuditEvent openedEvent = eventOf(sessionId, "CASH_SESSION_OPENED");

    assertSessionEvent(openedEvent, sessionId, username);
    JsonPath openedDetails = JsonPath.from(openedEvent.details());
    assertThat(openedDetails.getString("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(number(openedDetails.get("openingAmount"))).isEqualByComparingTo("100.00");

    AuditEvent closedEvent = eventOf(sessionId, "CASH_SESSION_CLOSED");

    assertSessionEvent(closedEvent, sessionId, username);
    JsonPath closedDetails = JsonPath.from(closedEvent.details());
    assertThat(number(closedDetails.get("countedAmount"))).isEqualByComparingTo("100.00");
    assertThat(number(closedDetails.get("expectedAmount"))).isEqualByComparingTo("100.00");
    assertThat(number(closedDetails.get("differenceAmount"))).isEqualByComparingTo("0.00");

    // As duas tentativas negadas não inventaram evento de operação de caixa para a sessão (o
    // ACCESS_DENIED que o interceptor grava é outro alvo — entity_id nulo).
    assertThat(countEvents(sessionId)).as("só abertura e fechamento no alvo").isEqualTo(2);
  }

  @Test
  @DisplayName(
      "GERENTE opera o caixa inteiro — abrir, suprir, sangrar e fechar — e cada ação vira um evento")
  void managerRunsTheWholeFlowAndEveryActionIsAudited() throws SQLException {
    String username = "caixa.gerente." + SUFFIX;
    String token = loginNewUser(username, "GERENTE");
    UUID managerId = userId(username);
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);

    Response opened = open(registerId, newKey(), "100.00", token);

    assertThat(opened.statusCode()).isEqualTo(201);
    UUID sessionId = sessionIdOf(opened);

    Response supply = movement(SUPPLIES_PATH, registerId, newKey(), "40.00", "troco extra", token);

    assertThat(supply.statusCode()).as("cash.supply não é exclusividade do ADMIN").isEqualTo(201);
    Map<String, Object> supplyBody = supply.jsonPath().getMap("$");

    assertThat(supplyBody.get("type")).isEqualTo("SUPPLY");
    assertThat(money(supplyBody, "amount")).isEqualByComparingTo("40.00");
    assertThat(money(supplyBody, "expectedBefore")).isEqualByComparingTo("100.00");
    assertThat(money(supplyBody, "expectedAfter")).isEqualByComparingTo("140.00");
    assertThat(storedMovement(sessionId, "SUPPLY").amount())
        .as("suprimento entra positivo no ledger")
        .isEqualTo("40.00");
    assertThat(storedMovement(sessionId, "SUPPLY").createdByUserId())
        .as("o movimento é do GERENTE que operou")
        .isEqualTo(managerId);

    Response withdrawal =
        movement(WITHDRAWALS_PATH, registerId, newKey(), "25.00", "depósito bancário", token);

    assertThat(withdrawal.statusCode())
        .as("cash.withdrawal não é exclusividade do ADMIN")
        .isEqualTo(201);
    Map<String, Object> withdrawalBody = withdrawal.jsonPath().getMap("$");

    assertThat(withdrawalBody.get("type")).isEqualTo("WITHDRAWAL");
    assertThat(money(withdrawalBody, "expectedBefore")).isEqualByComparingTo("140.00");
    assertThat(money(withdrawalBody, "expectedAfter")).isEqualByComparingTo("115.00");
    assertThat(storedMovement(sessionId, "WITHDRAWAL").amount())
        .as("sangria entra negativa no ledger")
        .isEqualTo("-25.00");
    assertThat(storedMovement(sessionId, "WITHDRAWAL").createdByUserId()).isEqualTo(managerId);

    // O esperado é do servidor (BR-12): a sessão atual confere com os movimentos que ele somou.
    Response current = get(token, CURRENT_SESSION_PATH.formatted(registerId));

    assertThat(current.statusCode()).as("cash.read é do GERENTE").isEqualTo(200);
    assertThat(money(current.jsonPath().getMap("$"), "expectedAmount"))
        .as("100 de abertura + 40 de suprimento − 25 de sangria")
        .isEqualByComparingTo("115.00");

    Response closed = close(registerId, newKey(), "120.00", "conferência do gerente", token);

    assertThat(closed.statusCode()).as("cash.close também é do GERENTE").isEqualTo(200);
    Map<String, Object> closedBody = closed.jsonPath().getMap("$");

    assertThat(closedBody.get("status")).isEqualTo("CLOSED");
    assertThat(closedBody.get("closedByUserId")).isEqualTo(managerId.toString());
    assertThat(money(closedBody, "countedAmount")).isEqualByComparingTo("120.00");
    assertThat(money(closedBody, "expectedAmount")).isEqualByComparingTo("115.00");
    assertThat(money(closedBody, "differenceAmount"))
        .as("contado 120 − esperado 115")
        .isEqualByComparingTo("5.00");

    // Um evento por ação, todos no entity_id da sessão, com ator, origem e details essenciais.
    AuditEvent openedEvent = eventOf(sessionId, "CASH_SESSION_OPENED");

    assertSessionEvent(openedEvent, sessionId, username);
    JsonPath openedDetails = JsonPath.from(openedEvent.details());
    assertThat(openedDetails.getString("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(number(openedDetails.get("openingAmount"))).isEqualByComparingTo("100.00");

    AuditEvent supplyEvent = eventOf(sessionId, "CASH_SUPPLY");

    assertSessionEvent(supplyEvent, sessionId, username);
    assertThat(supplyEvent.reason())
        .as("o motivo da operação é o rastro humano")
        .isEqualTo("troco extra");
    JsonPath supplyDetails = JsonPath.from(supplyEvent.details());
    assertThat(number(supplyDetails.get("amount"))).isEqualByComparingTo("40.00");
    assertThat(number(supplyDetails.get("expectedBefore"))).isEqualByComparingTo("100.00");
    assertThat(number(supplyDetails.get("expectedAfter"))).isEqualByComparingTo("140.00");
    assertThat(supplyDetails.getString("aboveExpected")).as("o alerta é só da sangria").isNull();

    AuditEvent withdrawalEvent = eventOf(sessionId, "CASH_WITHDRAWAL");

    assertSessionEvent(withdrawalEvent, sessionId, username);
    assertThat(withdrawalEvent.reason()).isEqualTo("depósito bancário");
    JsonPath withdrawalDetails = JsonPath.from(withdrawalEvent.details());
    assertThat(number(withdrawalDetails.get("amount"))).isEqualByComparingTo("25.00");
    assertThat(number(withdrawalDetails.get("expectedBefore"))).isEqualByComparingTo("140.00");
    assertThat(number(withdrawalDetails.get("expectedAfter"))).isEqualByComparingTo("115.00");
    assertThat(withdrawalDetails.getString("aboveExpected")).isEqualTo("false");

    AuditEvent closedEvent = eventOf(sessionId, "CASH_SESSION_CLOSED");

    assertSessionEvent(closedEvent, sessionId, username);
    JsonPath closedDetails = JsonPath.from(closedEvent.details());
    assertThat(number(closedDetails.get("countedAmount"))).isEqualByComparingTo("120.00");
    assertThat(number(closedDetails.get("expectedAmount"))).isEqualByComparingTo("115.00");
    assertThat(number(closedDetails.get("differenceAmount"))).isEqualByComparingTo("5.00");

    assertThat(countEvents(sessionId)).as("quatro operações, quatro eventos").isEqualTo(4);

    // O resumo da sessão fechada traz a mesma conferência que o evento registrou.
    Response summary = get(token, SESSION_SUMMARY_PATH.formatted(sessionId));

    assertThat(summary.statusCode()).isEqualTo(200);
    Map<String, Object> summaryBody = summary.jsonPath().getMap("$");

    assertThat(summaryBody.get("status")).isEqualTo("CLOSED");
    assertThat(money(summaryBody, "openingAmount")).isEqualByComparingTo("100.00");
    assertThat(money(summaryBody, "expectedAmount")).isEqualByComparingTo("115.00");
    assertThat(money(summaryBody, "countedAmount")).isEqualByComparingTo("120.00");
    assertThat(money(summaryBody, "differenceAmount")).isEqualByComparingTo("5.00");
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), movimentos, sessões e eventos das sessões; depois os
   * eventos, as sessões de auth, os papéis e os usuários dos atores criados.
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

  /** Cria o usuário pelo caso de uso (passo 107), loga de verdade e devolve o token da sessão. */
  private String loginNewUser(String username, String roleCode) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)))
            .id();
    userIds.add(id);
    return login(username);
  }

  /** Login real (passo 205) com o cliente TUI explícito, para a origem do evento ser conhecida. */
  private static String login(String username) {
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

  /** POST /open (passo 607) com a chave e o fundo de troco informados. */
  private static Response open(UUID registerId, String key, String openingAmount, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(KEY_HEADER, key)
        .contentType("application/json")
        .body("{\"openingAmount\": %s}".formatted(openingAmount))
        .when()
        .post(OPEN_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** POST /close (passo 612) com a chave e a conferência informadas. */
  private static Response close(
      UUID registerId, String key, String countedAmount, String notes, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(KEY_HEADER, key)
        .contentType("application/json")
        .body("{\"countedAmount\": %s, \"notes\": \"%s\"}".formatted(countedAmount, notes))
        .when()
        .post(CLOSE_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** POST de sangria/suprimento (passos 609/610) com a chave e o valor informados. */
  private static Response movement(
      String path, UUID registerId, String key, String amount, String reason, String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(KEY_HEADER, key)
        .contentType("application/json")
        .body("{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason))
        .when()
        .post(path.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** GET autenticado com o token informado; cada teste confere o status esperado. */
  private static Response get(String token, String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(path)
        .then()
        .extract()
        .response();
  }

  /** O 403 padrão do {@code RequirePermission}, citando a permissão que faltou. */
  private static void assertAccessDenied(Response response, String permission) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains(permission);
  }

  /** Campos comuns dos eventos de caixa: alvo, origem TUI da sessão do PDV e ator do teste. */
  private static void assertSessionEvent(AuditEvent event, UUID sessionId, String username) {
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.entityId()).isEqualTo(sessionId.toString());
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isEqualTo(CLIENT);
    assertThat(event.actorUsername()).isEqualTo(username);
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.permissoes." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Id da sessão da resposta de abertura, rastreado para a limpeza. */
  private UUID sessionIdOf(Response opened) {
    UUID sessionId = UUID.fromString(opened.jsonPath().getString("id"));
    cashSessionIds.add(sessionId);
    return sessionId;
  }

  /** Id do usuário criado pelo teste. */
  private UUID userId(String username) throws SQLException {
    return queryUuid("select id from users where username = ?", username);
  }

  /** A linha do caixa na listagem, pelo id: é por ela que o OPERADOR enxerga o CAIXA-01. */
  private static Map<String, Object> registerOf(Response listing, UUID registerId) {
    List<Map<String, Object>> items = listing.jsonPath().getList("$");
    return items.stream()
        .filter(item -> registerId.toString().equals(item.get("id")))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("caixa %s não apareceu na listagem".formatted(registerId)));
  }

  /** Movimento do tipo informado como o banco o guardou; falha quando não existe. */
  private StoredMovement storedMovement(UUID sessionId, String type) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select amount::text as amount, created_by_user_id from cash_movements"
                    + " where cash_session_id = ? and type = ?")) {
      statement.setObject(1, sessionId);
      statement.setString(2, type);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento %s da sessão %s", type, sessionId).isTrue();
        return new StoredMovement(
            resultSet.getString("amount"), resultSet.getObject("created_by_user_id", UUID.class));
      }
    }
  }

  /**
   * Evento da ação pelo alvo da sessão, com {@code details} no texto do jsonb; falha se houver zero
   * ou mais de um evento para o par — a conferência é sempre por {@code entity_id} + {@code
   * action}.
   */
  private AuditEvent eventOf(UUID sessionId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id::text as entity_id, source, actor_username, reason,"
                    + " details::text as details from audit_events"
                    + " where action = ? and entity_id = ?")) {
      statement.setString(1, action);
      statement.setObject(2, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s da sessão %s", action, sessionId).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("entity_id"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("reason"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma operação, um evento %s", action).isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos a sessão tem, de qualquer ação: só as operações de caixa contam. */
  private int countEvents(UUID sessionId) throws SQLException {
    return queryInt("select count(*) from audit_events where entity_id = ?", sessionId);
  }

  /** Eventos da ação para a sessão: o 403 não pode inventar evento de operação. */
  private int countEvents(UUID sessionId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", sessionId, action);
  }

  /** Movimentos do tipo na sessão: o 403 não pode deixar linha no ledger. */
  private int countMovements(UUID sessionId, String type) throws SQLException {
    return queryInt(
        "select count(*) from cash_movements where cash_session_id = ? and type = ?",
        sessionId,
        type);
  }

  /** Registros da chave de idempotência: um por operação efetivada, zero por tentativa barrada. */
  private int countIdempotencyKeys(String key) throws SQLException {
    return queryInt("select count(*) from idempotency_keys where key = ?", key);
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal money(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  /**
   * Valor numérico do JSON como BigDecimal: o JsonPath do RestAssured devolve decimal como {@code
   * Float}, então o dinheiro do evento é conferido por valor ({@code compareTo}), não pelo texto.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no details do evento").isNotNull();
    return new BigDecimal(value.toString());
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
  private record StoredMovement(String amount, UUID createdByUserId) {}

  /** Linha de {@code audit_events} com o {@code details} no texto do jsonb, pronto para o GPath. */
  private record AuditEvent(
      String entityType,
      String entityId,
      String source,
      String actorUsername,
      String reason,
      String details) {}
}
