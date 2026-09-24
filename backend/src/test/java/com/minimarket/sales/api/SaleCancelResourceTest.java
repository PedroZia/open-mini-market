package com.minimarket.sales.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
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
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cancelamento da venda aberta na API (passo 813) contra PostgreSQL real (Dev Services): a venda é
 * aberta pela própria API (passo 807), o item entra pela rota do 809b e o ator da venda é um
 * GERENTE real (tem {@code sale.cancel}), como no {@code SaleDiscountResourceTest} — a matriz de
 * permissões é exercitada com usuário de verdade e login de verdade, não com dublê. O 401 sem token
 * é do {@code RouteSecurityTest}; as regras do cancelamento (permissão, posse, motivo, estado) são
 * do caso de uso e têm unitários próprios.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (colunas de
 * cancelamento de {@code sales} e o evento {@code SALE_CANCELLED}) e limpa tudo o que comitou ao
 * final, na ordem que as FKs {@code restrict} exigem: chaves (que referenciam o usuário), itens,
 * vendas, série, eventos, movimentos, sessões de caixa, produtos, sessões de auth, papéis e
 * usuários.
 */
@QuarkusTest
class SaleCancelResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "18";

  private static final String PRODUCT_NAME = "Arroz 5kg";

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do catálogo: o produto do cenário nasce por aqui, sem passar pela API de cadastro. */
  @Inject ProductStore productStore;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste. */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Produtos semeados pelo teste. */
  private final List<UUID> productIds = new ArrayList<>();

  /** GERENTE dono da venda: tem {@code sale.cancel} (seed da {@code V3__rbac.sql}). */
  private String username;

  private String token;

  private UUID registerId;

  private UUID saleId;

  private UUID productId;

  @BeforeEach
  void openSaleWithItem() throws SQLException {
    username = "vendas.cancel." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(username, List.of("GERENTE"));
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, token));
    saleId = createSale(token);
    productId = seedProduct();
    assertThat(postItem(token, productId).statusCode()).as("item da fixture").isEqualTo(200);
  }

  @Test
  @DisplayName(
      "POST cancel: 200 com status CANCELLED, motivo, autor e instante no corpo e no banco")
  void cancelsOpenSale() throws SQLException {
    Response response = cancel(token, newKey(), "{\"reason\": \"cliente desistiu\"}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    Map<String, Object> body = response.jsonPath().getMap("$");

    assertThat(body.get("status")).isEqualTo("CANCELLED");
    assertThat(body.get("cancelReason")).isEqualTo("cliente desistiu");
    assertThat(body.get("cancelledByUserId"))
        .as("o autor é o ator do token, não o cliente")
        .isEqualTo(userId(username).toString());
    assertThat(body.get("cancelledAt")).isNotNull();
    assertThat(body.get("completedAt")).as("cancelar não conclui").isNull();
    assertThat(body.get("itemCount")).as("os itens ficam no histórico").isEqualTo(1);
    assertThat(new BigDecimal(String.valueOf(body.get("total")))).isEqualByComparingTo("19.80");

    SaleRow stored = saleRow(saleId);

    assertThat(stored.status()).isEqualTo("CANCELLED");
    assertThat(stored.cancelReason()).isEqualTo("cliente desistiu");
    assertThat(stored.cancelledByUserId()).isEqualTo(userId(username));
    assertThat(stored.cancelledAt()).isNotNull();
    assertThat(stored.completedAt()).isNull();
    assertThat(auditEventCount(saleId, "SALE_CANCELLED")).isEqualTo(1);
  }

  @Test
  @DisplayName("mesma Idempotency-Key: replay com o mesmo corpo e um único evento")
  void replaysSameKey() throws SQLException {
    String key = newKey();

    Response first = cancel(token, key, "{\"reason\": \"cliente desistiu\"}");
    assertThat(first.statusCode()).isEqualTo(200);

    Response replay = cancel(token, key, "{\"reason\": \"cliente desistiu\"}");

    assertThat(replay.statusCode()).as("o retry devolve o mesmo 200").isEqualTo(200);
    assertThat(replay.getHeader(IdempotencyGuard.REPLAYED_HEADER))
        .as("a resposta veio do registro, sem cancelar de novo")
        .isEqualTo("true");
    assertThat(replay.jsonPath().getMap("$"))
        .as("mesmo JSON da primeira chamada")
        .isEqualTo(first.jsonPath().getMap("$"));
    assertThat(auditEventCount(saleId, "SALE_CANCELLED")).as("um evento, não dois").isEqualTo(1);
    assertThat(countIdempotencyKeys(key)).as("um registro de idempotência").isEqualTo(1);
  }

  @Test
  @DisplayName("venda já cancelada com chave nova: 200 no-op, sem novo evento")
  void treatsSecondCancellationAsNoOp() throws SQLException {
    assertThat(cancel(token, newKey(), "{\"reason\": \"cliente desistiu\"}").statusCode())
        .isEqualTo(200);

    Response again = cancel(token, newKey(), "{\"reason\": \"outro motivo\"}");

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("status")).isEqualTo("CANCELLED");
    assertThat(again.jsonPath().getString("cancelReason"))
        .as("o motivo do primeiro cancelamento é o que vale")
        .isEqualTo("cliente desistiu");
    assertThat(auditEventCount(saleId, "SALE_CANCELLED")).as("um evento só").isEqualTo(1);
  }

  @Test
  @DisplayName("venda cancelada continua consultável e recusa mutação com 409, nunca 500")
  void cancelledSaleIsReadableAndRefusesMutation() throws SQLException {
    assertThat(cancel(token, newKey(), "{\"reason\": \"cliente desistiu\"}").statusCode())
        .isEqualTo(200);

    Response detail =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .get(SALES_PATH + "/" + saleId)
            .then()
            .extract()
            .response();

    assertThat(detail.statusCode()).as("o detalhe rehidrata CANCELLED").isEqualTo(200);
    assertThat(detail.jsonPath().getString("status")).isEqualTo("CANCELLED");
    assertThat(detail.jsonPath().getString("cancelReason")).isEqualTo("cliente desistiu");
    assertThat(detail.jsonPath().getList("items")).hasSize(1);

    Response removeItem =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .when()
            .delete(SALES_PATH + "/" + saleId + "/items/" + productId)
            .then()
            .extract()
            .response();

    assertThat(removeItem.statusCode()).as("venda cancelada é imutável").isEqualTo(409);
    assertThat(removeItem.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");
    assertThat(itemCountOf(saleId)).as("o item continua na venda").isEqualTo(1);
  }

  @Test
  @DisplayName("venda concluída: 409 SALE_ALREADY_COMPLETED sem cancelar")
  void rejectsCompletedSale() throws SQLException {
    completeSale(saleId);

    Response response = cancel(token, newKey(), "{\"reason\": \"cliente desistiu\"}");

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_ALREADY_COMPLETED");
    assertThat(saleRow(saleId).status()).isEqualTo("COMPLETED");
    assertThat(auditEventCount(saleId, "SALE_CANCELLED")).isZero();
  }

  @Test
  @DisplayName("motivo ausente ou em branco: 400 VALIDATION_ERROR sem cancelar")
  void rejectsBlankReason() throws SQLException {
    Response withoutReason = cancel(token, newKey(), "{}");
    Response blankReason = cancel(token, newKey(), "{\"reason\": \"  \"}");

    for (Response response : List.of(withoutReason, blankReason)) {
      assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    assertThat(saleRow(saleId).status())
        .as("a forma inválida nem chega ao caso de uso")
        .isEqualTo("OPEN");
    assertThat(auditEventCount(saleId, "SALE_CANCELLED")).isZero();
  }

  @Test
  @DisplayName("OPERADOR sem sale.cancel: 403 ACCESS_DENIED sem cancelar")
  void deniesOperatorWithoutPermission() throws SQLException {
    String operatorUsername =
        "vendas.cancel.op." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(operatorUsername, List.of("OPERADOR"));
    String operatorToken = login(operatorUsername, registerId);

    Response response = cancel(operatorToken, newKey(), "{\"reason\": \"cliente desistiu\"}");

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail"))
        .as("a recusa é do porteiro da rota, citando a permissão que falta")
        .contains("sale.cancel");
    assertThat(saleRow(saleId).status()).isEqualTo("OPEN");
    assertThat(auditEventCount(saleId, "SALE_CANCELLED")).isZero();
  }

  @Test
  @DisplayName("venda inexistente: 404 SALE_NOT_FOUND")
  void rejectsUnknownSale() {
    Response response =
        cancel(token, newKey(), UUID.randomUUID(), "{\"reason\": \"cliente desistiu\"}");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_FOUND");
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), itens, vendas, série, eventos, movimentos, sessões de
   * caixa, produtos, sessões de auth, papéis e usuários, nessa ordem.
   */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String key : idempotencyKeys) {
        execute(connection, "delete from idempotency_keys where key = ?", key);
      }
      for (UUID id : saleIds) {
        execute(connection, "delete from sale_items where sale_id = ?", id);
        execute(connection, "delete from sales where id = ?", id);
        execute(connection, "delete from audit_events where entity_id = ?", id);
      }
      for (UUID sessionId : cashSessionIds) {
        execute(connection, "delete from cash_movements where cash_session_id = ?", sessionId);
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
        execute(connection, "delete from audit_events where entity_id = ?", sessionId);
      }
      execute(connection, "delete from document_sequences where doc_type = 'SALE'");
      for (UUID id : productIds) {
        execute(connection, "delete from products where id = ?", id);
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
  private UUID open(UUID registerId, String token) {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .contentType("application/json")
            .body("{\"openingAmount\": 100.00}")
            .when()
            .post(OPEN_PATH.formatted(registerId))
            .then()
            .statusCode(201)
            .extract()
            .response();
    return UUID.fromString(response.jsonPath().getString("id"));
  }

  /** Abre a venda pela API (passo 807) com chave nova e devolve o id da venda comitada. */
  private UUID createSale(String token) {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .header(IdempotencyGuard.KEY_HEADER, newKey())
            .when()
            .post(SALES_PATH)
            .then()
            .statusCode(201)
            .extract()
            .response();
    UUID id = UUID.fromString(response.jsonPath().getString("id"));
    saleIds.add(id);
    return id;
  }

  /** Produto do cenário pela porta do catálogo, em transação própria como o 405 o grava. */
  private UUID seedProduct() throws SQLException {
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    productStore.insert(
                        new NewProduct(
                            storeId(),
                            PRODUCT_NAME,
                            BARCODE,
                            null,
                            null,
                            "UN",
                            new BigDecimal("9.90"),
                            null)));
    productIds.add(id);
    return id;
  }

  /** Item da venda pela rota do 809b: 2 × 9,90 = 19,80 de subtotal. */
  private Response postItem(String token, UUID productId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"productId\": \"%s\", \"quantity\": 2}".formatted(productId))
        .when()
        .post(SALES_PATH + "/" + saleId + "/items")
        .then()
        .extract()
        .response();
  }

  /** POST do cancelamento na venda do cenário; o status fica com cada teste. */
  private Response cancel(String token, String key, String body) {
    return cancel(token, key, saleId, body);
  }

  /** POST do cancelamento na venda informada; o status fica com cada teste. */
  private Response cancel(String token, String key, UUID id, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .header(IdempotencyGuard.KEY_HEADER, key)
        .contentType("application/json")
        .body(body)
        .when()
        .post(SALES_PATH + "/" + id + "/cancel")
        .then()
        .extract()
        .response();
  }

  /** Id da loja do seed, direto do banco: o produto precisa de uma loja real (FK restrict). */
  private UUID storeId() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from stores where code = 'MATRIZ'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("loja MATRIZ do seed da V1 presente").isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /** Marca a venda como concluída direto no banco: a conclusão pela API só nasce na Fase 9. */
  private void completeSale(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "update sales set status = 'COMPLETED', completed_at = now() where id = ?",
          id);
    }
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.cancelamento." + SUFFIX + "." + UUID.randomUUID();
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

  /** Login pela API (passo 205) vinculando a sessão ao caixa informado. */
  private static String login(String username, UUID cashRegisterId) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
            """
                .formatted(username, PASSWORD, cashRegisterId))
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

  /** Colunas de cancelamento e status de {@code sales} como o banco as guardou. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, cancel_reason, cancelled_by_user_id, cancelled_at, completed_at"
                    + " from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            resultSet.getString("cancel_reason"),
            resultSet.getObject("cancelled_by_user_id", UUID.class),
            resultSet.getObject("cancelled_at"),
            resultSet.getObject("completed_at"));
      }
    }
  }

  /** Itens da venda no banco: a venda cancelada não pode perder os seus. */
  private int itemCountOf(UUID saleId) throws SQLException {
    return queryInt("select count(*) from sale_items where sale_id = ?", saleId);
  }

  /** Eventos da ação para o alvo: um por operação efetivada, zero por tentativa barrada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
  }

  /** Registros da chave de idempotência: um por operação, não um por tentativa. */
  private int countIdempotencyKeys(String key) throws SQLException {
    return queryInt("select count(*) from idempotency_keys where key = ?", key);
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

  /** Colunas de cancelamento e status de {@code sales} como o banco as guardou. */
  private record SaleRow(
      String status,
      String cancelReason,
      UUID cancelledByUserId,
      Object cancelledAt,
      Object completedAt) {}
}
