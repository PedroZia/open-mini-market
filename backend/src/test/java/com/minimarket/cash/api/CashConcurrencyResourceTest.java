package com.minimarket.cash.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.api.IdempotencyGuard;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concorrência do caixa na API (passo 613) contra PostgreSQL real (Dev Services): as garantias do
 * §8 sob disputa de verdade — dois {@code open} simultâneos no mesmo caixa, dois {@code close}
 * simultâneos da mesma sessão e uma sangria disputando com o fechamento.
 *
 * <p>Cada thread faz a própria requisição HTTP — transação e contexto próprios, como dois caixas de
 * verdade — disparada por um {@link CountDownLatch} comum: sem {@code sleep} e sem
 * {@code @Disabled}. O que o teste prova são as invariantes no banco e a <em>contagem de
 * efeitos</em> (um vencedor e um conflito, um movimento, uma conferência), nunca a ordem em que as
 * threads chegaram — ordem é do escalonador, efeito é do sistema.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco e limpa tudo o que
 * comitou no {@code @AfterEach}: chaves de idempotência, movimentos, sessões e eventos, nessa ordem
 * — o banco é compartilhado, o {@code CAIXA-01} do seed é fixture de outros testes e as FKs são
 * {@code restrict}. O {@link com.minimarket.support.TestAdmin} apaga as sessões e o usuário dele
 * depois dos {@code @AfterEach} desta classe.
 */
@QuarkusTest
class CashConcurrencyResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  private static final String CLOSE_PATH = "/api/v1/cash-registers/%s/close";

  private static final String WITHDRAWALS_PATH = "/api/v1/cash-registers/%s/withdrawals";

  /** Caixa do seed da V11: existe sempre, é a fixture da disputa. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  /** Espera máxima das threads: o teste falha, nunca trava, se a corrida não terminar. */
  private static final long WORKER_TIMEOUT_SECONDS = 30;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  @Test
  @DisplayName("dois opens simultâneos: um 201 e um 409, uma sessão aberta e um movimento OPENING")
  void opensOnlyOnceUnderRace() throws Exception {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    String token = adminToken();
    String firstKey = newKey();
    String secondKey = newKey();

    List<Response> responses =
        race(
            () -> open(registerId, firstKey, "100.00", token),
            () -> open(registerId, secondKey, "150.00", token));

    assertThat(statuses(responses))
        .as("o índice único parcial deixa um vencedor e um conflito")
        .containsExactlyInAnyOrder(201, 409);
    Response conflict = withStatus(responses, 409);
    assertThat(conflict.contentType()).contains("application/problem+json");
    assertThat(conflict.jsonPath().getString("code")).isEqualTo("CASH_REGISTER_ALREADY_OPEN");

    UUID sessionId = UUID.fromString(withStatus(responses, 201).jsonPath().getString("id"));
    cashSessionIds.add(sessionId);

    assertThat(countOpenSessions(registerId)).as("exatamente uma sessão OPEN").isEqualTo(1);
    assertThat(countMovements(sessionId)).as("exatamente um movimento, o da abertura").isEqualTo(1);
    assertThat(countMovements(sessionId, "OPENING"))
        .as("o fundo de troco entra uma vez só")
        .isEqualTo(1);
    assertThat(countIdempotencyKeys(firstKey, secondKey))
        .as("só o vencedor grava o registro de idempotência")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "dois closes simultâneos: um 200 e um 409, uma só conferência e um só registro de chave")
  void closesOnlyOnceUnderRace() throws Exception {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "100.00");
    String token = adminToken();
    String firstKey = newKey();
    String secondKey = newKey();

    List<Response> responses =
        race(
            () -> close(registerId, firstKey, "100.00", token),
            () -> close(registerId, secondKey, "100.00", token));

    assertThat(statuses(responses))
        .as("o lock da sessão serializa: um fecha, o outro recebe o conflito de estado")
        .containsExactlyInAnyOrder(200, 409);
    assertThat(withStatus(responses, 409).jsonPath().getString("code"))
        .isEqualTo("CASH_SESSION_ALREADY_CLOSED");

    SessionRow stored = storedSession(sessionId);

    assertThat(stored.status()).isEqualTo("CLOSED");
    assertThat(stored.countedAmount()).isEqualTo("100.00");
    assertThat(stored.expectedAmount()).isEqualTo("100.00");
    assertThat(stored.differenceAmount()).isEqualTo("0.00");
    assertThat(stored.version()).as("uma única transição de OPEN para CLOSED").isEqualTo(1L);
    assertThat(countAuditEvents(sessionId, "CASH_SESSION_CLOSED"))
        .as("um fechamento, não dois")
        .isEqualTo(1);
    assertThat(countIdempotencyKeys(firstKey, secondKey))
        .as("o perdedor não grava a chave: não houve segunda resposta")
        .isEqualTo(1);
    assertThat(countOpenSessions(registerId)).as("o caixa não fica aberto").isZero();
  }

  @Test
  @DisplayName("sangria durante o fechamento: ou entra na conta do esperado, ou não é gravada")
  void withdrawalDuringCloseNeverLeavesTheExpectedAmountBehind() throws Exception {
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    UUID sessionId = open(registerId, "100.00");
    String token = adminToken();
    String closeKey = newKey();
    String withdrawalKey = newKey();

    List<Response> responses =
        race(
            () -> close(registerId, closeKey, "100.00", token),
            () ->
                movement(
                    WITHDRAWALS_PATH,
                    registerId,
                    withdrawalKey,
                    "30.00",
                    "depósito bancário",
                    token));
    Response close = responses.getFirst();
    Response withdrawal = responses.getLast();

    assertThat(close.statusCode()).as("o fechamento fecha a sessão do cenário").isEqualTo(200);
    assertThat(withdrawal.statusCode())
        .as("a sangria ou entra na conta (201) ou é recusada (404), nunca grava fora da conta")
        .isIn(201, 404);

    if (withdrawal.statusCode() == 404) {
      assertThat(withdrawal.jsonPath().getString("code")).isEqualTo("CASH_SESSION_NOT_OPEN");
      assertThat(countMovements(sessionId, "WITHDRAWAL"))
          .as("sem movimento órfão depois do fechamento")
          .isZero();
    } else {
      assertThat(storedMovementAmount(sessionId, "WITHDRAWAL"))
          .as("a sangria gravada é a que o esperado do fechamento tem de considerar")
          .isEqualTo("-30.00");
    }

    SessionRow stored = storedSession(sessionId);
    BigDecimal expected = new BigDecimal(stored.expectedAmount());

    assertThat(stored.status()).isEqualTo("CLOSED");
    assertThat(stored.countedAmount()).isEqualTo("100.00");
    assertThat(expected)
        .as("expected_amount = abertura + movimentos presentes, pela regra única do caixa")
        .isEqualByComparingTo(
            new BigDecimal(stored.openingAmount()).add(new BigDecimal(stored.movementsSum())));
    assertThat(new BigDecimal(stored.differenceAmount()))
        .as("difference_amount = contado − esperado")
        .isEqualByComparingTo(new BigDecimal(stored.countedAmount()).subtract(expected));
    assertThat(stored.version()).as("a sessão fecha exatamente uma vez").isEqualTo(1L);
    assertThat(countAuditEvents(sessionId, "CASH_SESSION_CLOSED")).isEqualTo(1);
  }

  /**
   * Dispara as duas ações no mesmo instante — o latch é a largada comum — e devolve as duas
   * respostas, na ordem em que foram submetidas. Cada thread faz a própria requisição HTTP, com
   * transação e contexto próprios.
   */
  private static List<Response> race(Callable<Response> first, Callable<Response> second)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Response> firstResponse = workers.submit(awaiting(start, first));
      Future<Response> secondResponse = workers.submit(awaiting(start, second));
      start.countDown();
      return List.of(
          firstResponse.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          secondResponse.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }
  }

  /** Envolve a ação para ela só começar quando o latch da largada abrir. */
  private static Callable<Response> awaiting(CountDownLatch start, Callable<Response> action) {
    return () -> {
      start.await();
      return action.call();
    };
  }

  /**
   * Abre o caixa pela API com chave nova e devolve o id da sessão comitada (fixture do cenário).
   */
  private UUID open(UUID registerId, String openingAmount) {
    Response response = open(registerId, newKey(), openingAmount, adminToken());
    assertThat(response.statusCode()).as("abertura da fixture").isEqualTo(201);
    UUID sessionId = UUID.fromString(response.jsonPath().getString("id"));
    cashSessionIds.add(sessionId);
    return sessionId;
  }

  /** POST /open com o token e a chave informados: a ação de uma thread da corrida. */
  private static Response open(UUID registerId, String key, String openingAmount, String token) {
    return post(
        OPEN_PATH, registerId, key, "{\"openingAmount\": %s}".formatted(openingAmount), token);
  }

  /** POST /close com a chave e o contado informados: a ação de uma thread da corrida. */
  private static Response close(UUID registerId, String key, String countedAmount, String token) {
    return post(
        CLOSE_PATH, registerId, key, "{\"countedAmount\": %s}".formatted(countedAmount), token);
  }

  /** POST de movimento (sangria) com a chave, o valor e o motivo informados. */
  private static Response movement(
      String path, UUID registerId, String key, String amount, String reason, String token) {
    return post(
        path,
        registerId,
        key,
        "{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason),
        token);
  }

  /** POST autenticado com a chave de idempotência; o status é conferido por quem chamou. */
  private static Response post(
      String path, UUID registerId, String key, String body, String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .header(KEY_HEADER, key)
        .contentType("application/json")
        .body(body)
        .when()
        .post(path.formatted(registerId))
        .then()
        .extract()
        .response();
  }

  /** Status das duas respostas, para a contagem de efeitos: um vencedor e um conflito. */
  private static List<Integer> statuses(List<Response> responses) {
    return responses.stream().map(Response::statusCode).toList();
  }

  /** A resposta com o status esperado; o teste falha se não houver exatamente uma. */
  private static Response withStatus(List<Response> responses, int status) {
    return responses.stream()
        .filter(response -> response.statusCode() == status)
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "nenhuma resposta com status %d: %s".formatted(status, statuses(responses))));
  }

  /** Chave de idempotência nova por operação, rastreada para a limpeza. */
  private String newKey() {
    String key = "caixa.concorrencia." + UUID.randomUUID();
    idempotencyKeys.add(key);
    return key;
  }

  /** Sessões abertas do caixa: a corrida da abertura não pode deixar mais de uma. */
  private int countOpenSessions(UUID registerId) throws SQLException {
    return queryInt(
        "select count(*) from cash_sessions where cash_register_id = ? and status = 'OPEN'",
        registerId);
  }

  /** Movimentos da sessão, todos os tipos. */
  private int countMovements(UUID sessionId) throws SQLException {
    return queryInt("select count(*) from cash_movements where cash_session_id = ?", sessionId);
  }

  /** Movimentos da sessão de um tipo: um por efeito, nenhum a mais. */
  private int countMovements(UUID sessionId, String type) throws SQLException {
    return queryInt(
        "select count(*) from cash_movements where cash_session_id = ? and type = ?",
        sessionId,
        type);
  }

  /** Eventos da ação para a sessão: um por efeito efetivado. */
  private int countAuditEvents(UUID sessionId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", sessionId, action);
  }

  /** Registros de idempotência das duas chaves da corrida: um por operação efetivada. */
  private int countIdempotencyKeys(String firstKey, String secondKey) throws SQLException {
    return queryInt(
        "select count(*) from idempotency_keys where key in (?, ?)", firstKey, secondKey);
  }

  /** Sessão como o banco a guardou, com a soma dos movimentos que entram na conta do esperado. */
  private SessionRow storedSession(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select s.status, s.opening_amount::text as opening_amount,"
                    + " s.counted_amount::text as counted_amount,"
                    + " s.expected_amount::text as expected_amount,"
                    + " s.difference_amount::text as difference_amount, s.version,"
                    + " coalesce((select sum(m.amount) from cash_movements m"
                    + " where m.cash_session_id = s.id and m.type <> 'OPENING'), 0)::text"
                    + " as movements_sum"
                    + " from cash_sessions s where s.id = ?")) {
      statement.setObject(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("sessão de caixa %s gravada", sessionId).isTrue();
        return new SessionRow(
            resultSet.getString("status"),
            resultSet.getString("opening_amount"),
            resultSet.getString("counted_amount"),
            resultSet.getString("expected_amount"),
            resultSet.getString("difference_amount"),
            resultSet.getString("movements_sum"),
            resultSet.getLong("version"));
      }
    }
  }

  /** Valor do movimento do tipo na sessão; falha se houver zero ou mais de um. */
  private String storedMovementAmount(UUID sessionId, String type) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select amount::text as amount from cash_movements"
                    + " where cash_session_id = ? and type = ?")) {
      statement.setObject(1, sessionId);
      statement.setString(2, type);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("movimento %s da sessão %s", type, sessionId).isTrue();
        String amount = resultSet.getString("amount");
        assertThat(resultSet.next()).as("um único movimento %s", type).isFalse();
        return amount;
      }
    }
  }

  /** Remove o que o teste comitou, na ordem que as FKs exigem (§ passo 613). */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(
            connection,
            "delete from audit_events where entity_id = ? or cash_session_id = ?",
            sessionId,
            sessionId);
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

  /** Linha de {@code cash_sessions} como o banco a guardou, com a soma dos movimentos. */
  private record SessionRow(
      String status,
      String openingAmount,
      String countedAmount,
      String expectedAmount,
      String differenceAmount,
      String movementsSum,
      long version) {}
}
