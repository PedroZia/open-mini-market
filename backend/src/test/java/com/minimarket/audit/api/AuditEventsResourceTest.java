package com.minimarket.audit.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consulta de auditoria na API (passo 1001) contra PostgreSQL real (Dev Services): o contrato de
 * {@code GET /api/v1/audit-events}, os filtros combinados, as bordas do período ({@code from}
 * inclusivo, {@code to} exclusivo), a ordenação (default {@code occurred_at desc}, {@code asc} via
 * {@code sort} e o desempate por {@code id}) e a paginação.
 *
 * <p>A fixture é semeada direto no banco com o marcador exclusivo {@code entity_type =
 * 'AUDIT_QUERY_TEST'}: nenhuma asserção depende de contar o log inteiro e a limpeza não toca em
 * evento de outro teste. O OPERADOR nasce pelo caso de uso (passo 107) e loga de verdade — o 403
 * dele é o porteiro da rota citando {@code audit.read} —, e o ADMIN da suíte prova o 200. O {@code
 * occurred_at} dos eventos é fixo, então a ordem não depende do relógio da máquina.
 *
 * <p>O request HTTP comita, então o teste limpa o que comitou ao fim de cada um: os eventos
 * semeados (append-only é grant da role {@code minimarket_app}, não do dono das tabelas) e os
 * eventos, sessões, papéis e o usuário criados pelo login do OPERADOR — as FKs de {@code
 * auth_sessions} e {@code user_roles} são {@code on delete restrict}.
 */
@QuarkusTest
class AuditEventsResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String PATH = AuditEventsResource.PATH;

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Marcador exclusivo da suíte: todo evento semeado por este teste o carrega. */
  private static final String MARKER_ENTITY_TYPE = "AUDIT_QUERY_TEST";

  private static final String MARKER_REQUEST_ID = "audit-query-suite." + SUFFIX;

  private static final String MARKER_DETAILS = "{\"marker\": \"audit-query-suite\"}";

  private static final String ACTOR_USERNAME = "audit.consulta." + SUFFIX;

  /** {@code occurred_at} fixos da fixture: a ordem e o período não dependem do relógio. */
  private static final Instant DAY_ONE = Instant.parse("2026-02-01T10:00:00Z");

  private static final Instant DAY_TWO = Instant.parse("2026-02-02T10:00:00Z");

  private static final Instant DAY_THREE = Instant.parse("2026-02-03T10:00:00Z");

  private static final Instant DAY_FOUR = Instant.parse("2026-02-04T10:00:00Z");

  /** Caso de uso da criação de usuário (passo 107): o OPERADOR do teste é fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  private UUID operatorId;

  private String operatorToken;

  /** Alvo dos eventos do cenário; os ids não têm FK — são históricos, como o log os guarda. */
  private UUID entityA;

  private UUID entityB;

  private UUID otherActorId;

  private UUID cashSessionA;

  private UUID cashSessionB;

  @BeforeEach
  void prepareFixture() throws SQLException {
    String username = "audit.consulta." + SUFFIX;
    operatorId =
        createUserUseCase
            .execute(new CreateUserCommand(username, username, PASSWORD, List.of("OPERADOR")))
            .id();
    operatorToken = login(username);
    entityA = UUID.randomUUID();
    entityB = UUID.randomUUID();
    otherActorId = UUID.randomUUID();
    cashSessionA = UUID.randomUUID();
    cashSessionB = UUID.randomUUID();
    seedEvents();
  }

  @Test
  @DisplayName(
      "GET /audit-events: ADMIN recebe 200 com a página padrão em occurred_at desc e o contrato do evento")
  void listsEventsInDescendingOrderForAdmin() {
    Response response = query(adminToken(), params("entityType", MARKER_ENTITY_TYPE));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(reasonsOf(response))
        .as("occurred_at desc: o mais recente primeiro")
        .containsExactly("suite-4", "suite-3", "suite-6", "suite-5", "suite-2", "suite-1");
    assertThat(response.jsonPath().getInt("page")).isZero();
    assertThat(response.jsonPath().getInt("size")).isEqualTo(20);
    assertThat(response.jsonPath().getLong("totalItems")).isEqualTo(6);
    assertThat(response.jsonPath().getInt("totalPages")).isEqualTo(1);

    Map<String, Object> first = firstItem(response);

    assertThat(first)
        .as("contrato do evento: todos os campos da coluna, sem sobra")
        .containsOnlyKeys(
            "id",
            "occurredAt",
            "storeId",
            "actorUserId",
            "actorUsername",
            "authSessionId",
            "cashSessionId",
            "cashRegisterId",
            "action",
            "entityType",
            "entityId",
            "source",
            "requestId",
            "reason",
            "details",
            "ip");
    assertThat(((Number) first.get("id")).longValue()).as("identity do banco").isPositive();
    assertThat(Instant.parse((String) first.get("occurredAt"))).isEqualTo(DAY_FOUR);
    assertThat(first.get("storeId")).as("evento semeado sem loja").isNull();
    assertThat(first.get("actorUserId")).isEqualTo(otherActorId.toString());
    assertThat(first.get("actorUsername")).isEqualTo(ACTOR_USERNAME);
    assertThat(first.get("authSessionId")).isNull();
    assertThat(first.get("cashSessionId")).isEqualTo(cashSessionB.toString());
    assertThat(first.get("cashRegisterId")).isNull();
    assertThat(first.get("action")).isEqualTo("AUDIT_QUERY_BETA");
    assertThat(first.get("entityType")).isEqualTo(MARKER_ENTITY_TYPE);
    assertThat(first.get("entityId")).isEqualTo(entityB.toString());
    assertThat(first.get("source")).isEqualTo("API");
    assertThat(first.get("requestId")).isEqualTo(MARKER_REQUEST_ID + ".4");
    assertThat(first.get("reason")).isEqualTo("suite-4");
    assertThat(response.jsonPath().getMap("items[0].details"))
        .as("details jsonb como o banco o guardou")
        .containsEntry("marker", "audit-query-suite");
    assertThat(first.get("ip"))
        .as("inet como endereço, nunca InetAddress cru")
        .isEqualTo("10.0.0.1");
  }

  @Test
  @DisplayName("GET /audit-events: os filtros do §9.3 combinam e recortam o log")
  void filtersByEveryParameter() {
    assertThat(
            reasonsOf(
                query(
                    adminToken(),
                    params("entityType", MARKER_ENTITY_TYPE, "entityId", entityA.toString()))))
        .as("entityId recorta os eventos de um alvo")
        .containsExactly("suite-6", "suite-5", "suite-2", "suite-1");
    assertThat(
            reasonsOf(
                query(
                    adminToken(),
                    params(
                        "entityType", MARKER_ENTITY_TYPE, "actorUserId", otherActorId.toString()))))
        .as("actorUserId recorta os eventos de um ator")
        .containsExactly("suite-4", "suite-3");
    assertThat(
            reasonsOf(
                query(
                    adminToken(),
                    params(
                        "entityType",
                        MARKER_ENTITY_TYPE,
                        "action",
                        "AUDIT_QUERY_ALPHA",
                        "cashSessionId",
                        cashSessionA.toString()))))
        .as("action e cashSessionId recortam juntos")
        .containsExactly("suite-6", "suite-5", "suite-1");

    Response combined =
        query(
            adminToken(),
            params(
                "entityType",
                MARKER_ENTITY_TYPE,
                "entityId",
                entityB.toString(),
                "actorUserId",
                otherActorId.toString(),
                "action",
                "AUDIT_QUERY_ALPHA",
                "cashSessionId",
                cashSessionB.toString()));

    assertThat(reasonsOf(combined))
        .as("os cinco filtros juntos acham um só evento")
        .containsExactly("suite-3");
    assertThat(combined.jsonPath().getLong("totalItems")).isEqualTo(1);

    Response none =
        query(
            adminToken(),
            params("entityType", MARKER_ENTITY_TYPE, "action", "AUDIT_QUERY_UNKNOWN"));

    assertThat(none.statusCode()).isEqualTo(200);
    assertThat(none.jsonPath().getLong("totalItems")).isZero();
    assertThat(none.jsonPath().getInt("totalPages")).isZero();
    assertThat(none.jsonPath().getList("items")).isNullOrEmpty();
  }

  @Test
  @DisplayName(
      "GET /audit-events: from inclusivo e to exclusivo, e valor inválido em 400 com errors[]")
  void appliesPeriodBounds() {
    Response from =
        query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "from", DAY_TWO.toString()));

    assertThat(reasonsOf(from))
        .as("from é inclusivo: o evento exatamente em from entra")
        .containsExactly("suite-4", "suite-3", "suite-6", "suite-5", "suite-2");

    Response to =
        query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "to", DAY_TWO.toString()));

    assertThat(reasonsOf(to))
        .as("to é exclusivo: o evento exatamente em to fica de fora")
        .containsExactly("suite-1");

    Response window =
        query(
            adminToken(),
            params(
                "entityType",
                MARKER_ENTITY_TYPE,
                "from",
                DAY_TWO.toString(),
                "to",
                DAY_THREE.toString()));

    assertThat(reasonsOf(window)).containsExactly("suite-6", "suite-5", "suite-2");

    Response inverted =
        query(
            adminToken(),
            params(
                "entityType",
                MARKER_ENTITY_TYPE,
                "from",
                DAY_FOUR.toString(),
                "to",
                DAY_ONE.toString()));

    assertThat(inverted.statusCode())
        .as("período invertido é uma consulta vazia, não um erro")
        .isEqualTo(200);
    assertThat(inverted.jsonPath().getLong("totalItems")).isZero();

    for (String[] invalid :
        List.of(
            new String[] {"from", "ontem"},
            new String[] {"to", "2026-02-01"},
            new String[] {"entityId", "nao-e-uuid"})) {
      Response response = query(adminToken(), params(invalid[0], invalid[1]));

      assertThat(response.statusCode())
          .as("valor inválido em %s não pode virar 404 do conversor implícito", invalid[0])
          .isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
      assertThat(response.jsonPath().getList("errors.field", String.class))
          .as("o 400 cita o campo %s", invalid[0])
          .containsExactly(invalid[0]);
    }
    assertThat(
            query(adminToken(), params("from", "ontem")).jsonPath().getString("errors[0].message"))
        .as("a mensagem diz o formato esperado")
        .contains("ISO-8601");
  }

  @Test
  @DisplayName("GET /audit-events: sort asc inverte a ordem e o id desempata o mesmo occurred_at")
  void sortsByOccurredAtWithIdTiebreaker() {
    Response ascending =
        query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "sort", "occurredat,asc"));

    assertThat(reasonsOf(ascending))
        .as("occurred_at asc: o mais antigo primeiro")
        .containsExactly("suite-1", "suite-2", "suite-5", "suite-6", "suite-3", "suite-4");

    Response defaultDirection =
        query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "sort", "OCCURREDAT"));

    assertThat(reasonsOf(defaultDirection))
        .as("sort sem direção mantém o default desc, sem diferenciar maiúsculas")
        .containsExactly("suite-4", "suite-3", "suite-6", "suite-5", "suite-2", "suite-1");

    Response descTie =
        query(
            adminToken(),
            params(
                "entityType",
                MARKER_ENTITY_TYPE,
                "from",
                DAY_TWO.toString(),
                "to",
                DAY_THREE.toString()));

    assertThat(reasonsOf(descTie))
        .as("mesmo occurred_at: id desc, o último gravado primeiro")
        .containsExactly("suite-6", "suite-5", "suite-2");

    Response ascTie =
        query(
            adminToken(),
            params(
                "entityType",
                MARKER_ENTITY_TYPE,
                "from",
                DAY_TWO.toString(),
                "to",
                DAY_THREE.toString(),
                "sort",
                "occurredAt,asc"));

    assertThat(reasonsOf(ascTie))
        .as("mesmo occurred_at: id asc, o primeiro gravado primeiro")
        .containsExactly("suite-2", "suite-5", "suite-6");
  }

  @Test
  @DisplayName(
      "GET /audit-events: paginação por page/size, teto de 100 e página fora da regra em 400")
  void paginatesHistory() {
    Response firstPage = query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "size", "2"));

    assertThat(firstPage.statusCode()).isEqualTo(200);
    assertThat(reasonsOf(firstPage)).containsExactly("suite-4", "suite-3");
    assertThat(firstPage.jsonPath().getInt("page")).isZero();
    assertThat(firstPage.jsonPath().getInt("size")).isEqualTo(2);
    assertThat(firstPage.jsonPath().getLong("totalItems")).isEqualTo(6);
    assertThat(firstPage.jsonPath().getInt("totalPages")).isEqualTo(3);

    Response lastPage =
        query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "page", "2", "size", "2"));

    assertThat(reasonsOf(lastPage)).containsExactly("suite-2", "suite-1");
    assertThat(lastPage.jsonPath().getInt("page")).isEqualTo(2);

    Response beyond =
        query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "page", "9", "size", "2"));

    assertThat(beyond.jsonPath().getList("items")).isNullOrEmpty();
    assertThat(beyond.jsonPath().getLong("totalItems")).isEqualTo(6);

    Response capped = query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "size", "250"));

    assertThat(capped.jsonPath().getInt("size")).as("teto de 100 do §9.1").isEqualTo(100);
    assertThat(reasonsOf(capped)).hasSize(6);

    for (Response response :
        List.of(
            query(adminToken(), params("page", "-1")), query(adminToken(), params("size", "0")))) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  @DisplayName("GET /audit-events: sort fora da whitelist responde 400 VALIDATION_ERROR")
  void rejectsSortOutsideWhitelist() {
    for (String sort : List.of("action,asc", "occurredat,sideways", "occurredat,asc,desc")) {
      Response response =
          query(adminToken(), params("entityType", MARKER_ENTITY_TYPE, "sort", sort));

      assertThat(response.statusCode()).as("sort %s", sort).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  @DisplayName(
      "GET /audit-events sem audit.read: OPERADOR autenticado recebe 403 citando audit.read")
  void deniesOperatorWithoutAuditRead() {
    Response response = query(operatorToken, params("entityType", MARKER_ENTITY_TYPE));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Acesso negado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(403);
    assertThat(response.jsonPath().getString("detail")).contains("audit.read");
    assertThat(response.asString())
        .as("a recusa não devolve evento nenhum")
        .doesNotContain("suite-4");
  }

  /**
   * Remove o que o teste comitou: os eventos semeados (marcador exclusivo) e os do OPERADOR criado
   * por ele — as FKs {@code restrict} exigem sessões e papéis antes do usuário.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "delete from audit_events where entity_type = ?", MARKER_ENTITY_TYPE);
      if (operatorId == null) {
        return;
      }
      execute(
          connection,
          "delete from audit_events where actor_user_id = ? or entity_id = ?",
          operatorId,
          operatorId);
      execute(
          connection,
          "delete from audit_events where entity_id in"
              + " (select id from auth_sessions where user_id = ?)",
          operatorId);
      execute(connection, "delete from auth_sessions where user_id = ?", operatorId);
      execute(connection, "delete from user_roles where user_id = ?", operatorId);
      execute(connection, "delete from users where id = ?", operatorId);
    }
  }

  /** GET da consulta com os filtros informados; o status fica com cada teste. */
  private static Response query(String token, Map<String, String> filters) {
    RequestSpecification request = given().header(AUTHORIZATION, "Bearer " + token);
    for (Map.Entry<String, String> filter : filters.entrySet()) {
      request = request.queryParam(filter.getKey(), filter.getValue());
    }
    return request.when().get(PATH).then().extract().response();
  }

  /** Mapa ordenado de filtros a partir de pares chave/valor, para a query da consulta. */
  private static Map<String, String> params(String... keyValues) {
    Map<String, String> filters = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      filters.put(keyValues[index], keyValues[index + 1]);
    }
    return filters;
  }

  /** Motivos dos eventos da página, na ordem em que vieram — o rótulo de cada evento da fixture. */
  private static List<String> reasonsOf(Response response) {
    return response.jsonPath().getList("items.reason", String.class);
  }

  /** Primeiro item do corpo da resposta como mapa. */
  private static Map<String, Object> firstItem(Response response) {
    List<Map<String, Object>> items = response.jsonPath().getList("items");
    assertThat(items).as("item no corpo da resposta").isNotEmpty();
    return items.getFirst();
  }

  /** Seis eventos com {@code occurred_at} fixo: um por dia e três no mesmo instante. */
  private void seedEvents() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      seedEvent(connection, 1, DAY_ONE, entityA, operatorId, cashSessionA, "AUDIT_QUERY_ALPHA");
      seedEvent(connection, 2, DAY_TWO, entityA, operatorId, cashSessionA, "AUDIT_QUERY_BETA");
      seedEvent(connection, 3, DAY_THREE, entityB, otherActorId, cashSessionB, "AUDIT_QUERY_ALPHA");
      seedEvent(connection, 4, DAY_FOUR, entityB, otherActorId, cashSessionB, "AUDIT_QUERY_BETA");
      // Os três do mesmo instante provam o desempate por id, na ordem em que foram gravados.
      seedEvent(connection, 5, DAY_TWO, entityA, operatorId, cashSessionA, "AUDIT_QUERY_ALPHA");
      seedEvent(connection, 6, DAY_TWO, entityA, operatorId, cashSessionA, "AUDIT_QUERY_ALPHA");
    }
  }

  private static void seedEvent(
      Connection connection,
      int sequence,
      Instant occurredAt,
      UUID entityId,
      UUID actorUserId,
      UUID cashSessionId,
      String action)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into audit_events (occurred_at, actor_user_id, actor_username, cash_session_id,"
                + " action, entity_type, entity_id, source, request_id, reason, details, ip)"
                + " values (?::timestamptz, ?, ?, ?, ?, ?, ?, 'API', ?, ?, ?::jsonb, ?::inet)")) {
      statement.setString(1, occurredAt.toString());
      statement.setObject(2, actorUserId);
      statement.setString(3, ACTOR_USERNAME);
      statement.setObject(4, cashSessionId);
      statement.setString(5, action);
      statement.setString(6, MARKER_ENTITY_TYPE);
      statement.setObject(7, entityId);
      statement.setString(8, MARKER_REQUEST_ID + "." + sequence);
      statement.setString(9, "suite-" + sequence);
      statement.setString(10, MARKER_DETAILS);
      statement.setString(11, "10.0.0.1");
      statement.executeUpdate();
    }
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
