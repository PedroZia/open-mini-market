package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fechamento do caixa na API (passo 612) contra PostgreSQL real (Dev Services): a rota {@code POST
 * /api/v1/cash-registers/{id}/close} com o ADMIN da fixture, do 200 com a conferência gravada ao
 * 409 da segunda chamada com chave nova, passando pelo replay idempotente. O 401 sem token é do
 * {@code RouteSecurityTest} e a regra do fechamento é do caso de uso (passo 611), com teste
 * próprio; aqui o alvo é o contrato HTTP e o que ele deixa no banco.
 *
 * <p>O request HTTP comita, então o teste confere por SQL a linha de {@code cash_sessions} e os
 * eventos de auditoria e limpa tudo o que comitou no {@code @AfterEach}: chaves de idempotência,
 * movimentos, sessões e eventos — o banco é compartilhado, o caixa do seed é o mesmo de outros
 * testes e as FKs são {@code restrict}. O {@link TestAdmin} apaga a sessão e o usuário dele depois.
 */
@QuarkusTest
class CloseCashSessionResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String CLOSE_PATH = "/api/v1/cash-registers/%s/close";

  private static final String WITHDRAWALS_PATH = "/api/v1/cash-registers/%s/withdrawals";

  /** Caixa do seed da V11: existe sempre, é a fixture do fechamento. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  @Test
  @DisplayName("fecha pela API: 200 com a conferência gravada e o detalhe da sessão fechada")
  void closesSessionWithConference() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "100.00");
    movement(WITHDRAWALS_PATH, registerId, newKey(), "30.00", "depósito bancário");

    Response response = close(registerId, newKey(), "60.00", "conferência do turno");

    assertThat(response.statusCode()).as("fechamento é atualização, não criação").isEqualTo(200);
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
    assertThat(body.get("status")).isEqualTo("CLOSED");
    assertThat(body.get("closedAt")).isNotNull();
    assertThat(body.get("closedByUserId")).isEqualTo(adminUserId().toString());
    assertThat(money(body, "countedAmount")).isEqualByComparingTo("60.00");
    assertThat(money(body, "expectedAmount"))
        .as("100 de abertura − 30 de sangria, recalculado pelo servidor")
        .isEqualByComparingTo("70.00");
    assertThat(money(body, "differenceAmount"))
        .as("contado 60 − esperado 70")
        .isEqualByComparingTo("-10.00");
    assertThat(body.get("closingNotes")).isEqualTo("conferência do turno");

    SessionRow stored = storedSession(sessionId);

    assertThat(stored.status()).isEqualTo("CLOSED");
    assertThat(stored.countedAmount()).isEqualTo("60.00");
    assertThat(stored.expectedAmount()).isEqualTo("70.00");
    assertThat(stored.differenceAmount()).isEqualTo("-10.00");
    assertThat(stored.closingNotes()).isEqualTo("conferência do turno");
    assertThat(stored.closedByUserId()).isEqualTo(adminUserId());
    assertThat(stored.closedAt()).isNotNull();
    assertThat(countAuditEvents(sessionId, "CASH_SESSION_CLOSED")).isEqualTo(1);
  }

  @Test
  @DisplayName("mesma Idempotency-Key: replay do mesmo 200 com um só fechamento e um só registro")
  void replaysSameKeyWithoutClosingTwice() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "80.00");
    String key = newKey();

    Response first = close(registerId, key, "80.00", "sem diferença");

    assertThat(first.statusCode()).isEqualTo(200);

    Response replay = close(registerId, key, "80.00", "sem diferença");

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 200").isEqualTo(200);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem fechar de novo")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));

    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
    assertThat(countAuditEvents(sessionId, "CASH_SESSION_CLOSED"))
        .as("um fechamento, não dois")
        .isEqualTo(1);
    assertThat(storedSession(sessionId).version())
        .as("a linha foi fechada uma vez só")
        .isEqualTo(1L);
  }

  @Test
  @DisplayName(
      "segunda close com chave nova: 409 CASH_SESSION_ALREADY_CLOSED, sem segundo fechamento")
  void rejectsSecondCloseWithNewKey() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "50.00");
    assertThat(close(registerId, newKey(), "50.00", null).statusCode()).isEqualTo(200);

    Response second = close(registerId, newKey(), "45.00", null);

    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.contentType()).contains("application/problem+json");
    assertThat(second.jsonPath().getString("code")).isEqualTo("CASH_SESSION_ALREADY_CLOSED");
    assertThat(countAuditEvents(sessionId, "CASH_SESSION_CLOSED"))
        .as("o conflito não grava um segundo fechamento")
        .isEqualTo(1);
    assertThat(storedSession(sessionId).countedAmount())
        .as("a conferência da primeira chamada é a que fica")
        .isEqualTo("50.00");
  }

  @Test
  @DisplayName("sem Idempotency-Key e com contado negativo: 400 sem fechar o caixa")
  void rejectsMissingKeyAndInvalidCountedAmount() throws SQLException {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "40.00");

    Response withoutKey = closeWithBody(registerId, null, "{\"countedAmount\": 40.00}");

    assertThat(withoutKey.statusCode()).isEqualTo(400);
    assertThat(withoutKey.contentType()).contains("application/problem+json");
    assertThat(withoutKey.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

    Response negative = closeWithBody(registerId, newKey(), "{\"countedAmount\": -1.00}");

    assertThat(negative.statusCode()).isEqualTo(400);
    assertThat(negative.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(negative.jsonPath().getList("errors"))
        .as("a validação diz qual campo falhou")
        .isNotEmpty();

    SessionRow stored = storedSession(sessionId);

    assertThat(stored.status()).as("nenhuma das tentativas fechou").isEqualTo("OPEN");
    assertThat(stored.countedAmount()).isNull();
  }

  /** Remove o que o teste comitou, na ordem que as FKs exigem (§ passo 612). */
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

  /** POST de fechamento com a chave e a conferência informadas; chave nula omite o header. */
  private Response close(UUID registerId, String key, String countedAmount, String notes) {
    String body =
        notes == null
            ? "{\"countedAmount\": %s}".formatted(countedAmount)
            : "{\"countedAmount\": %s, \"notes\": \"%s\"}".formatted(countedAmount, notes);
    return closeWithBody(registerId, key, body);
  }

  /** POST de fechamento com o corpo cru; chave nula é o cenário do header ausente. */
  private Response closeWithBody(UUID registerId, String key, String body) {
    RequestSpecification request =
        given()
            .header("Authorization", "Bearer " + adminToken())
            .contentType("application/json")
            .body(body);
    if (key != null) {
      request = request.header(KEY_HEADER, key);
    }
    return request.when().post(CLOSE_PATH.formatted(registerId)).then().extract().response();
  }

  /** POST de sangria com a chave e o valor informados: monta a conferência do fechamento. */
  private Response movement(
      String path, UUID registerId, String key, String amount, String reason) {
    return given()
        .header("Authorization", "Bearer " + adminToken())
        .header(KEY_HEADER, key)
        .contentType("application/json")
        .body("{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason))
        .when()
        .post(path.formatted(registerId))
        .then()
        .statusCode(201)
        .extract()
        .response();
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.fechamento." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Id do ADMIN da fixture: a FK de {@code closed_by_user_id} aponta para o dono do token. */
  private UUID adminUserId() throws SQLException {
    return queryUuid("select id from users where username = ?", TestAdmin.USERNAME);
  }

  /** Linha de {@code cash_sessions} como o banco a guardou; a linha é a única do cenário. */
  private SessionRow storedSession(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, counted_amount::text as counted_amount,"
                    + " expected_amount::text as expected_amount,"
                    + " difference_amount::text as difference_amount, closing_notes,"
                    + " closed_by_user_id, closed_at, version"
                    + " from cash_sessions where id = ?")) {
      statement.setObject(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão de caixa %s gravada", sessionId).isTrue();
        return new SessionRow(
            resultSet.getString("status"),
            resultSet.getString("counted_amount"),
            resultSet.getString("expected_amount"),
            resultSet.getString("difference_amount"),
            resultSet.getString("closing_notes"),
            resultSet.getObject("closed_by_user_id", UUID.class),
            resultSet.getTimestamp("closed_at") == null
                ? null
                : resultSet.getTimestamp("closed_at").toInstant(),
            resultSet.getLong("version"));
      }
    }
  }

  /** Eventos da ação para a sessão: um por fechamento efetivado. */
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

  /** Linha de {@code cash_sessions} como o banco a guardou depois do fechamento. */
  private record SessionRow(
      String status,
      String countedAmount,
      String expectedAmount,
      String differenceAmount,
      String closingNotes,
      UUID closedByUserId,
      Instant closedAt,
      long version) {}
}
