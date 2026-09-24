package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
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
 * Abertura de caixa na API (passo 607) contra PostgreSQL real (Dev Services): a rota de verdade,
 * com o ADMIN da fixture e com usuários criados pelo caminho de aplicação. O 401 sem token é do
 * {@code RouteSecurityTest}; a regra de negócio da abertura, do {@code OpenCashSessionUseCase}.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (sessão, movimento
 * {@code OPENING}, registro de idempotência e o vínculo da sessão autenticada) e limpa tudo o que
 * comitou ao final: o banco é compartilhado, o caixa do seed é o mesmo de outros testes e as FKs de
 * {@code cash_sessions}, {@code cash_movements}, {@code idempotency_keys} e {@code auth_sessions}
 * são {@code restrict}. O {@link TestAdmin} apaga as sessões e o usuário dele depois dos
 * {@code @AfterEach} desta classe.
 */
@QuarkusTest
class OpenCashSessionResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String ME_PATH = "/api/v1/auth/me";

  /** Caixa do seed da V11: existe sempre, é a fixture da abertura. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";

  /** Caso de uso da criação de usuário (passo 107): a fixture nasce por aqui, não pela API. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  @Test
  @DisplayName(
      "abre o CAIXA-01: 201 com a sessão, Location da sessão atual e movimento OPENING no banco")
  void opensRegister() throws SQLException {
    UUID registerId = registerId();
    String key = newKey();

    Response response = open(registerId, key, "150.00", adminToken());

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.contentType()).contains("application/json");
    Map<String, Object> body = response.jsonPath().getMap("$");
    assertThat(body)
        .as("contrato da abertura: só os campos da sessão, nem storeId nem version")
        .containsOnlyKeys(
            "id", "cashRegisterId", "status", "openedAt", "openedByUserId", "openingAmount");
    UUID sessionId = UUID.fromString((String) body.get("id"));
    cashSessionIds.add(sessionId);
    assertThat(sessionId.version()).as("id é UUIDv7").isEqualTo(7);
    assertThat(body.get("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(body.get("openedByUserId"))
        .as("quem abriu é o ator do token")
        .isEqualTo(adminUserId().toString());
    assertThat(body.get("openedAt")).isNotNull();
    assertThat(new BigDecimal(String.valueOf(body.get("openingAmount"))))
        .isEqualByComparingTo("150.00");

    assertThat(URI.create(response.getHeader("Location")).getPath())
        .as("o 201 aponta para a sessão atual do caixa (rota do passo 608)")
        .isEqualTo("/api/v1/cash-registers/%s/current-session".formatted(registerId));

    assertThat(openingMovementAmount(sessionId))
        .as("a abertura grava o movimento OPENING com o fundo de troco")
        .isEqualTo("150.00");
    assertThat(countIdempotencyKeys(key)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "mesma Idempotency-Key: replay com a mesma sessão e Idempotency-Replayed, sem duplicar")
  void replaysSameKey() throws SQLException {
    UUID registerId = registerId();
    String key = newKey();

    Response first = open(registerId, key, "80.00", adminToken());
    assertThat(first.statusCode()).isEqualTo(201);
    UUID sessionId = UUID.fromString(first.jsonPath().getString("id"));
    cashSessionIds.add(sessionId);

    Response replay = open(registerId, key, "80.00", adminToken());

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 201").isEqualTo(201);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem reexecutar a abertura")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));

    assertThat(countOpenSessions(registerId)).as("uma sessão aberta").isEqualTo(1);
    assertThat(countMovements(sessionId)).as("um movimento OPENING").isEqualTo(1);
    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
  }

  @Test
  @DisplayName("caixa já aberto: 409 CASH_REGISTER_ALREADY_OPEN com chave nova (não vira replay)")
  void rejectsSecondOpenWithNewKey() throws SQLException {
    UUID registerId = registerId();
    Response first = open(registerId, newKey(), "100.00", adminToken());
    assertThat(first.statusCode()).isEqualTo(201);
    cashSessionIds.add(UUID.fromString(first.jsonPath().getString("id")));

    Response second = open(registerId, newKey(), "50.00", adminToken());

    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.contentType()).contains("application/problem+json");
    assertThat(second.jsonPath().getString("code")).isEqualTo("CASH_REGISTER_ALREADY_OPEN");
    assertThat(countOpenSessions(registerId))
        .as("a segunda abertura não criou sessão")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("sem Idempotency-Key: 400 IDEMPOTENCY_KEY_REQUIRED sem abrir o caixa")
  void rejectsMissingIdempotencyKey() throws SQLException {
    UUID registerId = registerId();

    Response response = open(registerId, null, "100.00", adminToken());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(countOpenSessions(registerId)).as("sem chave a operação nem roda").isZero();
  }

  @Test
  @DisplayName("fundo de troco ausente ou negativo: 400 VALIDATION_ERROR sem abrir o caixa")
  void rejectsInvalidOpeningAmount() throws SQLException {
    UUID registerId = registerId();

    for (String body : List.of("{}", "{\"openingAmount\": -1.00}")) {
      Response response = openWithBody(registerId, newKey(), body, adminToken());

      assertThat(response.statusCode()).as("corpo %s", body).isEqualTo(400);
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
      assertThat(response.jsonPath().getList("errors")).isNotEmpty();
    }
    assertThat(countOpenSessions(registerId)).as("corpo inválido não abre o caixa").isZero();
  }

  @Test
  @DisplayName("usuário sem cash.open: 403 ACCESS_DENIED e o caixa continua fechado")
  void deniesUserWithoutCashOpenPermission() throws SQLException {
    String username = "caixa.sem-permissao." + SUFFIX;
    createUser(username, List.of());
    UUID registerId = registerId();
    String key = newKey();

    Response response = open(registerId, key, "100.00", login(username));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("cash.open");
    assertThat(countOpenSessions(registerId)).as("quem não pode abrir não abre").isZero();
    assertThat(countIdempotencyKeys(key)).as("o 403 barra antes da idempotência").isZero();
  }

  @Test
  @DisplayName(
      "abrir vincula a sessão autenticada ao caixa: /auth/me sai de nulo para o caixa aberto")
  void bindsAuthenticatedSessionToRegister() throws SQLException {
    String username = "caixa.vinculo." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    String token = login(username);
    UUID registerId = registerId();

    assertThat(me(token).jsonPath().getString("cashRegisterId"))
        .as("login sem caixa não vincula nada")
        .isNull();

    Response opened = open(registerId, newKey(), "60.00", token);
    assertThat(opened.statusCode()).isEqualTo(201);
    cashSessionIds.add(UUID.fromString(opened.jsonPath().getString("id")));

    assertThat(me(token).jsonPath().getString("cashRegisterId"))
        .as("o vínculo gravado na abertura vale para a próxima requisição")
        .isEqualTo(registerId.toString());
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

  /** POST /open com o token e a chave informados; chave nula é o cenário do header ausente. */
  private static Response open(UUID registerId, String key, String openingAmount, String token) {
    return openWithBody(registerId, key, "{\"openingAmount\": %s}".formatted(openingAmount), token);
  }

  /** POST /open com o corpo cru informado; chave nula é o cenário do header ausente. */
  private static Response openWithBody(UUID registerId, String key, String body, String token) {
    RequestSpecification request =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .body(body);
    if (key != null) {
      request = request.header(KEY_HEADER, key);
    }
    return request.when().post(OPEN_PATH.formatted(registerId)).then().extract().response();
  }

  /** GET /auth/me com o token informado: prova o vínculo da sessão com o caixa. */
  private static Response me(String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get(ME_PATH)
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.abertura." + SUFFIX + "." + UUID.randomUUID();
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

  /** Id do ADMIN da fixture: a FK de {@code opened_by_user_id} aponta para o dono do token. */
  private UUID adminUserId() throws SQLException {
    return queryUuid("select id from users where username = ?", TestAdmin.USERNAME);
  }

  /** Valor do movimento {@code OPENING} da sessão; falha quando ele não existe. */
  private String openingMovementAmount(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select amount::text as amount from cash_movements where cash_session_id = ? and"
                    + " type = 'OPENING'")) {
      statement.setObject(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento OPENING da sessão %s", sessionId).isTrue();
        return resultSet.getString("amount");
      }
    }
  }

  /** Sessões abertas do caixa: a abertura e o replay não podem deixar mais de uma. */
  private int countOpenSessions(UUID registerId) throws SQLException {
    return queryInt(
        "select count(*) from cash_sessions where cash_register_id = ? and status = 'OPEN'",
        registerId);
  }

  /** Movimentos da sessão: um por abertura, nenhum a mais no replay. */
  private int countMovements(UUID sessionId) throws SQLException {
    return queryInt("select count(*) from cash_movements where cash_session_id = ?", sessionId);
  }

  /** Registros da chave de idempotência: um por operação, não um por tentativa. */
  private int countIdempotencyKeys(String key) throws SQLException {
    return queryInt("select count(*) from idempotency_keys where key = ?", key);
  }

  private int queryInt(String sql, Object parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
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
}
