package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.CashSessionStore;
import com.minimarket.cash.application.NewCashMovement;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consulta da sessão atual do caixa na API (passo 608) contra PostgreSQL real (Dev Services): a
 * rota de verdade, com o ADMIN da fixture e com usuário sem permissão criado pelo caminho de
 * aplicação. O 401 sem token é do {@code RouteSecurityTest}; o cálculo do saldo esperado é do
 * domínio e do caso de uso, cobertos pelos testes deles.
 *
 * <p>Sangria e suprimento entram pela porta {@link CashSessionStore#insertMovement} em transação
 * própria, como no {@code CashSessionLockTest}: sem casos de uso de sangria/suprimento ainda
 * (passos 609/610) e sem passar pela API, é o caminho que o teste tem para montar o cenário. O
 * request HTTP comita, então o teste confere o JSON e limpa tudo o que comitou no
 * {@code @AfterEach}: chaves de idempotência, movimentos, sessões, eventos, sessões de auth, papéis
 * e usuários — o banco é compartilhado e as FKs são {@code restrict}. O {@link TestAdmin} apaga o
 * usuário dele depois.
 */
@QuarkusTest
class CurrentCashSessionResourceTest extends IntegrationTestBase {

  private static final String CURRENT_SESSION_PATH = "/api/v1/cash-registers/%s/current-session";

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da consulta. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";

  @Inject CashSessionStore cashSessionStore;

  @Inject CreateUserUseCase createUserUseCase;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  @Test
  @DisplayName("caixa fechado e caixa inexistente: 404 CASH_SESSION_NOT_OPEN em problem+json")
  void rejectsRegisterWithoutOpenSession() {
    UUID closedRegister = registerId();

    Response closed = currentSession(closedRegister, adminToken());

    assertThat(closed.statusCode()).isEqualTo(404);
    assertThat(closed.contentType()).contains("application/problem+json");
    assertThat(closed.jsonPath().getString("code")).isEqualTo("CASH_SESSION_NOT_OPEN");
    assertThat(closed.jsonPath().getString("title")).isEqualTo("Sessão de caixa não aberta");

    Response unknown = currentSession(UUID.randomUUID(), adminToken());

    assertThat(unknown.statusCode())
        .as("caixa inexistente cai no mesmo 404 do caixa fechado")
        .isEqualTo(404);
    assertThat(unknown.jsonPath().getString("code")).isEqualTo("CASH_SESSION_NOT_OPEN");
  }

  @Test
  @DisplayName(
      "depois do POST /open: 200 com esperado igual à abertura e o OPENING sem contar duas vezes")
  void reportsSessionOpenedByApi() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "150.00");

    Response response = currentSession(registerId, adminToken());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(body)
        .as("contrato da sessão atual: nem storeId, nem version, nem campos de fechamento")
        .containsOnlyKeys(
            "sessionId",
            "cashRegisterId",
            "status",
            "openedAt",
            "openedByUserId",
            "openingAmount",
            "expectedAmount",
            "totalsByType");
    assertThat(body.get("sessionId")).isEqualTo(sessionId.toString());
    assertThat(body.get("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(body.get("openedAt")).isNotNull();
    assertThat(body.get("openedByUserId")).isEqualTo(adminUserId().toString());
    assertThat(money(body, "openingAmount")).isEqualByComparingTo("150.00");
    assertThat(money(body, "expectedAmount"))
        .as("a abertura entra uma vez só, apesar do movimento OPENING no ledger")
        .isEqualByComparingTo("150.00");

    Map<String, Object> totals = totalsByType(body);
    assertThat(totals)
        .as("os quatro tipos sempre aparecem, os sem movimento zerados")
        .containsOnlyKeys("OPENING", "SALE", "SUPPLY", "WITHDRAWAL");
    assertThat(total(totals, "OPENING"))
        .as("o movimento OPENING do ledger é o único movimento da sessão")
        .isEqualByComparingTo("150.00");
    assertThat(total(totals, "SALE")).isEqualByComparingTo("0.00");
    assertThat(total(totals, "SUPPLY")).isEqualByComparingTo("0.00");
    assertThat(total(totals, "WITHDRAWAL")).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("com sangria e suprimento: esperado e totais por tipo conferem com os movimentos")
  void reportsWithdrawalsAndSupplies() throws SQLException {
    UUID registerId = registerId();
    UUID sessionId = open(registerId, "100.00");
    UUID operatorId = adminUserId();
    insertMovement(
        sessionId, CashMovementType.WITHDRAWAL, "-25.00", "depósito bancário", operatorId);
    insertMovement(sessionId, CashMovementType.SUPPLY, "40.00", "troco extra", operatorId);

    Response response = currentSession(registerId, adminToken());

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(money(body, "expectedAmount"))
        .as("100 de abertura + 40 de suprimento − 25 de sangria")
        .isEqualByComparingTo("115.00");
    Map<String, Object> totals = totalsByType(body);
    assertThat(total(totals, "OPENING")).isEqualByComparingTo("100.00");
    assertThat(total(totals, "SALE")).isEqualByComparingTo("0.00");
    assertThat(total(totals, "SUPPLY")).isEqualByComparingTo("40.00");
    assertThat(total(totals, "WITHDRAWAL"))
        .as("sangria é negativa no ledger")
        .isEqualByComparingTo("-25.00");
  }

  @Test
  @DisplayName("usuário sem cash.read: 403 ACCESS_DENIED citando a permissão")
  void deniesUserWithoutCashReadPermission() {
    String username = "caixa.consulta." + SUFFIX;
    createUser(username, List.of());
    UUID registerId = registerId();

    Response response = currentSession(registerId, login(username));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("cash.read");
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves,
   * movimentos, sessões, eventos, sessões de auth, papéis e usuários, nessa ordem.
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

  /** Abre o caixa pela API (passo 607) com chave de idempotência nova e devolve o id da sessão. */
  private UUID open(UUID registerId, String openingAmount) {
    String key = "caixa.consulta." + SUFFIX + "." + UUID.randomUUID();
    idempotencyKeys.add(key);
    Response response =
        given()
            .header("Authorization", "Bearer " + adminToken())
            .header(IdempotencyGuard.KEY_HEADER, key)
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

  /**
   * Grava um movimento do ledger pela porta, em transação própria para o request HTTP enxergá-lo.
   */
  private void insertMovement(
      UUID sessionId, CashMovementType type, String amount, String reason, UUID operatorId) {
    callInOwnTransaction(
        () ->
            cashSessionStore.insertMovement(
                new NewCashMovement(
                    storeId(),
                    sessionId,
                    type,
                    new BigDecimal(amount),
                    null,
                    null,
                    null,
                    reason,
                    operatorId,
                    Instant.now().truncatedTo(ChronoUnit.MICROS))));
  }

  /** GET /current-session com o token informado. */
  private static Response currentSession(UUID registerId, String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get(CURRENT_SESSION_PATH.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** Cria o usuário pelo caso de uso (passo 107) com os papéis pedidos e rastreia o id. */
  private void createUser(String username, List<String> roleCodes) {
    UUID id =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, roleCodes))
            .id();
    userIds.add(id);
  }

  /** Login pela API (passo 205) e devolve o token em claro da sessão nova. */
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

  /** Id do ADMIN da fixture: a FK de {@code created_by_user_id} aponta para um usuário real. */
  private UUID adminUserId() throws SQLException {
    return queryUuid("select id from users where username = ?", TestAdmin.USERNAME);
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal money(Map<String, Object> body, String field) {
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  /** Total de um tipo de movimento no mapa {@code totalsByType}, como número do JSON. */
  private static BigDecimal total(Map<String, Object> totals, String type) {
    return new BigDecimal(String.valueOf(totals.get(type)));
  }

  /** Mapa {@code totalsByType} do corpo, com as chaves dos tipos como o JSON as traz. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> totalsByType(Map<String, Object> body) {
    return (Map<String, Object>) body.get("totalsByType");
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          callInOwnTransaction(
              () ->
                  storeLookup
                      .findByCode(defaultStoreCode)
                      .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
                      .id());
    }
    return storeId;
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static <T> T callInOwnTransaction(Callable<T> work) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      return QuarkusTransaction.requiringNew().call(work);
    } finally {
      if (activated) {
        requestContext.terminate();
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
}
