package com.minimarket.sales.api;

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
 * Abertura de venda na API (passo 807) contra PostgreSQL real (Dev Services): a rota de verdade,
 * com OPERADOR criado pelo caminho de aplicação, login real vinculado ao {@code CAIXA-01} do seed e
 * a sessão de caixa aberta pela própria API (passo 607) — o mesmo caminho da TUI. O 401 sem token é
 * do {@code RouteSecurityTest}; a regra da abertura é do {@code CreateSaleUseCase} (passo 805) e a
 * da idempotência, do {@code IdempotencyGuard} (607a).
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (linha {@code OPEN}
 * em {@code sales}, evento {@code SALE_CREATED} e registro de idempotência) e limpa tudo o que
 * comitou ao final, na ordem que as FKs {@code restrict} exigem: chaves (que referenciam o
 * usuário), itens, vendas, série, eventos, movimentos, sessões de caixa, sessões de auth, papéis e
 * usuários. O banco é compartilhado e o caixa do seed é o mesmo de outros testes; o {@link
 * TestAdmin} apaga as sessões e o usuário dele depois dos {@code @AfterEach} desta classe.
 */
@QuarkusTest
class SalesResourceTest extends IntegrationTestBase {

  private static final String KEY_HEADER = IdempotencyGuard.KEY_HEADER;

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da abertura da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste. */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  @Test
  @DisplayName(
      "abre a venda: 201 com número, status OPEN e totais zerados, e a linha vazia no banco")
  void createsOpenSale() throws SQLException {
    String username = "vendas.api." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    UUID operatorId = userId(username);
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    String token = login(username, registerId);
    UUID sessionId = open(registerId, token);

    Response response = createSale(newKey(), token);

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.getHeader("Location"))
        .as("a rota de leitura da venda só nasce no passo 812")
        .isNull();

    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body)
        .as("contrato da abertura: nem storeId nem version")
        .containsOnlyKeys(
            "id",
            "number",
            "status",
            "cashSessionId",
            "cashRegisterId",
            "operatorUserId",
            "subtotal",
            "discountAmount",
            "total",
            "itemCount",
            "createdAt");
    UUID saleId = UUID.fromString((String) body.get("id"));
    saleIds.add(saleId);
    assertThat(saleId.version()).as("id da venda é UUIDv7").isEqualTo(7);
    assertThat(number(body, "number"))
        .as("número vem do alocador da loja")
        .isGreaterThanOrEqualTo(1L);
    assertThat(body.get("status")).isEqualTo("OPEN");
    assertThat(body.get("cashSessionId")).isEqualTo(sessionId.toString());
    assertThat(body.get("cashRegisterId")).isEqualTo(registerId.toString());
    assertThat(body.get("operatorUserId"))
        .as("o operador é o ator do token, não o cliente")
        .isEqualTo(operatorId.toString());
    assertThat(money(body, "subtotal")).as("venda nasce vazia").isEqualByComparingTo("0.00");
    assertThat(money(body, "discountAmount")).isEqualByComparingTo("0.00");
    assertThat(money(body, "total")).isEqualByComparingTo("0.00");
    assertThat(number(body, "itemCount")).isZero();
    assertThat(body.get("createdAt")).isNotNull();

    SaleRow stored = saleRow(saleId);

    assertThat(stored.status()).isEqualTo("OPEN");
    assertThat(stored.number()).isEqualTo(number(body, "number"));
    assertThat(stored.cashSessionId()).isEqualTo(sessionId);
    assertThat(stored.cashRegisterId()).isEqualTo(registerId);
    assertThat(stored.operatorUserId()).isEqualTo(operatorId);
    assertThat(stored.customerId()).as("cliente é do passo 811").isNull();
    assertThat(stored.subtotal()).isEqualTo("0.00");
    assertThat(stored.discountAmount()).isEqualTo("0.00");
    assertThat(stored.total()).isEqualTo("0.00");
    assertThat(stored.itemCount()).isZero();
    assertThat(stored.completedAt()).isNull();
    assertThat(itemCountOf(saleId)).as("venda nasce sem itens").isZero();
    assertThat(auditEventCount(saleId, "SALE_CREATED")).isEqualTo(1);
  }

  @Test
  @DisplayName("mesma Idempotency-Key: replay com o mesmo corpo e uma única venda")
  void replaysSameKey() throws SQLException {
    String username = "vendas.replay." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    String token = login(username, registerId);
    UUID sessionId = open(registerId, token);
    String key = newKey();

    Response first = createSale(key, token);
    assertThat(first.statusCode()).isEqualTo(201);
    UUID saleId = UUID.fromString(first.jsonPath().getString("id"));
    saleIds.add(saleId);

    Response replay = createSale(key, token);

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 201").isEqualTo(201);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem abrir outra venda")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));

    assertThat(countSales(sessionId)).as("uma venda, não duas").isEqualTo(1);
    assertThat(auditEventCount(saleId, "SALE_CREATED")).as("um evento, não dois").isEqualTo(1);
    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
  }

  @Test
  @DisplayName("sem Idempotency-Key: 400 IDEMPOTENCY_KEY_REQUIRED sem abrir a venda")
  void rejectsMissingIdempotencyKey() throws SQLException {
    String username = "vendas.sem-chave." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    String token = login(username, registerId);
    UUID sessionId = open(registerId, token);

    Response response = createSale(null, token);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(countSales(sessionId)).as("sem chave a operação nem roda").isZero();
  }

  @Test
  @DisplayName("caixa vinculado sem sessão aberta: 409 CASH_SESSION_REQUIRED sem abrir a venda")
  void rejectsRegisterWithoutOpenSession() throws SQLException {
    String username = "vendas.sem-sessao." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    UUID operatorId = userId(username);
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    String token = login(username, registerId);

    Response response = createSale(newKey(), token);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("CASH_SESSION_REQUIRED");
    assertThat(countSalesOfOperator(operatorId)).as("nenhuma venda sem sessão aberta").isZero();
  }

  @Test
  @DisplayName("usuário sem sale.create: 403 ACCESS_DENIED sem abrir a venda")
  void deniesUserWithoutSaleCreatePermission() throws SQLException {
    String username = "vendas.sem-permissao." + SUFFIX;
    createUser(username, List.of());
    UUID operatorId = userId(username);
    UUID registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    String token = login(username, registerId);
    String key = newKey();

    Response response = createSale(key, token);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail"))
        .as("a recusa é do porteiro da rota, citando a permissão")
        .contains("sale.create");
    assertThat(countSalesOfOperator(operatorId)).as("quem não pode vender não abre venda").isZero();
    assertThat(countIdempotencyKeys(key)).as("o 403 barra antes da idempotência").isZero();
  }

  @Test
  @DisplayName("sessão autenticada sem caixa vinculado: 403 do caso de uso, sem abrir a venda")
  void deniesSessionWithoutBoundRegister() throws SQLException {
    String username = "vendas.sem-caixa." + SUFFIX;
    createUser(username, List.of("OPERADOR"));
    UUID operatorId = userId(username);
    String token = login(username, null);

    Response response = createSale(newKey(), token);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail"))
        .as("a recusa é do passo 805, não do porteiro da rota")
        .contains("caixa vinculado")
        .doesNotContain("sale.create");
    assertThat(countSalesOfOperator(operatorId)).as("sem vínculo de caixa não há venda").isZero();
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), itens, vendas, série, eventos, movimentos, sessões de
   * caixa, sessões de auth, papéis e usuários, nessa ordem.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID saleId : saleIds) {
        execute(connection, "delete from sale_items where sale_id = ?", saleId);
        execute(connection, "delete from sales where id = ?", saleId);
        execute(connection, "delete from audit_events where entity_id = ?", saleId);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
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

  /**
   * POST /sales sem corpo e sem {@code Content-Type} — é assim que a TUI abre a venda; chave nula é
   * o cenário do header ausente.
   */
  private static Response createSale(String key, String token) {
    RequestSpecification request = given().header("Authorization", "Bearer " + token);
    if (key != null) {
      request = request.header(KEY_HEADER, key);
    }
    return request.when().post(SALES_PATH).then().extract().response();
  }

  /** Abre o caixa pela API (passo 607) com chave nova e devolve o id da sessão comitada. */
  private UUID open(UUID registerId, String token) {
    Response response =
        given()
            .header("Authorization", "Bearer " + token)
            .header(KEY_HEADER, newKey())
            .contentType("application/json")
            .body("{\"openingAmount\": 100.00}")
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

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.abertura." + SUFFIX + "." + UUID.randomUUID();
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

  /**
   * Login pela API (passo 205) vinculando ou não a sessão ao caixa: o vínculo é o que a venda usa
   * como caixa (BR-11) e o {@code null} é o cenário da sessão sem caixa.
   */
  private static String login(String username, UUID cashRegisterId) {
    String body =
        cashRegisterId == null
            ? """
              {"username": "%s", "password": "%s"}
              """
                .formatted(username, PASSWORD)
            : """
              {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
              """
                .formatted(username, PASSWORD, cashRegisterId);
    return given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** Id do usuário criado pelo teste. */
  private UUID userId(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from users where username = ?")) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("usuário %s criado", username).isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /** Linha de {@code sales} como o banco a guardou. */
  private SaleRow saleRow(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, number, cash_session_id, cash_register_id, operator_user_id,"
                    + " customer_id, subtotal::text as subtotal, discount_amount::text as"
                    + " discount_amount, total::text as total, item_count, completed_at from sales"
                    + " where id = ?")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", saleId).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            resultSet.getLong("number"),
            resultSet.getObject("cash_session_id", UUID.class),
            resultSet.getObject("cash_register_id", UUID.class),
            resultSet.getObject("operator_user_id", UUID.class),
            resultSet.getObject("customer_id", UUID.class),
            resultSet.getString("subtotal"),
            resultSet.getString("discount_amount"),
            resultSet.getString("total"),
            resultSet.getInt("item_count"),
            resultSet.getObject("completed_at"));
      }
    }
  }

  /** Quantos itens a venda tem no banco; a venda da abertura nasce vazia. */
  private int itemCountOf(UUID saleId) throws SQLException {
    return queryInt("select count(*) from sale_items where sale_id = ?", saleId);
  }

  /** Vendas da sessão de caixa: o replay não pode deixar uma segunda. */
  private int countSales(UUID sessionId) throws SQLException {
    return queryInt("select count(*) from sales where cash_session_id = ?", sessionId);
  }

  /** Vendas do operador: as recusas não podem deixar linha. */
  private int countSalesOfOperator(UUID operatorUserId) throws SQLException {
    return queryInt("select count(*) from sales where operator_user_id = ?", operatorUserId);
  }

  /** Eventos da ação para o alvo: um por operação efetivada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
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

  /** Campo numérico do corpo (Integer ou Long) como long. */
  private static long number(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return ((Number) body.get(field)).longValue();
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

  /** Linha de {@code sales} como o banco a guardou. */
  private record SaleRow(
      String status,
      long number,
      UUID cashSessionId,
      UUID cashRegisterId,
      UUID operatorUserId,
      UUID customerId,
      String subtotal,
      String discountAmount,
      String total,
      int itemCount,
      Object completedAt) {}
}
