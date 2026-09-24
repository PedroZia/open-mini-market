package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
 * Detalhe e resumo da sessão de caixa na API (passo 612) contra PostgreSQL real (Dev Services): as
 * rotas {@code GET /api/v1/cash-sessions/{id}} e {@code .../{id}/summary} com o ADMIN da fixture,
 * na sessão aberta e na fechada. O 401 sem token é do {@code RouteSecurityTest}; a conta do
 * esperado é do domínio e do caso de uso, cobertos pelos testes deles.
 *
 * <p>O cenário monta a sessão pelo caminho de verdade — abrir, sangrar, suprir e fechar pela API —
 * e confere o resumo contra os movimentos que o banco somou. O request HTTP comita, então o teste
 * limpa tudo o que comitou no {@code @AfterEach}: chaves de idempotência, movimentos, sessões e
 * eventos — o banco é compartilhado, o caixa do seed é o mesmo de outros testes e as FKs são {@code
 * restrict}. O {@link TestAdmin} apaga a sessão e o usuário dele depois.
 */
@QuarkusTest
class CashSessionsResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String CLOSE_PATH = "/api/v1/cash-registers/%s/close";

  private static final String WITHDRAWALS_PATH = "/api/v1/cash-registers/%s/withdrawals";

  private static final String SUPPLIES_PATH = "/api/v1/cash-registers/%s/supplies";

  private static final String DETAIL_PATH = "/api/v1/cash-sessions/%s";

  private static final String SUMMARY_PATH = "/api/v1/cash-sessions/%s/summary";

  /** Caixa do seed da V11: existe sempre, é a fixture da consulta. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /** Os quatro tipos de movimento do ledger, como o JSON os traz. */
  private static final List<String> MOVEMENT_TYPES =
      List.of("OPENING", "SALE", "SUPPLY", "WITHDRAWAL");

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  @Test
  @DisplayName("detalhe: 200 na sessão aberta com os campos de fechamento nulos")
  void returnsOpenSessionDetail() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "150.00");

    Response response = get(DETAIL_PATH.formatted(sessionId));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(body)
        .as("contrato do detalhe: nem storeId, nem version")
        .containsOnlyKeys(
            "id",
            "cashRegisterId",
            "status",
            "openedAt",
            "openedByUserId",
            "openingAmount",
            "closedAt",
            "closedByUserId",
            "countedAmount",
            "expectedAmount",
            "differenceAmount",
            "closingNotes");
    assertThat(body.get("id")).isEqualTo(sessionId.toString());
    assertThat(body.get("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(body.get("openedAt")).isNotNull();
    assertThat(body.get("openedByUserId")).isEqualTo(adminUserId().toString());
    assertThat(money(body, "openingAmount")).isEqualByComparingTo("150.00");
    for (String field :
        List.of(
            "closedAt",
            "closedByUserId",
            "countedAmount",
            "expectedAmount",
            "differenceAmount",
            "closingNotes")) {
      assertThat(body.get(field)).as("%s nulo enquanto a sessão está aberta", field).isNull();
    }
  }

  @Test
  @DisplayName("detalhe: 200 na sessão fechada com a conferência do fechamento")
  void returnsClosedSessionDetail() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "100.00");
    movement(WITHDRAWALS_PATH, registerId, "30.00", "depósito bancário");
    close(registerId, "60.00", "conferência do turno");

    Response response = get(DETAIL_PATH.formatted(sessionId));

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(body.get("status")).isEqualTo("CLOSED");
    assertThat(body.get("closedAt")).isNotNull();
    assertThat(body.get("closedByUserId")).isEqualTo(adminUserId().toString());
    assertThat(money(body, "countedAmount")).isEqualByComparingTo("60.00");
    assertThat(money(body, "expectedAmount"))
        .as("100 de abertura − 30 de sangria")
        .isEqualByComparingTo("70.00");
    assertThat(money(body, "differenceAmount")).isEqualByComparingTo("-10.00");
    assertThat(body.get("closingNotes")).isEqualTo("conferência do turno");
  }

  @Test
  @DisplayName("detalhe e resumo de sessão inexistente: 404 CASH_SESSION_NOT_FOUND")
  void rejectsUnknownSession() {
    UUID unknown = UUID.randomUUID();

    for (String path : List.of(DETAIL_PATH, SUMMARY_PATH)) {
      Response response = get(path.formatted(unknown));

      assertThat(response.statusCode()).as("%s", path).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("CASH_SESSION_NOT_FOUND");
      assertThat(response.jsonPath().getString("title"))
          .isEqualTo("Sessão de caixa não encontrada");
    }
  }

  @Test
  @DisplayName(
      "resumo: esperado × contado × totais contra os movimentos, aberta e depois de fechar")
  void summarizesSessionAgainstMovements() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "100.00");
    movement(WITHDRAWALS_PATH, registerId, "30.00", "depósito bancário");
    movement(SUPPLIES_PATH, registerId, "20.00", "troco extra");

    Response open = get(SUMMARY_PATH.formatted(sessionId));

    assertThat(open.statusCode()).isEqualTo(200);
    assertThat(open.contentType()).contains("application/json");
    Map<String, Object> openBody = open.jsonPath().getMap("$");
    assertThat(openBody)
        .as("contrato do resumo: nem storeId, nem cashRegisterId, nem version")
        .containsOnlyKeys(
            "sessionId",
            "status",
            "openingAmount",
            "expectedAmount",
            "countedAmount",
            "differenceAmount",
            "totalsByType");
    assertThat(openBody.get("sessionId")).isEqualTo(sessionId.toString());
    assertThat(openBody.get("status")).isEqualTo("OPEN");
    assertThat(money(openBody, "openingAmount")).isEqualByComparingTo("100.00");
    assertThat(money(openBody, "expectedAmount"))
        .as("100 de abertura + 20 de suprimento − 30 de sangria; o OPENING não conta duas vezes")
        .isEqualByComparingTo("90.00");
    assertThat(openBody.get("countedAmount")).as("nulo enquanto aberta").isNull();
    assertThat(openBody.get("differenceAmount")).as("nulo enquanto aberta").isNull();

    Map<String, Object> totals = totalsByType(openBody);

    assertThat(totals)
        .as("os quatro tipos sempre aparecem, os sem movimento zerados")
        .containsOnlyKeys(MOVEMENT_TYPES.toArray(String[]::new));
    assertThat(money(totals, "OPENING"))
        .as("o movimento OPENING do ledger é o registro da abertura")
        .isEqualByComparingTo("100.00");
    assertThat(money(totals, "SALE")).isEqualByComparingTo("0.00");
    assertThat(money(totals, "SUPPLY")).isEqualByComparingTo("20.00");
    assertThat(money(totals, "WITHDRAWAL"))
        .as("sangria é negativa no ledger")
        .isEqualByComparingTo("-30.00");
    for (String type : MOVEMENT_TYPES) {
      assertThat(money(totals, type))
          .as("total de %s confere com os movimentos no banco", type)
          .isEqualByComparingTo(storedTotal(sessionId, type));
    }

    close(registerId, "85.00", "conferência do turno");

    Response closed = get(SUMMARY_PATH.formatted(sessionId));

    assertThat(closed.statusCode()).isEqualTo(200);
    Map<String, Object> closedBody = closed.jsonPath().getMap("$");
    assertThat(closedBody.get("status")).isEqualTo("CLOSED");
    assertThat(money(closedBody, "openingAmount")).isEqualByComparingTo("100.00");
    assertThat(money(closedBody, "expectedAmount"))
        .as("o esperado não muda com o fechamento")
        .isEqualByComparingTo("90.00");
    assertThat(money(closedBody, "countedAmount"))
        .as("a conferência do fechamento")
        .isEqualByComparingTo("85.00");
    assertThat(money(closedBody, "differenceAmount"))
        .as("contado 85 − esperado 90")
        .isEqualByComparingTo("-5.00");
    assertThat(totalsByType(closedBody)).as("fechar não mexe nos movimentos").isEqualTo(totals);
  }

  /** Remove o que o teste comitou, na ordem que as FKs exigem. */
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
    }
  }

  /** Abre o caixa pela API (passo 607) com chave nova e devolve o id da sessão comitada. */
  private UUID open(UUID registerId, String openingAmount) {
    Response response =
        given()
            .header("Authorization", "Bearer " + adminToken())
            .header(KEY_HEADER, newKey())
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

  /** Fecha o caixa pela API (passo 612) com a conferência informada. */
  private void close(UUID registerId, String countedAmount, String notes) {
    given()
        .header("Authorization", "Bearer " + adminToken())
        .header(KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"countedAmount\": %s, \"notes\": \"%s\"}".formatted(countedAmount, notes))
        .when()
        .post(CLOSE_PATH.formatted(registerId))
        .then()
        .statusCode(200);
  }

  /** POST de sangria/suprimento pela API (passos 609/610) com chave nova. */
  private void movement(String path, UUID registerId, String amount, String reason) {
    given()
        .header("Authorization", "Bearer " + adminToken())
        .header(KEY_HEADER, newKey())
        .contentType("application/json")
        .body("{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason))
        .when()
        .post(path.formatted(registerId))
        .then()
        .statusCode(201);
  }

  /** GET autenticado como o ADMIN da fixture. */
  private Response get(String path) {
    return given()
        .header("Authorization", "Bearer " + adminToken())
        .when()
        .get(path)
        .then()
        .extract()
        .response();
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.sessao." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Id do ADMIN da fixture: a FK de {@code opened_by_user_id} aponta para o dono do token. */
  private UUID adminUserId() throws SQLException {
    return queryUuid("select id from users where username = ?", TestAdmin.USERNAME);
  }

  /** Soma dos movimentos do tipo na sessão, como o banco a guardou; zero sem movimento. */
  private BigDecimal storedTotal(UUID sessionId, String type) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select coalesce(sum(amount), 0)::text as total from cash_movements"
                    + " where cash_session_id = ? and type = ?")) {
      statement.setObject(1, sessionId);
      statement.setString(2, type);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return new BigDecimal(resultSet.getString("total"));
      }
    }
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal money(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
  }

  /** Mapa {@code totalsByType} do corpo, com as chaves dos tipos como o JSON as traz. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> totalsByType(Map<String, Object> body) {
    Map<String, Object> totals = (Map<String, Object>) body.get("totalsByType");
    assertThat(totals).as("totalsByType no corpo").isNotNull();
    return totals;
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
