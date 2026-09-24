package com.minimarket.sales.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.customers.application.CustomerStore;
import com.minimarket.customers.application.NewCustomer;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rotas de cliente da venda na API (passo 811b) contra PostgreSQL real (Dev Services): a venda é
 * aberta pela própria API (passo 807), o operador nasce pelo caso de uso e loga de verdade (tem
 * {@code sale.create}, a permissão das duas rotas) e os clientes do cenário nascem pela porta do
 * cadastro (502a), sem passar pela API de clientes — o alvo é o vínculo da venda, não o CRUD. O 401
 * sem token é do {@code RouteSecurityTest}; as regras do vínculo (ativo, existência) são do caso de
 * uso do 811a e têm unitários próprios.
 *
 * <p>O request HTTP comita, então o teste confere por SQL o que ficou no banco (coluna {@code
 * customer_id} de {@code sales} e eventos de auditoria) e limpa tudo o que comitou ao final, na
 * ordem que as FKs {@code restrict} exigem: chaves (que referenciam o usuário), itens, vendas,
 * série, eventos, movimentos, sessões de caixa, produtos, clientes, sessões de auth, papéis,
 * usuários e os caixas extras.
 */
@QuarkusTest
class SaleCustomerResourceTest extends IntegrationTestBase {

  private static final String AUTHORIZATION = "Authorization";

  private static final String SALES_PATH = SalesResource.PATH;

  private static final String OPEN_PATH = "/api/v1/cash-registers/%s/open";

  /** Caixa do seed da V11: existe sempre, é a fixture da venda. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  private static final String PASSWORD = "senha-secreta";

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Caso de uso da criação de usuário (passo 107): os atores do teste são fixture, não o alvo. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta do cadastro: os clientes do cenário nascem por aqui, sem passar pela API de clientes. */
  @Inject CustomerStore customerStore;

  /** Porta da loja: o cliente novo precisa da loja resolvida, como no 502b. */
  @Inject StoreLookup storeLookup;

  /** Loja única do MVP: o mesmo código que o caso de uso do cadastro usa. */
  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Chaves de idempotência usadas pelo teste: a limpeza apaga exatamente elas. */
  private final List<String> idempotencyKeys = new ArrayList<>();

  /** Vendas abertas pelo teste. */
  private final List<UUID> saleIds = new ArrayList<>();

  /** Sessões de caixa abertas pelo teste. */
  private final List<UUID> cashSessionIds = new ArrayList<>();

  /** Usuários criados pelo teste (e as sessões de auth, papéis e eventos deles). */
  private final List<UUID> userIds = new ArrayList<>();

  /** Clientes semeados pelo teste. */
  private final List<UUID> customerIds = new ArrayList<>();

  /** Caixas extras criados pelo cenário do 403. */
  private final List<UUID> cashRegisterIds = new ArrayList<>();

  /** OPERADOR dono da venda: {@code sale.create} é a permissão das rotas de cliente. */
  private String username;

  private String token;

  private UUID registerId;

  private UUID saleId;

  /** Cliente ativo do cenário. */
  private UUID activeCustomerId;

  /** Cliente desativado do cenário: existe, mas não vende (422). */
  private UUID inactiveCustomerId;

  private UUID storeId;

  @BeforeEach
  void openSaleWithCustomers() throws SQLException {
    username = "vendas.cliente." + SUFFIX + "." + UUID.randomUUID().toString().substring(0, 6);
    createUser(username, List.of("OPERADOR"));
    registerId = cashRegisterId(SEEDED_REGISTER_CODE);
    token = login(username, registerId);
    cashSessionIds.add(open(registerId, token));
    saleId = createSale(token);
    activeCustomerId = seedCustomer("Ana Souza", true);
    inactiveCustomerId = seedCustomer("Bruno Lima", false);
  }

  @Test
  @DisplayName("PUT cliente: 200 com o vínculo no corpo e no banco; DELETE devolve a venda anônima")
  void linksAndUnlinksCustomer() throws SQLException {
    Response linked = putCustomer(token, activeCustomerId);

    assertThat(linked.statusCode()).isEqualTo(200);
    assertThat(linked.contentType()).contains("application/json");
    Map<String, Object> body = linked.jsonPath().getMap("$");

    assertThat(body.get("customerId")).isEqualTo(activeCustomerId.toString());
    assertThat(body.get("discountType")).as("o vínculo não mexe no desconto").isNull();
    assertThat(decimal(body, "total")).isEqualByComparingTo("0.00");
    assertThat(saleCustomerId(saleId)).isEqualTo(activeCustomerId);

    JsonPath details = JsonPath.from(onlyEventDetails(saleId, "SALE_CUSTOMER_LINKED"));

    assertThat(details.getString("customerId")).isEqualTo(activeCustomerId.toString());
    assertThat(details.getString("customerName")).isEqualTo("Ana Souza");

    Response unlinked = deleteCustomer(token);

    assertThat(unlinked.statusCode()).isEqualTo(200);
    assertThat(unlinked.jsonPath().getMap("$").get("customerId")).isNull();
    assertThat(saleCustomerId(saleId)).as("a coluna volta a nulo").isNull();
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_UNLINKED")).isEqualTo(1);
  }

  @Test
  @DisplayName("PUT de outro cliente substitui o vínculo anterior")
  void replacesPreviousCustomer() throws SQLException {
    UUID otherCustomerId = seedCustomer("Carla Dias", true);

    assertThat(putCustomer(token, activeCustomerId).statusCode()).isEqualTo(200);

    Response replaced = putCustomer(token, otherCustomerId);

    assertThat(replaced.statusCode()).isEqualTo(200);
    assertThat(replaced.jsonPath().getString("customerId")).isEqualTo(otherCustomerId.toString());
    assertThat(saleCustomerId(saleId)).isEqualTo(otherCustomerId);
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_LINKED"))
        .as("cada vínculo é uma operação auditada")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("cliente desativado: 422 CUSTOMER_INACTIVE sem vincular nem auditar")
  void rejectsInactiveCustomer() throws SQLException {
    Response response = putCustomer(token, inactiveCustomerId);

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("CUSTOMER_INACTIVE");
    assertThat(saleCustomerId(saleId)).isNull();
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_LINKED")).isZero();
  }

  @Test
  @DisplayName("cliente inexistente: 404 CUSTOMER_NOT_FOUND sem vincular nem auditar")
  void rejectsUnknownCustomer() throws SQLException {
    Response response = putCustomer(token, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("CUSTOMER_NOT_FOUND");
    assertThat(saleCustomerId(saleId)).isNull();
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_LINKED")).isZero();
  }

  @Test
  @DisplayName("forma inválida: 400 VALIDATION_ERROR sem customerId")
  void rejectsInvalidForm() throws SQLException {
    Response response =
        given()
            .header(AUTHORIZATION, "Bearer " + token)
            .contentType("application/json")
            .body("{}")
            .when()
            .put(SALES_PATH + "/" + saleId + "/customer")
            .then()
            .extract()
            .response();

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(saleCustomerId(saleId)).isNull();
  }

  @Test
  @DisplayName("DELETE sem cliente vinculado é no-op: 200 com a venda intacta e sem evento")
  void unlinkWithoutCustomerIsNoOp() throws SQLException {
    Response response = deleteCustomer(token);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getMap("$").get("customerId")).isNull();
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_UNLINKED")).isZero();
  }

  @Test
  @DisplayName("venda de outro caixa: 403 ACCESS_DENIED nos dois, sem tocar na venda")
  void deniesSaleOfAnotherRegister() throws SQLException {
    UUID otherRegister = insertCashRegister();
    String otherToken = login(username, otherRegister);

    Response linked = putCustomer(otherToken, activeCustomerId);
    Response unlinked = deleteCustomer(otherToken);

    for (Response response : List.of(linked, unlinked)) {
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
      assertThat(response.jsonPath().getString("detail"))
          .as("a recusa é da guarda de posse (BR-11), não do porteiro da rota")
          .contains(saleId.toString())
          .doesNotContain("sale.create");
    }

    assertThat(saleCustomerId(saleId)).isNull();
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_LINKED")).isZero();
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_UNLINKED")).isZero();
  }

  @Test
  @DisplayName("venda concluída: 409 SALE_NOT_OPEN nos dois, sem tocar na venda")
  void rejectsCompletedSale() throws SQLException {
    assertThat(putCustomer(token, activeCustomerId).statusCode()).isEqualTo(200);
    completeSale(saleId);

    Response linked = putCustomer(token, inactiveCustomerId);
    Response unlinked = deleteCustomer(token);

    for (Response response : List.of(linked, unlinked)) {
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("SALE_NOT_OPEN");
    }

    assertThat(saleCustomerId(saleId))
        .as("o cliente da venda concluída fica onde está")
        .isEqualTo(activeCustomerId);
    assertThat(auditEventCount(saleId, "SALE_CUSTOMER_UNLINKED")).isZero();
  }

  /**
   * Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}: chaves
   * (primeiro, porque referenciam o usuário), itens, vendas, série, eventos, movimentos, sessões de
   * caixa, clientes, sessões de auth, papéis, usuários e caixas extras, nessa ordem.
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
      for (UUID id : customerIds) {
        execute(connection, "delete from customers where id = ?", id);
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
      for (UUID id : cashRegisterIds) {
        execute(connection, "delete from cash_registers where id = ?", id);
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

  /**
   * Cliente do cenário pela porta do cadastro, em transação própria como o 502b o grava; o
   * desativado passa pelo mesmo soft delete do {@code POST /{id}/disable}.
   */
  private UUID seedCustomer(String name, boolean active) {
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    customerStore.insert(new NewCustomer(storeId(), name, null, null, null, null)));
    customerIds.add(id);
    if (!active) {
      QuarkusTransaction.requiringNew().run(() -> customerStore.disable(id));
    }
    return id;
  }

  /** Id da loja configurada pela porta {@code StoreLookup}, sem importar infrastructure alheia. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      storeLookup
                          .findByCode(defaultStoreCode)
                          .orElseThrow(
                              () -> new IllegalStateException("loja do seed da V1 ausente"))
                          .id());
    }
    return storeId;
  }

  /** Caixa extra do cenário do 403: a venda de um caixa não é operável pelo outro. */
  private UUID insertCashRegister() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into cash_registers (id, store_id, code, name)"
                    + " values (uuidv7(), (select id from stores where code = 'MATRIZ'), ?,"
                    + " 'Caixa de teste') returning id")) {
      statement.setString(1, "CLIENTE." + SUFFIX);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        UUID id = resultSet.getObject("id", UUID.class);
        cashRegisterIds.add(id);
        return id;
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

  /** PUT do vínculo na venda do cenário; o status fica com cada teste. */
  private Response putCustomer(String token, UUID customerId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body("{\"customerId\": \"%s\"}".formatted(customerId))
        .when()
        .put(SALES_PATH + "/" + saleId + "/customer")
        .then()
        .extract()
        .response();
  }

  /** DELETE do vínculo na venda do cenário; o status fica com cada teste. */
  private Response deleteCustomer(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .delete(SALES_PATH + "/" + saleId + "/customer")
        .then()
        .extract()
        .response();
  }

  /** Chave de idempotência nova por chamada, rastreada para a limpeza. */
  private String newKey() {
    String key = "vendas.cliente." + SUFFIX + "." + UUID.randomUUID();
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

  /** Cliente vinculado na linha da venda, como o banco o guardou. */
  private UUID saleCustomerId(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select customer_id from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return resultSet.getObject("customer_id", UUID.class);
      }
    }
  }

  /**
   * {@code details} do único evento da ação para o alvo — a conferência é por {@code entity_id} +
   * {@code action}, nunca por contagem global, que outras linhas do log poderiam inflar.
   */
  private String onlyEventDetails(UUID entityId, String action) throws SQLException {
    assertThat(auditEventCount(entityId, action)).as("um evento %s", action).isEqualTo(1);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select details::text as details from audit_events"
                    + " where entity_id = ? and action = ?")) {
      statement.setObject(1, entityId);
      statement.setString(2, action);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString("details");
      }
    }
  }

  /** Eventos da ação para o alvo: um por operação efetivada, zero por tentativa barrada. */
  private int auditEventCount(UUID entityId, String action) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where entity_id = ? and action = ?", entityId, action);
  }

  /** Campo de dinheiro do corpo como número (o JSON pode vir como float ou BigDecimal). */
  private static BigDecimal decimal(Map<String, Object> body, String field) {
    assertThat(body.get(field)).as("campo %s no corpo", field).isNotNull();
    return new BigDecimal(String.valueOf(body.get(field)));
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
}
