package com.minimarket.customers.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.customers.application.CustomerStore;
import com.minimarket.customers.application.NewCustomer;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clientes na API: cadastro, listagem com busca, detalhe, edição e desativação (passo 502b) contra
 * PostgreSQL real (Dev Services): as rotas de verdade, com o ADMIN da fixture e um OPERADOR criado
 * pelo caso de uso e autenticado por login real, como no {@code PermissionMatrixTest}.
 *
 * <p>A busca é semeada pela porta {@code CustomerStore} (sem caso de uso nem auditoria, em
 * transação própria); o cadastro passa pela API para provar a normalização de CPF e telefone que só
 * o caso de uso faz. O request HTTP commita: o {@link #removeRowsCreatedByThisTest()} apaga ao fim
 * de cada teste os clientes do sufixo desta classe (e os eventos de auditoria que os referenciam),
 * o usuário OPERADOR, as sessões e os eventos dele — os do ADMIN já saem pelo {@code
 * TestAdmin.remove}.
 */
@QuarkusTest
class CustomersResourceTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/customers";
  private static final String OPERATOR_ROLE = "OPERADOR";

  /**
   * CPF de verdade do cenário: a máscara é o que a API recebe, os dígitos é o que o banco guarda.
   */
  private static final String ANA_CPF = "11144477735";

  private static final String ANA_MASKED_CPF = "111.444.777-35";
  private static final String BRUNO_CPF = "52998224725";
  private static final String BRUNO_MASKED_CPF = "529.982.247-25";
  private static final String OTHER_CPF = "39053344705";

  /** Caso de uso da criação de usuário: o OPERADOR é fixture, não o alvo do teste. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Porta para semear a listagem sem passar pelos casos de uso (nem pela auditoria). */
  @Inject CustomerStore customerStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Id da loja do seed da V1, resolvido uma vez por teste. */
  private UUID storeId;

  @Test
  @DisplayName(
      "ADMIN cria cliente: 201 com Location e corpo com CPF e telefone normalizados pelo caso de uso")
  void createsCustomer() throws SQLException {
    String name = name("Ana Souza");

    Response response =
        post(
            adminToken(),
            """
            {"name": "%s", "taxId": "%s", "phone": "(11) 91234-5678", "email": "ana@exemplo.com",
             "notes": "vizinho"}
            """
                .formatted(name, ANA_MASKED_CPF));

    assertThat(response.statusCode()).isEqualTo(201);
    String id = response.jsonPath().getString("id");
    assertThat(UUID.fromString(id).version()).as("id é UUIDv7").isEqualTo(7);
    assertThat(response.header("Location")).endsWith(PATH + "/" + id);

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "taxId",
            "phone",
            "email",
            "notes",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("name")).isEqualTo(name);
    assertThat(json.get("taxId")).as("CPF só com dígitos").isEqualTo(ANA_CPF);
    assertThat(json.get("phone")).as("telefone só com dígitos").isEqualTo("11912345678");
    assertThat(json.get("email")).isEqualTo("ana@exemplo.com");
    assertThat(json.get("notes")).isEqualTo("vizinho");
    assertThat(json.get("active")).as("cliente nasce ativo").isEqualTo(true);
    assertThat(json.get("version")).as("primeira versão do lock otimista").isEqualTo(0);
    assertThat(json.get("createdAt")).isNotNull();
    assertThat(json.get("updatedAt")).isNotNull();

    StoredCustomer stored = customerOf(UUID.fromString(id));
    assertThat(stored.taxId()).as("o corpo é o que ficou no banco").isEqualTo(ANA_CPF);
    assertThat(stored.phone()).isEqualTo("11912345678");
    assertThat(stored.active()).isTrue();
    assertThat(stored.deleted()).isFalse();
  }

  @Test
  @DisplayName("CPF informado com dígito verificador errado responde 400 VALIDATION_ERROR")
  void rejectsInvalidTaxId() throws SQLException {
    Response response =
        post(adminToken(), body(name("Ana Souza"), "11144477736", null, null, null));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/validation-error");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Dados inválidos");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(countByTaxId("11144477736")).as("o 400 não cria a linha").isZero();
  }

  @Test
  @DisplayName("CPF já usado por cliente vivo responde 409 TAX_ID_ALREADY_EXISTS")
  void rejectsDuplicateTaxId() throws SQLException {
    createdId(post(adminToken(), body(name("Ana Souza"), ANA_MASKED_CPF, null, null, null)));

    // A segunda tentativa manda o CPF sem máscara: é a normalização do caso de uso que colide.
    Response response = post(adminToken(), body(name("Ana Clara"), ANA_CPF, null, null, null));

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("TAX_ID_ALREADY_EXISTS");
    assertThat(countByTaxId(ANA_CPF))
        .as("o 409 não cria a segunda linha com o mesmo CPF")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("GET /api/v1/customers pagina 25 clientes e limita size ao teto de 100")
  void listsWithPaginationAndClampsSize() {
    seedCustomers(25);

    Response firstPage = list("search", SUFFIX, "page", "0", "size", "10");

    assertThat(firstPage.statusCode()).isEqualTo(200);
    assertThat(firstPage.contentType()).contains("application/json");
    assertThat(firstPage.jsonPath().getList("items")).hasSize(10);
    assertThat(firstPage.jsonPath().getInt("page")).isZero();
    assertThat(firstPage.jsonPath().getInt("size")).isEqualTo(10);
    assertThat(firstPage.jsonPath().getInt("totalItems")).isEqualTo(25);
    assertThat(firstPage.jsonPath().getInt("totalPages")).isEqualTo(3);

    Response lastPage = list("search", SUFFIX, "page", "2", "size", "10");
    assertThat(lastPage.jsonPath().getList("items")).hasSize(5);

    Response clamped = list("search", SUFFIX, "size", "500");
    assertThat(clamped.statusCode()).isEqualTo(200);
    assertThat(clamped.jsonPath().getInt("size")).isEqualTo(100);
  }

  @Test
  @DisplayName("GET /api/v1/customers busca por trecho do nome, por CPF e por telefone")
  void searchesByNameFragmentTaxIdAndPhone() {
    seedCustomer(name("Ana Souza"), ANA_CPF, null, null, null);
    seedCustomer(name("Bruno Lima"), null, null, null, null);
    // O cliente do POST prova o outro lado da normalização: a busca casa o telefone com máscara.
    createdId(post(adminToken(), body(name("Carla Dias"), null, "(21) 98765-4321", null, null)));

    Response byName = list("search", name("Souza").toUpperCase(Locale.ROOT));

    assertThat(byName.statusCode()).isEqualTo(200);
    assertThat(names(byName)).containsExactly(name("Ana Souza"));

    Response byMaskedTaxId = list("search", ANA_MASKED_CPF);

    assertThat(names(byMaskedTaxId))
        .as("CPF com ou sem máscara acha o mesmo cliente")
        .containsExactly(name("Ana Souza"));

    Response byPhone = list("search", "21987654321");

    assertThat(names(byPhone))
        .as("telefone guardado só em dígitos")
        .containsExactly(name("Carla Dias"));

    Response withoutResult = list("search", "ninguem-" + SUFFIX);

    assertThat(withoutResult.jsonPath().getList("items")).isEmpty();
    assertThat(withoutResult.jsonPath().getInt("totalItems")).isZero();
    assertThat(withoutResult.jsonPath().getInt("totalPages")).isZero();
  }

  @Test
  @DisplayName(
      "GET /api/v1/customers/{id} devolve 200 com o cliente completo, sem storeId nem deletedAt")
  void returnsCustomerDetail() {
    UUID id = seedCustomer(name("Ana Souza"), ANA_CPF, "11912345678", "ana@exemplo.com", "vizinho");

    Response response = getDetail(id.toString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "taxId",
            "phone",
            "email",
            "notes",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("name")).isEqualTo(name("Ana Souza"));
    assertThat(json.get("taxId")).isEqualTo(ANA_CPF);
    assertThat(json.get("phone")).isEqualTo("11912345678");
    assertThat(json.get("email")).isEqualTo("ana@exemplo.com");
    assertThat(json.get("notes")).isEqualTo("vizinho");
    assertThat(json.get("active")).isEqualTo(true);
    assertThat(json.get("version")).isEqualTo(0);
    assertThat(json.get("createdAt")).isNotNull();
    assertThat(json.get("updatedAt")).isNotNull();
  }

  @Test
  @DisplayName("GET, PUT e disable de id desconhecido respondem 404 CUSTOMER_NOT_FOUND")
  void returnsNotFoundForUnknownCustomer() {
    UUID unknown = UUID.randomUUID();

    for (Response response :
        List.of(
            getDetail(unknown.toString()),
            put(adminToken(), unknown, body(name("Ana Souza"), null, null, null, null)),
            postStatus(adminToken(), unknown, "disable"))) {
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("type"))
          .isEqualTo("https://minimarket.local/problems/customer-not-found");
      assertThat(response.jsonPath().getString("title")).isEqualTo("Cliente não encontrado");
      assertThat(response.jsonPath().getInt("status")).isEqualTo(404);
      assertThat(response.jsonPath().getString("code")).isEqualTo("CUSTOMER_NOT_FOUND");
    }
  }

  @Test
  @DisplayName("PUT /api/v1/customers/{id} substitui os campos e avança a version")
  void updatesCustomer() throws SQLException {
    UUID id = seedCustomer(name("Ana Souza"), ANA_CPF, "11912345678", "ana@exemplo.com", "vizinho");
    String newName = name("Ana Souza Lima");

    Response response =
        put(
            adminToken(),
            id,
            body(newName, BRUNO_MASKED_CPF, "(21) 98765-4321", "ana.lima@exemplo.com", "mudou"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "taxId",
            "phone",
            "email",
            "notes",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("name")).isEqualTo(newName);
    assertThat(json.get("taxId")).isEqualTo(BRUNO_CPF);
    assertThat(json.get("phone")).isEqualTo("21987654321");
    assertThat(json.get("email")).isEqualTo("ana.lima@exemplo.com");
    assertThat(json.get("notes")).isEqualTo("mudou");
    assertThat(json.get("active")).isEqualTo(true);
    assertThat(json.get("version")).as("lock otimista avançou").isEqualTo(1);

    assertThat(customerOf(id).name()).as("o banco guardou a edição").isEqualTo(newName);
  }

  @Test
  @DisplayName("PUT sem mudança efetiva é no-op: 200 com o cliente, sem gravar nem auditar")
  void keepsCustomerWhenNothingChanged() throws SQLException {
    UUID id = seedCustomer(name("Ana Souza"), ANA_CPF, "11912345678", "ana@exemplo.com", "vizinho");

    // A máscara é a mesma pessoa: o que se compara é o valor normalizado, não o texto digitado.
    Response response =
        put(
            adminToken(),
            id,
            body(
                name("Ana Souza"),
                ANA_MASKED_CPF,
                "(11) 91234-5678",
                "ana@exemplo.com",
                "vizinho"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("taxId")).isEqualTo(ANA_CPF);
    assertThat(response.jsonPath().getInt("version")).as("o no-op não toca na linha").isZero();
    assertThat(customerOf(id).name()).isEqualTo(name("Ana Souza"));
    assertThat(eventCount(id)).as("o no-op não inventa evento").isZero();
  }

  @Test
  @DisplayName("PUT com CPF de outro cliente vivo responde 409; o CPF do próprio não conflita")
  void rejectsTaxIdTakenOnUpdate() throws SQLException {
    seedCustomer(name("Ana Souza"), ANA_CPF, null, null, null);
    UUID bruno = seedCustomer(name("Bruno Lima"), BRUNO_CPF, null, null, null);

    Response taken =
        put(adminToken(), bruno, body(name("Bruno Lima"), ANA_MASKED_CPF, null, null, null));

    assertThat(taken.statusCode()).isEqualTo(409);
    assertThat(taken.contentType()).contains("application/problem+json");
    assertThat(taken.jsonPath().getString("code")).isEqualTo("TAX_ID_ALREADY_EXISTS");
    assertThat(customerOf(bruno).taxId()).as("o 409 não toca na linha").isEqualTo(BRUNO_CPF);
    assertThat(eventCount(bruno)).as("o 409 não audita").isZero();

    Response own =
        put(adminToken(), bruno, body(name("Bruno Lima"), BRUNO_MASKED_CPF, null, null, null));

    assertThat(own.statusCode()).as("o CPF do próprio cliente não conflita").isEqualTo(200);
    assertThat(own.jsonPath().getString("taxId")).isEqualTo(BRUNO_CPF);
  }

  @Test
  @DisplayName("POST /api/v1/customers/{id}/disable tira o cliente da busca e libera o CPF")
  void disablesCustomerAndFreesTaxId() throws SQLException {
    UUID id = seedCustomer(name("Ana Souza"), ANA_CPF, "11912345678", null, null);

    Response response = postStatus(adminToken(), id, "disable");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getBoolean("active")).isFalse();
    assertThat(response.jsonPath().getInt("version")).as("a desativação grava").isEqualTo(1);

    StoredCustomer stored = customerOf(id);
    assertThat(stored.active()).as("o banco guardou o active falso").isFalse();
    assertThat(stored.deleted()).as("o soft delete acompanha a desativação").isTrue();

    assertThat(names(list("search", SUFFIX)))
        .as("cliente desativado não aparece na busca")
        .doesNotContain(name("Ana Souza"));
    assertThat(getDetail(id.toString()).statusCode()).as("o detalhe também esconde").isEqualTo(404);

    Response again = postStatus(adminToken(), id, "disable");

    assertThat(again.statusCode()).as("desativado de novo conta como inexistente").isEqualTo(404);
    assertThat(again.jsonPath().getString("code")).isEqualTo("CUSTOMER_NOT_FOUND");

    Response reused = post(adminToken(), body(name("Ana Clara"), ANA_MASKED_CPF, null, null, null));

    assertThat(reused.statusCode())
        .as("o índice único parcial ignora o cliente desativado")
        .isEqualTo(201);
    assertThat(reused.jsonPath().getString("taxId")).isEqualTo(ANA_CPF);
    assertThat(countByTaxId(ANA_CPF)).as("só o cliente novo está vivo").isEqualTo(1);
  }

  @Test
  @DisplayName("OPERADOR escreve clientes: 201 no POST, 200 no PUT e 200 no disable (matriz §4.5)")
  void operatorWritesCustomers() throws SQLException {
    String username = "clientes.operador." + SUFFIX;
    createUser(username, OPERATOR_ROLE);
    String token = login(username);
    String name = name("Ana Souza");

    Response created = post(token, body(name, ANA_MASKED_CPF, "(11) 91234-5678", null, null));

    assertThat(created.statusCode())
        .as("a matriz de clientes dá customer.write ao OPERADOR: %s", created.asString())
        .isEqualTo(201);
    UUID id = UUID.fromString(created.jsonPath().getString("id"));

    Response updated = put(token, id, body(name, ANA_MASKED_CPF, null, "ana@exemplo.com", null));

    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(updated.jsonPath().getString("email")).isEqualTo("ana@exemplo.com");

    Response disabled = postStatus(token, id, "disable");

    assertThat(disabled.statusCode()).isEqualTo(200);
    assertThat(disabled.jsonPath().getBoolean("active")).isFalse();
  }

  @Test
  @DisplayName("sem token, as cinco rotas de clientes respondem 401 problem+json")
  void rejectsAnonymousAccess() {
    List<Response> responses =
        List.of(
            given().when().get(PATH).then().extract().response(),
            given()
                .contentType("application/json")
                .body("{}")
                .when()
                .post(PATH)
                .then()
                .extract()
                .response(),
            given().when().get(PATH + "/" + UUID.randomUUID()).then().extract().response(),
            given()
                .contentType("application/json")
                .body("{}")
                .when()
                .put(PATH + "/" + UUID.randomUUID())
                .then()
                .extract()
                .response(),
            given()
                .when()
                .post(PATH + "/" + UUID.randomUUID() + "/disable")
                .then()
                .extract()
                .response());

    for (Response response : responses) {
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    }
  }

  @Test
  @DisplayName("GET /api/v1/customers com page negativo ou size menor que 1 responde 400")
  void rejectsInvalidPagination() {
    assertBadRequest(list("page", "-1"));
    assertBadRequest(list("size", "0"));
  }

  @Test
  @DisplayName("GET /api/v1/customers com page não numérico responde 400 citando o campo")
  void rejectsNonNumericPage() {
    Response response = list("page", "primeira");

    assertBadRequest(response);
    assertThat(response.jsonPath().getList("errors.field", String.class)).containsExactly("page");
  }

  @Test
  @DisplayName("POST com nome em branco responde 400 apontando o campo")
  void rejectsBlankName() {
    Response response = post(adminToken(), body("   ", null, null, null, null));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getList("errors.field", String.class)).contains("name");
  }

  /** Testes que criam OPERADOR usam o caso de uso (passo 107); o login é o de verdade. */
  private void createUser(String username, String roleCode) {
    createUserUseCase.execute(
        new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)));
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

  /** Nome de cliente com o sufixo da classe, para a limpeza no fim do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
  }

  /** GET na listagem como ADMIN, com os pares {@code chave, valor} na query string. */
  private Response list(String... query) {
    StringBuilder url = new StringBuilder(PATH);
    for (int index = 0; index < query.length; index += 2) {
      url.append(index == 0 ? '?' : '&').append(query[index]).append('=').append(query[index + 1]);
    }
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .when()
        .get(url.toString())
        .then()
        .extract()
        .response();
  }

  /** GET no detalhe como ADMIN. */
  private Response getDetail(String id) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .when()
        .get(PATH + "/" + id)
        .then()
        .extract()
        .response();
  }

  /** POST no cadastro com o token informado. */
  private static Response post(String token, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .post(PATH)
        .then()
        .extract()
        .response();
  }

  /** PUT na edição com o token informado — sem {@code If-Match}, como o contrato do 502b. */
  private static Response put(String token, UUID id, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .put(PATH + "/" + id)
        .then()
        .extract()
        .response();
  }

  /** POST do ciclo de vida do cliente: {@code disable}, sem corpo. */
  private static Response postStatus(String token, UUID id, String status) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .post(PATH + "/" + id + "/" + status)
        .then()
        .extract()
        .response();
  }

  /**
   * Corpo do POST/PUT com os cinco campos; campo nulo vira {@code null} no JSON — o mesmo que o
   * cliente o omite.
   */
  private static String body(String name, String taxId, String phone, String email, String notes) {
    return """
        {"name": "%s", "taxId": %s, "phone": %s, "email": %s, "notes": %s}
        """
        .formatted(name, quoted(taxId), quoted(phone), quoted(email), quoted(notes));
  }

  private static String quoted(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  /** O 400 padrão de parâmetro inválido: problem+json com {@code VALIDATION_ERROR}. */
  private static void assertBadRequest(Response response) {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
  }

  /** Nomes dos itens da página, na ordem devolvida. */
  private static List<String> names(Response response) {
    return response.jsonPath().getList("items.name", String.class);
  }

  /** Id do corpo de um 201; falha com o status quando a criação não aconteceu. */
  private static String createdId(Response response) {
    assertThat(response.statusCode())
        .as("criação da fixture: %s", response.asString())
        .isEqualTo(201);
    return response.jsonPath().getString("id");
  }

  /** Cliente da fixture pela porta, sem caso de uso nem auditoria — o alvo é a rota. */
  private UUID seedCustomer(String name, String taxId, String phone, String email, String notes) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                customerStore.insert(new NewCustomer(storeId(), name, taxId, phone, email, notes)));
  }

  /** {@code count} clientes numa transação só: a paginação precisa de massa. */
  private void seedCustomers(int count) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (int index = 1; index <= count; index++) {
                customerStore.insert(
                    new NewCustomer(
                        storeId(), name("Cliente %02d".formatted(index)), null, null, null, null));
              }
            });
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

  /** Linha de {@code customers} como o banco a guardou; {@code deleted} é o soft delete. */
  private StoredCustomer customerOf(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select name, tax_id, phone, active, deleted_at is not null as deleted"
                    + " from customers where id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("cliente %s gravado", id).isTrue();
        return new StoredCustomer(
            resultSet.getString("name"),
            resultSet.getString("tax_id"),
            resultSet.getString("phone"),
            resultSet.getBoolean("active"),
            resultSet.getBoolean("deleted"));
      }
    }
  }

  /** Quantos clientes existem com o CPF informado, vivos ou não: o 400/409 não pode criar linha. */
  private int countByTaxId(String taxId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from customers where tax_id = ? and deleted_at is null")) {
      statement.setString(1, taxId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Quantos eventos de auditoria o cliente tem; o no-op e os 4xx exigem zero. */
  private int eventCount(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where entity_id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos de
   * auditoria (do OPERADOR e os que apontam para os clientes do sufixo) antes das sessões, do
   * usuário e dos clientes que eles referenciam.
   */
  @AfterEach
  void removeRowsCreatedByThisTest() throws SQLException {
    String suffixLike = "%" + SUFFIX;
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from audit_events where actor_user_id in"
              + " (select id from users where username like ?) or entity_id in"
              + " (select id from users where username like ?)",
          suffixLike,
          suffixLike);
      delete(
          connection,
          "delete from audit_events where entity_id in (select id from auth_sessions where user_id"
              + " in (select id from users where username like ?))",
          suffixLike);
      delete(
          connection,
          "delete from audit_events where entity_id in (select id from customers where name like ?)",
          suffixLike);
      delete(
          connection,
          "delete from auth_sessions where user_id in"
              + " (select id from users where username like ?)",
          suffixLike);
      delete(
          connection,
          "delete from user_roles where user_id in (select id from users where username like ?)",
          suffixLike);
      delete(connection, "delete from users where username like ?", suffixLike);
      delete(connection, "delete from customers where name like ?", suffixLike);
    }
  }

  private static void delete(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  /** Linha de {@code customers} como o banco a guardou; {@code deleted} é o soft delete. */
  private record StoredCustomer(
      String name, String taxId, String phone, boolean active, boolean deleted) {}
}
