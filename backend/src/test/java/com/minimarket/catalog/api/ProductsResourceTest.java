package com.minimarket.catalog.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.CategoryStore;
import com.minimarket.catalog.application.NewCategory;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.support.TestAdmin;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Produtos na API: criação (passo 406), listagem com busca e filtros (passo 407), detalhe (passo
 * 408), bipe por código de barras (passo 409), edição com {@code If-Match} (passo 410), alteração
 * de preço auditada (passo 411) e desativação/reativação (passo 412) contra PostgreSQL real (Dev
 * Services): as rotas de verdade, com o ADMIN da fixture e um OPERADOR criado pelo caso de uso e
 * autenticado por login real, como no {@code PermissionMatrixTest}.
 *
 * <p>A listagem é semeada pela porta {@code ProductStore} (sem caso de uso nem auditoria, em
 * transação própria). O request HTTP commita: o {@link #removeRowsCreatedByThisTest()} apaga ao fim
 * de cada teste os produtos e categorias com o sufixo desta classe (e os eventos de auditoria que
 * os referenciam), o usuário OPERADOR, as sessões e os eventos dele — os do ADMIN já saem pelo
 * {@code TestAdmin.remove}. Nomes editados pelo PUT mantêm o sufixo para a limpeza alcançá-los.
 */
@QuarkusTest
class ProductsResourceTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes e barcodes nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Limite do smoke de tempo do bipe (passo 409): o caminho quente não pode passar disso. */
  private static final long MAX_BARCODE_LOOKUP_NANOS = 50_000_000L;

  private static final String PASSWORD = "senha-secreta";
  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/products";
  private static final String OPERATOR_ROLE = "OPERADOR";

  /** O barcode como o teste o digita: com espaços, que só o caso de uso sabe normalizar. */
  private static final String SPACED_BARCODE = " 789 1000 " + SUFFIX + " 17 ";

  /** O barcode como o caso de uso o guarda: sem espaços — o que o 201 precisa devolver. */
  private static final String STORED_BARCODE = "7891000" + SUFFIX + "17";

  /** Código interno do produto pesável: 5 dígitos, o tamanho que a loja configura na etiqueta. */
  private static final String INTERNAL_CODE =
      String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));

  /** Código interno que nenhum produto do teste tem: o 404 do bipe por PLU desconhecido. */
  private static final String UNKNOWN_INTERNAL_CODE =
      String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));

  /** Código interno digitado curto (passo 1104d): 4 dígitos, como o operador o digita. */
  private static final String TYPED_INTERNAL_CODE =
      String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));

  /** O mesmo código na forma canônica que o cadastro grava: com o zero à esquerda da etiqueta. */
  private static final String PADDED_INTERNAL_CODE = "0" + TYPED_INTERNAL_CODE;

  /** Caso de uso da criação de usuário: o OPERADOR é fixture, não o alvo do teste. */
  @Inject CreateUserUseCase createUserUseCase;

  /** Portas para semear a listagem sem passar pelos casos de uso (nem pela auditoria). */
  @Inject ProductStore productStore;

  @Inject CategoryStore categoryStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Id da loja do seed da V1, resolvido uma vez por teste. */
  private UUID storeId;

  @Test
  @DisplayName(
      "ADMIN cria produto: 201 com Location e corpo com os valores como o banco os guardou")
  void createsProduct() throws SQLException {
    String name = name("Arroz 5kg");

    Response response =
        post(
            adminToken(),
            """
            {"name": "%s", "barcode": "%s", "description": "grão longo", "unit": "UN",
             "price": 24.9, "minQuantity": 1.5}
            """
                .formatted(name, SPACED_BARCODE));

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
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("name")).isEqualTo(name);
    assertThat(json.get("barcode"))
        .as("trim e espaços internos removidos pelo caso de uso, não pela API")
        .isEqualTo(STORED_BARCODE);
    assertThat(json.get("description")).isEqualTo("grão longo");
    assertThat(json.get("categoryId")).as("categoria é opcional").isNull();
    assertThat(json.get("unit")).isEqualTo("UN");
    assertThat(number(json.get("price")))
        .as("preço em escala 2 (HALF_UP)")
        .isEqualByComparingTo("24.90");
    assertThat(number(json.get("minQuantity"))).isEqualByComparingTo("1.500");
    assertThat(json.get("active")).as("produto nasce ativo").isEqualTo(true);
    assertThat(json.get("version")).as("primeira versão do lock otimista").isEqualTo(0);
    assertThat(json.get("createdAt")).isNotNull();
    assertThat(json.get("updatedAt")).isNotNull();

    StoredProduct stored = productOf(id);
    assertThat(stored.barcode()).as("o corpo é o que ficou no banco").isEqualTo(STORED_BARCODE);
    assertThat(stored.price()).isEqualTo("24.90");
    assertThat(stored.active()).isTrue();
  }

  @Test
  @DisplayName("barcode já usado por produto vivo responde 409 BARCODE_ALREADY_EXISTS")
  void rejectsDuplicateBarcode() throws SQLException {
    createdId(post(adminToken(), validBody(name("Arroz 5kg"), SPACED_BARCODE)));

    // A segunda tentativa manda o barcode sem espaços: é a normalização do caso de uso que colide.
    Response response = post(adminToken(), validBody(name("Arroz 1kg"), STORED_BARCODE));

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("BARCODE_ALREADY_EXISTS");
    assertThat(countActiveByBarcode(STORED_BARCODE))
        .as("o 409 não cria a segunda linha")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "POST com internalCode curto grava a forma canônica: etiqueta e código digitado resolvem o mesmo produto")
  void storesCanonicalInternalCode() throws SQLException {
    Response created =
        post(
            adminToken(),
            """
            {"name": "%s", "internalCode": "%s", "unit": "KG", "price": 7.49}
            """
                .formatted(name("Banana prata"), TYPED_INTERNAL_CODE));

    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.jsonPath().getString("internalCode"))
        .as("zeros à esquerda até os 5 dígitos da etiqueta da loja")
        .isEqualTo(PADDED_INTERNAL_CODE);
    String id = created.jsonPath().getString("id");
    assertThat(productOf(id).internalCode())
        .as("o banco guardou a forma canônica")
        .isEqualTo(PADDED_INTERNAL_CODE);

    Response typed = getByBarcode(TYPED_INTERNAL_CODE);
    Response label = getByBarcode(weightLabel(PADDED_INTERNAL_CODE));

    assertThat(typed.statusCode()).as("PLU digitado curto resolve").isEqualTo(200);
    assertThat(label.statusCode()).as("etiqueta de peso resolve").isEqualTo(200);
    for (Response response : List.of(typed, label)) {
      assertThat(response.jsonPath().getString("id"))
          .as("o mesmo produto resolve pela etiqueta e pelo código digitado")
          .isEqualTo(id);
    }
    assertThat(number(label.jsonPath().getMap("$").get("quantity")))
        .as("0001234 com 3 casas = 1,234 kg")
        .isEqualByComparingTo("1.234");
  }

  @Test
  @DisplayName("código interno já usado por produto vivo responde 409 INTERNAL_CODE_ALREADY_EXISTS")
  void rejectsDuplicateInternalCode() throws SQLException {
    seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);

    Response response =
        post(
            adminToken(),
            """
            {"name": "%s", "internalCode": "%s", "unit": "KG", "price": 7.49}
            """
                .formatted(name("Banana nanica"), INTERNAL_CODE));

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/internal-code-already-exists");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Código interno já está em uso");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INTERNAL_CODE_ALREADY_EXISTS");
    assertThat(countActiveByInternalCode(INTERNAL_CODE))
        .as("o 409 não cria a segunda linha")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "internalCode não numérico ou maior que o configurado responde 400 VALIDATION_ERROR no campo")
  void rejectsMalformedInternalCode() throws SQLException {
    for (String malformed : List.of("12A", "12.3", "123456")) {
      Response response =
          post(
              adminToken(),
              """
              {"name": "%s", "internalCode": "%s", "unit": "KG", "price": 7.49}
              """
                  .formatted(name("Banana prata"), malformed));

      assertThat(response.statusCode()).as("código interno %s", malformed).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
      assertThat(response.jsonPath().getList("errors.field", String.class))
          .as("o 400 cita o campo")
          .containsExactly("internalCode");
      assertThat(countActiveByInternalCode(malformed))
          .as("a linha inválida não foi criada")
          .isZero();
    }
  }

  @Test
  @DisplayName("forma inválida responde 400 VALIDATION_ERROR em problem+json apontando o campo")
  void rejectsInvalidShape() {
    assertInvalid(
        """
        {"name": "   ", "unit": "UN", "price": 1.00}
        """,
        "name");
    assertInvalid(
        """
        {"name": "Arroz", "unit": "UN", "price": -0.01}
        """,
        "price");
    assertInvalid(
        """
        {"name": "Arroz", "price": 1.00}
        """,
        "unit");
  }

  @Test
  @DisplayName(
      "OPERADOR não cria nem edita produto: 403 ACCESS_DENIED citando product.write, sem gravar nada")
  void deniesOperator() throws SQLException {
    String username = "produtos.operador." + SUFFIX;
    createUser(username, OPERATOR_ROLE);
    String token = login(username);
    String barcode = "555" + SUFFIX;

    Response response = post(token, validBody(name("Café"), barcode));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("product.write");
    assertThat(countActiveByBarcode(barcode)).as("o 403 barra antes do caso de uso").isZero();

    // A edição (passo 410) também exige product.write: barrada antes do caso de uso, sem tocar no
    // produto — nem na versão, que seguiria valendo para o próximo If-Match do dono.
    UUID id = seedProduct("Café", "18.90", null);
    Response onUpdate =
        put(token, id, "\"0\"", updateBody(name("Café moído"), null, "KG", "moído fino", null));

    assertThat(onUpdate.statusCode()).isEqualTo(403);
    assertThat(onUpdate.contentType()).contains("application/problem+json");
    assertThat(onUpdate.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(onUpdate.jsonPath().getString("detail")).contains("product.write");
    assertThat(storedName(id)).as("o 403 não editou o produto").isEqualTo(name("Café"));
  }

  @Test
  @DisplayName(
      "GET /api/v1/products pagina 25 produtos: página 0/size 10 devolve 10 itens e totalItems=25")
  void listsWithPagination() {
    seedProducts(25);

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
  }

  @Test
  @DisplayName("GET /api/v1/products com size=500 devolve size limitado a 100")
  void limitsSizeToMaximum() {
    seedProduct("Arroz 5kg", "24.90", null);

    Response response = list("search", SUFFIX, "size", "500");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getInt("size")).isEqualTo(100);
    assertThat(response.jsonPath().getList("items")).hasSize(1);
  }

  @Test
  @DisplayName("GET /api/v1/products busca por trecho do nome, sem diferenciar maiúsculas")
  void searchesByNameFragment() {
    seedProduct("Arroz Integral", "12.90", null);
    seedProduct("Feijao Carioca", "8.49", null);

    // O termo é ASCII e em caixa alta: o ILIKE do banco não diferencia maiúsculas e acento não
    // decide o resultado.
    Response response = list("search", name("integral").toUpperCase(Locale.ROOT));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(names(response)).containsExactly(name("Arroz Integral"));
    assertThat(response.jsonPath().getInt("totalItems")).isEqualTo(1);
    assertThat(response.jsonPath().getMap("items[0]"))
        .as("contrato do item: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
  }

  @Test
  @DisplayName("GET /api/v1/products sem resultado devolve items vazio e totalPages 0")
  void returnsEmptyPage() {
    Response response = list("search", "ninguem-" + SUFFIX);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("items")).isEmpty();
    assertThat(response.jsonPath().getInt("totalItems")).isZero();
    assertThat(response.jsonPath().getInt("totalPages")).isZero();
  }

  @Test
  @DisplayName("GET /api/v1/products filtra por categoria")
  void filtersByCategory() {
    UUID bebidas = seedCategory("Bebidas");
    UUID limpeza = seedCategory("Limpeza");
    seedProduct("Refrigerante", "7.50", bebidas);
    seedProduct("Suco de uva", "8.90", bebidas);
    seedProduct("Detergente", "3.79", limpeza);

    Response response = list("search", SUFFIX, "categoryId", bebidas.toString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(names(response))
        .as("só a categoria pedida, no default name,asc")
        .containsExactly(name("Refrigerante"), name("Suco de uva"));
    assertThat(response.jsonPath().getInt("totalItems")).isEqualTo(2);
  }

  @Test
  @DisplayName("GET /api/v1/products ordena por price,desc e por name,asc")
  void sortsByPriceDescendingAndNameAscending() {
    seedProduct("Arroz 5kg", "24.90", null);
    seedProduct("Banana prata", "6.99", null);
    seedProduct("Cafe 500g", "18.90", null);

    Response byPrice = list("search", SUFFIX, "sort", "price,desc");

    assertThat(names(byPrice))
        .containsExactly(name("Arroz 5kg"), name("Cafe 500g"), name("Banana prata"));

    Response byName = list("search", SUFFIX, "sort", "name,asc");

    assertThat(names(byName))
        .containsExactly(name("Arroz 5kg"), name("Banana prata"), name("Cafe 500g"));
  }

  @Test
  @DisplayName("GET /api/v1/products com sort fora da whitelist ou direção inválida responde 400")
  void rejectsInvalidSort() {
    assertBadRequest(list("sort", "barcode,asc"));
    assertBadRequest(list("sort", "name,up"));
  }

  @Test
  @DisplayName("GET /api/v1/products com page negativo ou size menor que 1 responde 400")
  void rejectsInvalidPagination() {
    assertBadRequest(list("page", "-1"));
    assertBadRequest(list("size", "0"));
  }

  @Test
  @DisplayName("GET /api/v1/products com filtro tipado inválido responde 400 citando o campo")
  void rejectsInvalidTypedFilters() {
    for (String[] invalid :
        List.of(
            new String[] {"active", "abc"},
            new String[] {"categoryId", "nao-e-uuid"},
            new String[] {"page", "primeira"})) {
      Response response = list(invalid[0], invalid[1]);

      assertBadRequest(response);
      assertThat(response.jsonPath().getList("errors.field", String.class))
          .as("o 400 cita o campo %s", invalid[0])
          .containsExactly(invalid[0]);
    }
  }

  @Test
  @DisplayName(
      "GET /api/v1/products filtra active: produto com active=false só aparece com active=false")
  void filtersByActive() throws SQLException {
    seedProduct("Arroz 5kg", "24.90", null);
    UUID inactive = seedProduct("Detergente 500ml", "3.79", null);
    setActiveDirectly(inactive, false);

    Response inactiveOnly = list("search", SUFFIX, "active", "false");

    assertThat(names(inactiveOnly)).containsExactly(name("Detergente 500ml"));
    assertThat(inactiveOnly.jsonPath().getInt("totalItems")).isEqualTo(1);

    Response activeOnly = list("search", SUFFIX, "active", "true");

    assertThat(names(activeOnly)).containsExactly(name("Arroz 5kg"));

    assertThat(names(list("search", SUFFIX)))
        .as("sem filtro de status os dois aparecem")
        .containsExactly(name("Arroz 5kg"), name("Detergente 500ml"));
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/{id} devolve 200 com o produto completo, sem storeId nem deletedAt")
  void returnsProductDetail() {
    UUID categoryId = seedCategory("Bebidas");
    UUID id = seedProduct("Refrigerante 2L", "7.50", categoryId);

    Response response = getDetail(id.toString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("name")).isEqualTo(name("Refrigerante 2L"));
    assertThat(json.get("barcode")).as("sem barcode no cadastro").isNull();
    assertThat(json.get("description")).isEqualTo("descrição de Refrigerante 2L");
    assertThat(json.get("categoryId")).isEqualTo(categoryId.toString());
    assertThat(json.get("unit")).isEqualTo("UN");
    assertThat(number(json.get("price"))).isEqualByComparingTo("7.50");
    assertThat(json.get("minQuantity")).as("sem mínimo no cadastro").isNull();
    assertThat(json.get("active")).isEqualTo(true);
    assertThat(json.get("version")).as("primeira versão do lock otimista").isEqualTo(0);
    assertThat(json.get("createdAt")).isNotNull();
    assertThat(json.get("updatedAt")).isNotNull();
  }

  @Test
  @DisplayName("GET /api/v1/products/{id} de id inexistente responde 404 PRODUCT_NOT_FOUND")
  void returnsNotFoundForUnknownId() {
    Response response = getDetailExpectingNotFound(UUID.randomUUID().toString());

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/product-not-found");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Produto não encontrado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName("GET /api/v1/products/{id} de produto soft-deletado responde 404 PRODUCT_NOT_FOUND")
  void hidesSoftDeletedProductFromDetail() {
    UUID id = seedProduct("Detergente 500ml", "3.79", null);
    softDeleteViaPort(id);

    Response response = getDetailExpectingNotFound(id.toString());

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName("GET /api/v1/products/barcode/{barcode} devolve 200 com os campos do bipe")
  void resolvesActiveProductByBarcode() {
    UUID id = seedProduct("Refrigerante 2L", "7.50", null, STORED_BARCODE);

    Response response = getByBarcode(STORED_BARCODE);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato do bipe: resposta enxuta, sem cadastro completo nem estoque")
        .containsOnlyKeys("id", "barcode", "name", "price", "unit", "quantity");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("barcode")).isEqualTo(STORED_BARCODE);
    assertThat(json.get("name")).isEqualTo(name("Refrigerante 2L"));
    assertThat(number(json.get("price"))).isEqualByComparingTo("7.50");
    assertThat(json.get("unit")).isEqualTo("UN");
    assertThat(json.get("quantity"))
        .as("GTIN não embute quantidade: o campo existe, mas vem nulo (1104b3)")
        .isNull();
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/barcode/{barcode} de código interno digitado resolve sem quantidade")
  void resolvesInternalCodeTypedWithoutQuantity() {
    UUID id = seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);

    Response response = getByBarcode(INTERNAL_CODE);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("barcode")).as("produto pesável pode não ter GTIN").isNull();
    assertThat(json.get("unit")).isEqualTo("KG");
    assertThat(json.get("quantity"))
        .as("PLU digitado não embute quantidade: quem manda é o cliente")
        .isNull();
  }

  @Test
  @DisplayName("etiqueta de peso no bipe: 200 com a quantidade em kg que o código embute")
  void resolvesWeightLabelWithSuggestedQuantity() {
    UUID id = seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);

    Response response = getByBarcode(weightLabel());

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("unit")).isEqualTo("KG");
    assertThat(number(json.get("quantity")))
        .as("0001234 com 3 casas = 1,234 kg")
        .isEqualByComparingTo("1.234");
  }

  @Test
  @DisplayName("etiqueta de preço no bipe: 200 com a quantidade derivada do preço vigente")
  void resolvesPriceLabelDerivingQuantityFromPrice() throws SQLException {
    setStoreScale("PRICE", 2);
    UUID id = seedProduct("Uva itália", "9.99", "KG", null, null, INTERNAL_CODE);

    Response response = getByBarcode(priceLabel());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("id")).isEqualTo(id.toString());
    assertThat(number(response.jsonPath().getMap("$").get("quantity")))
        .as("R$ 19,99 embutidos a R$ 9,99/kg = 2,001 kg")
        .isEqualByComparingTo("2.001");
  }

  @Test
  @DisplayName("etiqueta malformada no bipe: 422 INVALID_INTERNAL_BARCODE, não 404")
  void rejectsMalformedLabel() {
    Response response = getByBarcode("2000000000000");

    assertThat(response.statusCode())
        .as("valor embutido zero é defeito da balança, não código desconhecido")
        .isEqualTo(422);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_INTERNAL_BARCODE");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Código interno inválido");
  }

  @Test
  @DisplayName("código interno digitado e etiqueta de produto inexistente: 404 PRODUCT_NOT_FOUND")
  void returnsNotFoundForUnknownInternalCodeAndLabel() {
    Response typed = getByBarcode(UNKNOWN_INTERNAL_CODE);
    Response label = getByBarcode("2" + UNKNOWN_INTERNAL_CODE + "0001234");

    for (Response response : List.of(typed, label)) {
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
    }
  }

  @Test
  @DisplayName("barcode exato vence o parse: código no formato da etiqueta resolve pelo barcode")
  void exactBarcodeTakesPrecedenceOverLabelParsing() {
    String label = weightLabel();
    UUID byExactBarcode = seedProduct("Arroz 5kg", "24.90", "UN", null, label, null);
    seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);

    Response response = getByBarcode(label);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json.get("id"))
        .as("o barcode exato vence a interpretação de etiqueta")
        .isEqualTo(byExactBarcode.toString());
    assertThat(json.get("quantity"))
        .as("resolvido pelo barcode, o código não sugere quantidade")
        .isNull();
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/barcode/{barcode} normaliza espaços: o bipe resolve o mesmo produto")
  void normalizesBarcodeWithSpaces() {
    UUID id = seedProduct("Café 500g", "18.90", null, STORED_BARCODE);

    Response response = getByBarcode(SPACED_BARCODE);

    assertThat(response.statusCode())
        .as("trim e espaços internos removidos pelo caso de uso, não pela API")
        .isEqualTo(200);
    assertThat(response.jsonPath().getString("id")).isEqualTo(id.toString());
    assertThat(response.jsonPath().getString("barcode"))
        .as("a resposta devolve o código como o banco guardou")
        .isEqualTo(STORED_BARCODE);

    Response blank = getByBarcode("   ");

    assertThat(blank.statusCode())
        .as("código só de espaços normaliza para vazio e não resolve produto")
        .isEqualTo(404);
    assertThat(blank.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/barcode/{barcode} de produto soft-deletado responde 404 PRODUCT_NOT_FOUND")
  void hidesSoftDeletedProductFromBarcode() {
    UUID id = seedProduct("Detergente 500ml", "3.79", null, STORED_BARCODE);
    softDeleteViaPort(id);

    Response response = getByBarcode(STORED_BARCODE);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/barcode/{barcode} de produto inativo responde 404 PRODUCT_NOT_FOUND")
  void hidesInactiveProductFromBarcode() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);
    setActiveDirectly(id, false);

    Response response = getByBarcode(STORED_BARCODE);

    assertThat(response.statusCode()).as("bipe só resolve produto ativo").isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/barcode/{barcode} de código inexistente responde 404 PRODUCT_NOT_FOUND")
  void returnsNotFoundForUnknownBarcode() {
    // O código não é UUID: se caísse na rota /{id}, a conversão do parâmetro responderia outro
    // código — o PRODUCT_NOT_FOUND abaixo prova que o segmento literal venceu no roteamento.
    Response response = getByBarcode("000" + SUFFIX + "000");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/product-not-found");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Produto não encontrado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "GET /api/v1/products/barcode/{barcode} responde abaixo de 50 ms (smoke do caminho quente)")
  void resolvesBarcodeWithinFiftyMilliseconds() {
    seedProduct("Cerveja lata", "4.29", null, STORED_BARCODE);

    // O primeiro GET aquece a rota (e faz o login lazy do ADMIN da fixture): só as três medições
    // seguintes contam, e a melhor delas é a do teste — sem sleep e sem depender de outra classe.
    assertThat(getByBarcode(STORED_BARCODE).statusCode()).as("aquecimento").isEqualTo(200);

    long[] measured = new long[3];
    for (int index = 0; index < measured.length; index++) {
      long start = System.nanoTime();
      Response response = getByBarcode(STORED_BARCODE);
      measured[index] = System.nanoTime() - start;
      assertThat(response.statusCode()).as("medição %d", index + 1).isEqualTo(200);
    }

    long best = Arrays.stream(measured).min().orElseThrow();
    assertThat(best)
        .as(
            "melhor das 3 medições: %.2f ms, %.2f ms, %.2f ms (limite de 50 ms)",
            millis(measured[0]), millis(measured[1]), millis(measured[2]))
        .isLessThan(MAX_BARCODE_LOOKUP_NANOS);
  }

  @Test
  @DisplayName(
      "PUT /api/v1/products/{id} com If-Match correto devolve 200 com os valores novos e version maior")
  void updatesProduct() throws SQLException {
    UUID categoryId = seedCategory("Bebidas");
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);
    String newName = name("Arroz Tipo 1 5kg");

    Response response =
        put(
            adminToken(),
            id,
            "\"" + versionOf(id) + "\"",
            updateBody(newName, categoryId, "KG", "grão longo tipo 1", "2.500"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("name")).isEqualTo(newName);
    assertThat(json.get("categoryId")).isEqualTo(categoryId.toString());
    assertThat(json.get("unit")).isEqualTo("KG");
    assertThat(json.get("description")).isEqualTo("grão longo tipo 1");
    assertThat(number(json.get("minQuantity"))).isEqualByComparingTo("2.500");
    assertThat(json.get("version")).as("lock otimista avançou").isEqualTo(1);
    assertThat(json.get("barcode")).as("barcode não muda por aqui").isEqualTo(STORED_BARCODE);
    assertThat(number(json.get("price")))
        .as("preço não muda por aqui")
        .isEqualByComparingTo("24.90");
    assertThat(json.get("active")).isEqualTo(true);

    assertThat(storedName(id)).as("o banco guardou a edição").isEqualTo(newName);
  }

  @Test
  @DisplayName("If-Match aceita 13, \"13\" e W/\"13\": as três formas editam o produto")
  void acceptsIfMatchFormats() {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);
    List<String> forms = List.of("0", "\"1\"", "W/\"2\"");

    for (int index = 0; index < forms.size(); index++) {
      Response response =
          put(
              adminToken(),
              id,
              forms.get(index),
              updateBody(name("Arroz Tipo " + (index + 1)), null, "UN", null, null));

      assertThat(response.statusCode()).as("If-Match %s", forms.get(index)).isEqualTo(200);
      assertThat(response.jsonPath().getInt("version")).isEqualTo(index + 1);
    }
  }

  @Test
  @DisplayName("PUT com versão antiga responde 409 CONCURRENT_MODIFICATION e não altera o produto")
  void rejectsStaleVersion() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);
    String edited = name("Arroz Tipo 1 5kg");
    assertThat(
            put(adminToken(), id, "\"0\"", updateBody(edited, null, "KG", "grão longo", "1.000"))
                .statusCode())
        .as("primeira edição")
        .isEqualTo(200);

    Response stale =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Arroz Integral"), null, "UN", "outra descrição", "2.000"));

    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(stale.contentType()).contains("application/problem+json");
    assertThat(stale.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/concurrent-modification");
    assertThat(stale.jsonPath().getString("title")).isEqualTo("Modificação concorrente");
    assertThat(stale.jsonPath().getInt("status")).isEqualTo(409);
    assertThat(stale.jsonPath().getString("code")).isEqualTo("CONCURRENT_MODIFICATION");

    // Releitura: o 409 não deixa a alteração pela metade, nem na API nem no banco.
    Map<String, Object> json = getDetail(id.toString()).jsonPath().getMap("$");
    assertThat(json.get("name")).isEqualTo(edited);
    assertThat(json.get("unit")).isEqualTo("KG");
    assertThat(json.get("version")).as("a versão não avançou").isEqualTo(1);
    assertThat(storedName(id)).isEqualTo(edited);
  }

  @Test
  @DisplayName("PUT sem If-Match responde 428 IF_MATCH_REQUIRED e não altera o produto")
  void requiresIfMatch() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    for (String missing : Arrays.asList(null, "   ")) {
      Response response =
          put(
              adminToken(),
              id,
              missing,
              updateBody(name("Arroz Tipo 1 5kg"), null, "KG", null, null));

      assertThat(response.statusCode()).as("If-Match %s", missing).isEqualTo(428);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("type"))
          .isEqualTo("https://minimarket.local/problems/if-match-required");
      assertThat(response.jsonPath().getString("title"))
          .isEqualTo("Cabeçalho If-Match obrigatório");
      assertThat(response.jsonPath().getInt("status")).isEqualTo(428);
      assertThat(response.jsonPath().getString("code")).isEqualTo("IF_MATCH_REQUIRED");
    }

    assertThat(storedName(id)).as("o 428 barra antes do caso de uso").isEqualTo(name("Arroz 5kg"));
  }

  @Test
  @DisplayName(
      "If-Match que não é versão (texto, negativo ou decimal) responde 400 VALIDATION_ERROR")
  void rejectsMalformedIfMatch() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    for (String malformed : Arrays.asList("abc", "-1", "1.5")) {
      Response response =
          put(
              adminToken(),
              id,
              malformed,
              updateBody(name("Arroz Tipo 1 5kg"), null, "KG", null, null));

      assertThat(response.statusCode()).as("If-Match %s", malformed).isEqualTo(400);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    assertThat(storedName(id)).isEqualTo(name("Arroz 5kg"));
  }

  @Test
  @DisplayName(
      "PUT de produto inexistente, desativado ou soft-deletado responde 404 PRODUCT_NOT_FOUND")
  void hidesUnknownInactiveAndDeletedFromUpdate() throws SQLException {
    UUID inactive = seedProduct("Detergente 500ml", "3.79", null);
    setActiveDirectly(inactive, false);
    UUID deleted = seedProduct("Café 500g", "18.90", null);
    softDeleteViaPort(deleted);

    for (UUID id : List.of(UUID.randomUUID(), inactive, deleted)) {
      Response response =
          put(
              adminToken(),
              id,
              "\"0\"",
              updateBody(name("Arroz Tipo 1 5kg"), null, "KG", null, null));

      assertThat(response.statusCode()).as("produto %s", id).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
    }
  }

  @Test
  @DisplayName("unidade fora de UN/KG (ou ausente) responde 400 e não altera o produto")
  void rejectsInvalidUnitOnUpdate() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    Response onUnknownUnit =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Arroz Tipo 1 5kg"), null, "CX", "grão longo", null));

    assertThat(onUnknownUnit.statusCode()).isEqualTo(400);
    assertThat(onUnknownUnit.contentType()).contains("application/problem+json");
    assertThat(onUnknownUnit.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");

    Response onMissingUnit =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Arroz Tipo 1 5kg"), null, null, "grão longo", null));

    assertThat(onMissingUnit.statusCode()).isEqualTo(400);
    assertThat(onMissingUnit.jsonPath().getList("errors.field", String.class)).contains("unit");

    assertThat(storedName(id)).isEqualTo(name("Arroz 5kg"));
  }

  @Test
  @DisplayName(
      "categoria inexistente no PUT responde 404 CATEGORY_NOT_FOUND e não altera o produto")
  void rejectsUnknownCategoryOnUpdate() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    Response response =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Arroz Tipo 1 5kg"), UUID.randomUUID(), "KG", null, null));

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");
    assertThat(storedName(id)).isEqualTo(name("Arroz 5kg"));
  }

  @Test
  @DisplayName(
      "PUT com internalCode substitui o código (com zeros à esquerda) e o campo ausente limpa")
  void updatesAndClearsInternalCode() throws SQLException {
    UUID id = seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);

    Response set =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Banana prata kg"), "42", null, "KG", null, null));

    assertThat(set.statusCode()).isEqualTo(200);
    assertThat(set.jsonPath().getString("internalCode"))
        .as("o cadastro completa o curto com zeros")
        .isEqualTo("00042");
    assertThat(productOf(id.toString()).internalCode()).isEqualTo("00042");
    assertThat(getByBarcode("00042").jsonPath().getString("id"))
        .as("o código novo resolve no bipe")
        .isEqualTo(id.toString());

    Response cleared =
        put(adminToken(), id, "\"1\"", updateBody(name("Banana prata kg"), null, "KG", null, null));

    assertThat(cleared.statusCode()).isEqualTo(200);
    assertThat(cleared.jsonPath().getString("internalCode"))
        .as("o campo ausente do PUT limpa o código")
        .isNull();
    assertThat(productOf(id.toString()).internalCode()).isNull();
    assertThat(getByBarcode("00042").statusCode()).as("código limpo não resolve").isEqualTo(404);
  }

  @Test
  @DisplayName("PUT com o próprio código interno é aceito: não colide consigo mesmo")
  void acceptsOwnInternalCodeOnUpdate() throws SQLException {
    UUID id = seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);

    Response response =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Banana prata kg"), INTERNAL_CODE, null, "KG", null, null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("internalCode")).isEqualTo(INTERNAL_CODE);
    assertThat(productOf(id.toString()).internalCode()).isEqualTo(INTERNAL_CODE);
  }

  @Test
  @DisplayName(
      "PUT com código interno de outro produto vivo responde 409 INTERNAL_CODE_ALREADY_EXISTS")
  void rejectsInternalCodeTakenOnUpdate() throws SQLException {
    seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);
    UUID id = seedProduct("Tomate", "9.90", "KG", null, null, null);

    Response response =
        put(
            adminToken(),
            id,
            "\"0\"",
            updateBody(name("Tomate kg"), INTERNAL_CODE, null, "KG", null, null));

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INTERNAL_CODE_ALREADY_EXISTS");
    assertThat(response.jsonPath().getString("detail")).contains("código interno");
    assertThat(productOf(id.toString()).internalCode()).as("o 409 não grava").isNull();
  }

  @Test
  @DisplayName("PUT grava PRODUCT_UPDATED com o antes/depois dos campos editados")
  void auditsProductUpdated() throws SQLException {
    UUID categoryId = seedCategory("Bebidas");
    UUID id = seedProduct("Arroz 5kg", "24.90", null);
    String newName = name("Arroz Tipo 1 5kg");

    assertThat(
            put(
                    adminToken(),
                    id,
                    "\"0\"",
                    updateBody(newName, "123", categoryId, "KG", "grão longo tipo 1", "2.500"))
                .statusCode())
        .isEqualTo(200);

    UpdateEvent event = updateEventOf(id);
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);
    assertThat(event.beforeName()).isEqualTo(name("Arroz 5kg"));
    assertThat(event.beforeCategoryId()).as("produto nasceu sem categoria").isNull();
    assertThat(event.beforeUnit()).isEqualTo("UN");
    assertThat(event.beforeDescription()).isEqualTo("descrição de Arroz 5kg");
    assertThat(event.beforeMinQuantity()).as("produto nasceu sem mínimo").isNull();
    assertThat(event.afterName()).isEqualTo(newName);
    assertThat(event.afterCategoryId()).isEqualTo(categoryId.toString());
    assertThat(event.afterUnit()).isEqualTo("KG");
    assertThat(event.afterDescription()).isEqualTo("grão longo tipo 1");
    assertThat(event.afterMinQuantity()).isEqualTo("2.500");
    assertThat(event.beforeInternalCode())
        .as("produto nasceu sem código interno (passo 1104d)")
        .isNull();
    assertThat(event.afterInternalCode())
        .as("o código interno da etiqueta entra no antes/depois")
        .isEqualTo("00123");
  }

  @Test
  @DisplayName(
      "PATCH /api/v1/products/{id}/price muda o preço e grava PRODUCT_PRICE_CHANGED com motivo")
  void changesPriceAndAudits() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);

    Response response = patchPrice(adminToken(), id, priceBody("19.99", "promoção do dia"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(number(json.get("price"))).isEqualByComparingTo("19.99");
    assertThat(json.get("version")).as("lock otimista avançou").isEqualTo(1);
    assertThat(json.get("barcode")).as("o preço não mexe no cadastro").isEqualTo(STORED_BARCODE);
    assertThat(json.get("name")).isEqualTo(name("Arroz 5kg"));
    assertThat(json.get("active")).isEqualTo(true);
    assertThat(storedPrice(id)).as("o banco guardou o preço novo").isEqualTo("19.99");

    PriceEvent event = priceEventOf(id);
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);
    assertThat(event.reason()).isEqualTo("promoção do dia");
    assertThat(event.beforePrice()).isEqualTo("24.90");
    assertThat(event.afterPrice()).isEqualTo("19.99");
  }

  @Test
  @DisplayName("OPERADOR não altera preço: 403 ACCESS_DENIED citando price.write, sem gravar nada")
  void deniesOperatorOnPriceChange() throws SQLException {
    String username = "produtos.preco.operador." + SUFFIX;
    createUser(username, OPERATOR_ROLE);
    String token = login(username);
    UUID id = seedProduct("Café 500g", "18.90", null);

    Response response = patchPrice(token, id, priceBody("17.90", "promoção"));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("detail")).contains("price.write");
    assertThat(storedPrice(id)).as("o 403 barra antes do caso de uso").isEqualTo("18.90");
    assertThat(priceEventCount(id)).as("o 403 não audita").isZero();
  }

  @Test
  @DisplayName("PATCH de preço sem motivo, com motivo em branco ou preço negativo responde 400")
  void rejectsInvalidPriceChangeShape() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    assertPriceChangeInvalid(id, "{\"price\": 19.99}", "reason");
    assertPriceChangeInvalid(id, "{\"price\": 19.99, \"reason\": \"   \"}", "reason");
    assertPriceChangeInvalid(id, "{\"price\": -0.01, \"reason\": \"promoção\"}", "price");

    assertThat(storedPrice(id)).as("o 400 não altera o preço").isEqualTo("24.90");
    assertThat(priceEventCount(id)).as("o 400 não audita").isZero();
  }

  @Test
  @DisplayName("PATCH de preço de produto inexistente, desativado ou soft-deletado responde 404")
  void hidesUnknownInactiveAndDeletedFromPriceChange() throws SQLException {
    UUID inactive = seedProduct("Detergente 500ml", "3.79", null);
    setActiveDirectly(inactive, false);
    UUID deleted = seedProduct("Café 500g", "18.90", null);
    softDeleteViaPort(deleted);

    for (UUID id : List.of(UUID.randomUUID(), inactive, deleted)) {
      Response response = patchPrice(adminToken(), id, priceBody("1.00", "promoção"));

      assertThat(response.statusCode()).as("produto %s", id).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
      assertThat(priceEventCount(id)).as("o 404 não audita").isZero();
    }

    assertThat(storedPrice(deleted)).as("o 404 não altera o preço").isEqualTo("18.90");
  }

  @Test
  @DisplayName("preço igual ao atual é no-op: 200 com o produto e sem evento novo de auditoria")
  void keepsPriceWhenUnchanged() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    // 24.9 e 24.90 são o mesmo preço: a normalização em escala 2 não pode inventar um reajuste.
    Response response = patchPrice(adminToken(), id, priceBody("24.9", "reajuste sem efeito"));

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(number(json.get("price"))).isEqualByComparingTo("24.90");
    assertThat(json.get("version")).as("o no-op não toca na linha").isEqualTo(0);
    assertThat(storedPrice(id)).isEqualTo("24.90");
    assertThat(priceEventCount(id)).as("o no-op não inventa evento").isZero();
  }

  @Test
  @DisplayName(
      "POST /api/v1/products/{id}/disable responde 200 com active=false e tira o produto do catálogo")
  void disablesProduct() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);

    Response response = postStatus(adminToken(), id, "disable");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");

    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(json.get("id")).isEqualTo(id.toString());
    assertThat(json.get("active")).isEqualTo(false);
    assertThat(json.get("version")).as("a desativação grava e devolve a versão nova").isEqualTo(1);

    StoredProduct stored = productOf(id.toString());
    assertThat(stored.active()).as("o banco guardou o active falso").isFalse();
    assertThat(stored.deleted()).as("o soft delete acompanha a desativação").isTrue();

    assertThat(names(list("search", SUFFIX)))
        .as("produto desativado não aparece na busca padrão")
        .doesNotContain(name("Arroz 5kg"));
    assertThat(getDetail(id.toString()).statusCode()).as("o detalhe também esconde").isEqualTo(404);
    assertThat(getByBarcode(STORED_BARCODE).statusCode())
        .as("o bipe não resolve mais")
        .isEqualTo(404);
  }

  @Test
  @DisplayName(
      "o barcode do produto desativado é liberado: o produto novo com o mesmo código dá 201")
  void freesBarcodeAfterDisable() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);
    assertThat(postStatus(adminToken(), id, "disable").statusCode()).isEqualTo(200);

    Response response = post(adminToken(), validBody(name("Arroz 1kg"), STORED_BARCODE));

    assertThat(response.statusCode())
        .as("o índice único parcial ignora o produto desativado")
        .isEqualTo(201);
    assertThat(response.jsonPath().getString("barcode")).isEqualTo(STORED_BARCODE);
    assertThat(countActiveByBarcode(STORED_BARCODE)).as("só o produto novo está vivo").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "POST /api/v1/products/{id}/enable responde 200 com active=true e devolve o produto ao catálogo")
  void enablesProduct() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);
    assertThat(postStatus(adminToken(), id, "disable").statusCode()).isEqualTo(200);

    Response response = postStatus(adminToken(), id, "enable");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getMap("$"))
        .as("contrato: nem storeId nem deletedAt vazam")
        .containsOnlyKeys(
            "id",
            "name",
            "barcode",
            "internalCode",
            "description",
            "categoryId",
            "unit",
            "price",
            "minQuantity",
            "active",
            "version",
            "createdAt",
            "updatedAt");
    assertThat(response.jsonPath().getBoolean("active")).isTrue();
    assertThat(response.jsonPath().getInt("version"))
        .as("desativar e reativar, duas gravações")
        .isEqualTo(2);

    assertThat(productOf(id.toString()).deleted()).as("deleted_at limpo").isFalse();
    assertThat(names(list("search", SUFFIX)))
        .as("voltou à busca padrão")
        .contains(name("Arroz 5kg"));
    assertThat(getByBarcode(STORED_BARCODE).jsonPath().getString("id"))
        .as("o bipe resolve de novo")
        .isEqualTo(id.toString());
  }

  @Test
  @DisplayName("enable alcança o produto soft-deletado pela porta: volta à busca padrão e ao bipe")
  void enablesSoftDeletedProduct() {
    UUID id = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);
    softDeleteViaPort(id);

    Response response = postStatus(adminToken(), id, "enable");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getBoolean("active")).isTrue();
    assertThat(names(list("search", SUFFIX))).contains(name("Arroz 5kg"));
    assertThat(getByBarcode(STORED_BARCODE).jsonPath().getString("id")).isEqualTo(id.toString());
  }

  @Test
  @DisplayName("enable de produto já ativo é no-op: 200 com o produto e sem evento novo")
  void keepsActiveProductOnEnable() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);

    Response response = postStatus(adminToken(), id, "enable");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getBoolean("active")).isTrue();
    assertThat(response.jsonPath().getInt("version")).as("o no-op não toca na linha").isZero();
    assertThat(productOf(id.toString()).deleted()).isFalse();
    assertThat(statusEventCount(id, "PRODUCT_ENABLED")).as("o no-op não inventa evento").isZero();
  }

  @Test
  @DisplayName(
      "disable de produto já desativado responde 404 PRODUCT_NOT_FOUND, como o disable de usuário")
  void rejectsDisableOfDisabledProduct() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);
    assertThat(postStatus(adminToken(), id, "disable").statusCode()).isEqualTo(200);

    Response response = postStatus(adminToken(), id, "disable");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(statusEventCount(id, "PRODUCT_DISABLED"))
        .as("o 404 não inventa evento")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("disable e enable de id inexistente respondem 404 PRODUCT_NOT_FOUND")
  void rejectsStatusChangeOfUnknownProduct() {
    UUID unknown = UUID.randomUUID();

    for (String status : List.of("disable", "enable")) {
      Response response = postStatus(adminToken(), unknown, status);

      assertThat(response.statusCode()).as("POST %s de inexistente", status).isEqualTo(404);
      assertThat(response.contentType()).contains("application/problem+json");
      assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
    }
  }

  @Test
  @DisplayName(
      "disable e enable gravam PRODUCT_DISABLED e PRODUCT_ENABLED com o entityId do produto")
  void auditsStatusChanges() throws SQLException {
    UUID id = seedProduct("Arroz 5kg", "24.90", null);
    assertThat(postStatus(adminToken(), id, "disable").statusCode()).isEqualTo(200);
    assertThat(postStatus(adminToken(), id, "enable").statusCode()).isEqualTo(200);

    StatusEvent disabled = statusEventOf(id, "PRODUCT_DISABLED");
    assertThat(disabled.entityType()).isEqualTo("PRODUCT");
    assertThat(disabled.entityId()).as("o evento aponta o produto").isEqualTo(id.toString());
    assertThat(disabled.source())
        .as("evento da requisição autenticada, não de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(disabled.actorUsername()).isEqualTo(TestAdmin.USERNAME);
    assertThat(disabled.beforeActive()).isEqualTo("true");
    assertThat(disabled.afterActive()).isEqualTo("false");

    StatusEvent enabled = statusEventOf(id, "PRODUCT_ENABLED");
    assertThat(enabled.entityType()).isEqualTo("PRODUCT");
    assertThat(enabled.entityId()).isEqualTo(id.toString());
    assertThat(enabled.source()).isNotEqualTo("SYSTEM");
    assertThat(enabled.actorUsername()).isEqualTo(TestAdmin.USERNAME);
    assertThat(enabled.beforeActive()).isEqualTo("false");
    assertThat(enabled.afterActive()).isEqualTo("true");
  }

  @Test
  @DisplayName(
      "enable de produto cujo barcode já foi tomado responde 409 BARCODE_ALREADY_EXISTS e o produto continua desativado")
  void rejectsEnableWhenBarcodeAlreadyTaken() throws SQLException {
    UUID antigo = seedProduct("Arroz 5kg", "24.90", null, STORED_BARCODE);
    assertThat(postStatus(adminToken(), antigo, "disable").statusCode()).isEqualTo(200);
    String novo = createdId(post(adminToken(), validBody(name("Arroz 1kg"), STORED_BARCODE)));

    Response response = postStatus(adminToken(), antigo, "enable");

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("BARCODE_ALREADY_EXISTS");
    assertThat(response.jsonPath().getString("detail")).contains("código de barras");
    assertThat(statusEventCount(antigo, "PRODUCT_ENABLED")).as("o 409 não audita").isZero();
    StoredProduct stored = productOf(antigo.toString());
    assertThat(stored.deleted()).as("o produto continua desativado").isTrue();
    assertThat(stored.active()).isFalse();
    assertThat(countActiveByBarcode(STORED_BARCODE))
        .as("o barcode continua só do produto novo")
        .isEqualTo(1);
    assertThat(getByBarcode(STORED_BARCODE).jsonPath().getString("id"))
        .as("o bipe resolve o produto novo")
        .isEqualTo(novo);
  }

  @Test
  @DisplayName(
      "enable de produto cujo código interno já foi tomado responde 409 e o produto continua desativado")
  void rejectsEnableWhenInternalCodeAlreadyTaken() throws SQLException {
    UUID antigo = seedProduct("Banana prata", "7.49", "KG", null, null, INTERNAL_CODE);
    assertThat(postStatus(adminToken(), antigo, "disable").statusCode()).isEqualTo(200);
    UUID novo = seedProduct("Banana nanica", "5.99", "KG", null, null, INTERNAL_CODE);

    Response response = postStatus(adminToken(), antigo, "enable");

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INTERNAL_CODE_ALREADY_EXISTS");
    assertThat(response.jsonPath().getString("detail")).contains("código interno");
    assertThat(statusEventCount(antigo, "PRODUCT_ENABLED")).as("o 409 não audita").isZero();
    StoredProduct stored = productOf(antigo.toString());
    assertThat(stored.deleted()).as("o produto continua desativado").isTrue();
    assertThat(stored.active()).isFalse();
    assertThat(countActiveByInternalCode(INTERNAL_CODE))
        .as("o código interno continua só do produto novo")
        .isEqualTo(1);
    assertThat(getByBarcode(INTERNAL_CODE).jsonPath().getString("id"))
        .as("o bipe resolve o produto novo")
        .isEqualTo(novo.toString());
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos de
   * auditoria (do OPERADOR e os que apontam para os produtos criados) antes das sessões, do usuário
   * e dos produtos que eles referenciam, e as categorias depois dos produtos (a FK {@code
   * products.category_id} é {@code on delete restrict}).
   */
  @AfterEach
  void removeRowsCreatedByThisTest() throws SQLException {
    setStoreScale("WEIGHT", 3);
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
          "delete from audit_events where entity_id in"
              + " (select id from products where name like ? or barcode like ?)",
          suffixLike,
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
      delete(connection, "delete from products where name like ?", suffixLike);
      delete(connection, "delete from products where barcode like ?", suffixLike);
      delete(connection, "delete from categories where name like ?", suffixLike);
    }
  }

  /** Cria o OPERADOR pelo caso de uso (passo 107); o login é o de verdade. */
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

  /** Nome de produto com o sufixo da classe, para a limpeza no fim do teste. */
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

  /** GET no caminho quente do bipe como ADMIN; o RestAssured encoda o path param do barcode. */
  private Response getByBarcode(String barcode) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .pathParam("barcode", barcode)
        .when()
        .get(PATH + "/barcode/{barcode}")
        .then()
        .extract()
        .response();
  }

  /**
   * PUT na edição (passo 410) com o token e o {@code If-Match} informados; {@code ifMatch} nulo
   * omite o cabeçalho, como o cliente que esqueceu de mandá-lo.
   */
  private static Response put(String token, UUID id, String ifMatch, String body) {
    RequestSpecification request =
        given().header(AUTHORIZATION, "Bearer " + token).contentType("application/json").body(body);
    if (ifMatch != null) {
      request = request.header("If-Match", ifMatch);
    }
    return request.when().put(PATH + "/" + id).then().extract().response();
  }

  /** PATCH da alteração de preço (passo 411) com o token e o corpo JSON informados. */
  private static Response patchPrice(String token, UUID id, String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .patch(PATH + "/" + id + "/price")
        .then()
        .extract()
        .response();
  }

  /** Corpo do PATCH de preço com o preço e o motivo informados. */
  private static String priceBody(String price, String reason) {
    return "{\"price\": %s, \"reason\": \"%s\"}".formatted(price, reason);
  }

  /** POST do ciclo de vida do produto (passo 412): {@code disable} ou {@code enable}, sem corpo. */
  private static Response postStatus(String token, UUID id, String status) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .post(PATH + "/" + id + "/" + status)
        .then()
        .extract()
        .response();
  }

  /** O 400 padrão da bean validation no PATCH de preço: campo apontado em {@code errors[]}. */
  private void assertPriceChangeInvalid(UUID id, String body, String field) {
    Response response = patchPrice(adminToken(), id, body);

    assertThat(response.statusCode()).as("corpo %s", body).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getList("errors.field", String.class))
        .as("campo apontado")
        .contains(field);
  }

  /**
   * Versão do produto como o detalhe a devolve: é o valor que o cliente manda no {@code If-Match}.
   */
  private int versionOf(UUID id) {
    Response detail = getDetail(id.toString());

    assertThat(detail.statusCode())
        .as("detalhe para ler a versão: %s", detail.asString())
        .isEqualTo(200);
    return detail.jsonPath().getInt("version");
  }

  /** Nanossegundos em milissegundos fracionários, só para a mensagem do smoke de tempo. */
  private static double millis(long nanos) {
    return nanos / 1_000_000.0;
  }

  /** GET no detalhe como ADMIN exigindo 404; o formato do problem fica com cada teste. */
  private Response getDetailExpectingNotFound(String id) {
    Response response = getDetail(id);

    assertThat(response.statusCode()).as("detalhe de %s precisa ser 404", id).isEqualTo(404);
    return response;
  }

  /** Nomes dos itens da página, na ordem devolvida. */
  private static List<String> names(Response response) {
    return response.jsonPath().getList("items.name", String.class);
  }

  /** O 400 padrão de parâmetro inválido: problem+json com {@code VALIDATION_ERROR}. */
  private static void assertBadRequest(Response response) {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
  }

  /** Categoria da fixture pela porta, sem caso de uso nem auditoria — o alvo é a listagem. */
  private UUID seedCategory(String base) {
    return QuarkusTransaction.requiringNew()
        .call(() -> categoryStore.insert(new NewCategory(storeId(), name(base), null, 0)));
  }

  /** Produto da fixture pela porta, sem caso de uso nem auditoria — o alvo é a listagem. */
  private UUID seedProduct(String base, String price, UUID categoryId) {
    return seedProduct(base, price, categoryId, null);
  }

  /**
   * Produto da fixture com barcode gravado: o bipe precisa do código na linha, como o 405 grava.
   */
  private UUID seedProduct(String base, String price, UUID categoryId, String barcode) {
    return seedProduct(base, price, "UN", categoryId, barcode, null);
  }

  /**
   * Produto da fixture com barcode, unidade e código interno (1104b1) gravados: o código interno é
   * o PLU que a etiqueta de balança embute e o cenário do 1104b3 precisa dele na linha.
   */
  private UUID seedProduct(
      String base,
      String price,
      String unit,
      UUID categoryId,
      String barcode,
      String internalCode) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        name(base),
                        barcode,
                        internalCode,
                        "descrição de " + base,
                        categoryId,
                        unit,
                        new BigDecimal(price),
                        null)));
  }

  /** Etiqueta de peso da loja: prefixo "2" + código interno + o valor embutido de 1,234 kg. */
  private static String weightLabel() {
    return weightLabel(INTERNAL_CODE);
  }

  /** A mesma etiqueta com outro código interno: o PLU da loja tem os 5 dígitos configurados. */
  private static String weightLabel(String internalCode) {
    return "2" + internalCode + "0001234";
  }

  /** Etiqueta de preço da loja: prefixo "2" + código interno + R$ 19,99 embutidos (2 casas). */
  private static String priceLabel() {
    return "2" + INTERNAL_CODE + "0001999";
  }

  /**
   * Troca os parâmetros da etiqueta da loja (passo 1104b1) direto no banco: a MATRIZ nasce com peso
   * e 3 casas e a etiqueta de preço precisa da loja configurada para preço. O {@code
   * removeRowsCreatedByThisTest} restaura ao fim de cada teste.
   */
  private void setStoreScale(String field, int decimals) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update stores set scale_embedded_field = ?, scale_embedded_decimals = ?"
                    + " where code = ?")) {
      statement.setString(1, field);
      statement.setInt(2, decimals);
      statement.setString(3, defaultStoreCode);
      statement.executeUpdate();
    }
  }

  /** Soft delete pela porta, como o caso de uso do passo 412 fará — sem auditoria aqui. */
  private void softDeleteViaPort(UUID id) {
    QuarkusTransaction.requiringNew().run(() -> productStore.softDelete(id));
  }

  /** {@code count} produtos de preço fixo, numa transação só: a paginação precisa de massa. */
  private void seedProducts(int count) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (int index = 1; index <= count; index++) {
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        name("Produto %02d".formatted(index)),
                        null,
                        null,
                        null,
                        "UN",
                        new BigDecimal("10.00"),
                        null));
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

  /**
   * Marca {@code active} direto no banco, com {@code deleted_at} nulo: desativar é regra do caso de
   * uso do passo 412 (com auditoria) e o filtro por status precisa de um produto desativado.
   */
  private void setActiveDirectly(UUID id, boolean active) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("update products set active = ? where id = ?::uuid")) {
      statement.setBoolean(1, active);
      statement.setString(2, id.toString());
      statement.executeUpdate();
    }
  }

  /**
   * Valor numérico do JSON como BigDecimal: o parser pode devolver Integer, Double ou BigDecimal.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no corpo da resposta").isNotNull();
    return new BigDecimal(value.toString());
  }

  /** Corpo do POST que o caso de uso aceita: só o nome e o barcode variam entre os testes. */
  private static String validBody(String name, String barcode) {
    return """
        {"name": "%s", "barcode": "%s", "description": "grão longo", "unit": "UN", "price": 12.5}
        """
        .formatted(name, barcode);
  }

  /**
   * Corpo do PUT com os cinco campos da edição (passo 410). Campo nulo fica fora do JSON, como o
   * cliente o omite; {@code unit} nulo é a exceção — o teste da bean validation precisa mandá-lo
   * nulo de propósito.
   */
  private static String updateBody(
      String name, UUID categoryId, String unit, String description, Object minQuantity) {
    return updateBody(name, null, categoryId, unit, description, minQuantity);
  }

  /**
   * Corpo do PUT com o código interno da etiqueta (passo 1104d): {@code internalCode} nulo omite o
   * campo, que é exatamente a semântica de limpar do PUT.
   */
  private static String updateBody(
      String name,
      String internalCode,
      UUID categoryId,
      String unit,
      String description,
      Object minQuantity) {
    StringBuilder json = new StringBuilder("{\"name\": \"").append(name).append('"');
    if (internalCode != null) {
      json.append(", \"internalCode\": \"").append(internalCode).append('"');
    }
    if (categoryId != null) {
      json.append(", \"categoryId\": \"").append(categoryId).append('"');
    }
    json.append(", \"unit\": ").append(unit == null ? "null" : "\"" + unit + "\"");
    if (description != null) {
      json.append(", \"description\": \"").append(description).append('"');
    }
    if (minQuantity != null) {
      json.append(", \"minQuantity\": ").append(minQuantity);
    }
    return json.append('}').toString();
  }

  /** O 400 padrão da bean validation: problem+json com o campo apontado em {@code errors[]}. */
  private void assertInvalid(String body, String field) {
    Response response = post(adminToken(), body);

    assertThat(response.statusCode()).as("corpo %s", body).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getList("errors.field", String.class))
        .as("campo apontado")
        .contains(field);
  }

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

  /** Id do corpo de um 201; falha com o status quando a criação não aconteceu. */
  private static String createdId(Response response) {
    assertThat(response.statusCode())
        .as("criação da fixture: %s", response.asString())
        .isEqualTo(201);
    return response.jsonPath().getString("id");
  }

  /** Barcode, código interno, preço (numeric textual), active e soft delete do produto no banco. */
  private StoredProduct productOf(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select barcode, internal_code, price::text as price, active,"
                    + " deleted_at is not null as deleted from products where id = ?::uuid")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return new StoredProduct(
            resultSet.getString("barcode"),
            resultSet.getString("internal_code"),
            resultSet.getString("price"),
            resultSet.getBoolean("active"),
            resultSet.getBoolean("deleted"));
      }
    }
  }

  /** Nome do produto no banco; falha quando a linha não existe. */
  private String storedName(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select name from products where id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return resultSet.getString("name");
      }
    }
  }

  /**
   * Preço do produto no banco, no formato textual do numeric(14,2); falha se a linha não existe.
   */
  private String storedPrice(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select price::text as price from products where id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto %s gravado", id).isTrue();
        return resultSet.getString("price");
      }
    }
  }

  /**
   * Evento {@code PRODUCT_UPDATED} do produto, com o antes/depois extraído do jsonb por chave;
   * falha se houver zero ou mais de um evento para o alvo.
   */
  private UpdateEvent updateEventOf(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, source, actor_username,"
                    + " details->'before'->>'name' as before_name,"
                    + " details->'before'->>'categoryId' as before_category_id,"
                    + " details->'before'->>'unit' as before_unit,"
                    + " details->'before'->>'description' as before_description,"
                    + " details->'before'->>'minQuantity' as before_min_quantity,"
                    + " details->'before'->>'internalCode' as before_internal_code,"
                    + " details->'after'->>'name' as after_name,"
                    + " details->'after'->>'categoryId' as after_category_id,"
                    + " details->'after'->>'unit' as after_unit,"
                    + " details->'after'->>'description' as after_description,"
                    + " details->'after'->>'minQuantity' as after_min_quantity,"
                    + " details->'after'->>'internalCode' as after_internal_code"
                    + " from audit_events where action = 'PRODUCT_UPDATED' and entity_id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento PRODUCT_UPDATED do produto %s", id).isTrue();
        UpdateEvent event =
            new UpdateEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("before_name"),
                resultSet.getString("before_category_id"),
                resultSet.getString("before_unit"),
                resultSet.getString("before_description"),
                resultSet.getString("before_min_quantity"),
                resultSet.getString("before_internal_code"),
                resultSet.getString("after_name"),
                resultSet.getString("after_category_id"),
                resultSet.getString("after_unit"),
                resultSet.getString("after_description"),
                resultSet.getString("after_min_quantity"),
                resultSet.getString("after_internal_code"));
        assertThat(resultSet.next()).as("uma edição, um evento").isFalse();
        return event;
      }
    }
  }

  /**
   * Evento {@code PRODUCT_PRICE_CHANGED} do produto, com o antes/depois extraído do jsonb por
   * chave; falha se houver zero ou mais de um evento para o alvo.
   */
  private PriceEvent priceEventOf(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, source, actor_username, reason,"
                    + " details->'before'->>'price' as before_price,"
                    + " details->'after'->>'price' as after_price"
                    + " from audit_events where action = 'PRODUCT_PRICE_CHANGED'"
                    + " and entity_id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento PRODUCT_PRICE_CHANGED do produto %s", id).isTrue();
        PriceEvent event =
            new PriceEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("reason"),
                resultSet.getString("before_price"),
                resultSet.getString("after_price"));
        assertThat(resultSet.next()).as("uma alteração, um evento").isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos {@code PRODUCT_PRICE_CHANGED} o produto tem; o no-op e os 4xx exigem zero. */
  private int priceEventCount(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where action = 'PRODUCT_PRICE_CHANGED'"
                    + " and entity_id = ?::uuid")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /**
   * Evento {@code PRODUCT_DISABLED} ou {@code PRODUCT_ENABLED} do produto, com o antes/depois do
   * {@code active} extraído do jsonb; falha se houver zero ou mais de um evento do alvo.
   */
  private StatusEvent statusEventOf(UUID id, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id::text as entity_id, source, actor_username,"
                    + " details->'before'->>'active' as before_active,"
                    + " details->'after'->>'active' as after_active"
                    + " from audit_events where action = ? and entity_id = ?::uuid")) {
      statement.setString(1, action);
      statement.setString(2, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s do produto %s", action, id).isTrue();
        StatusEvent event =
            new StatusEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("entity_id"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("before_active"),
                resultSet.getString("after_active"));
        assertThat(resultSet.next()).as("uma operação, um evento").isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos da ação o produto tem; o no-op e os 4xx exigem zero. */
  private int statusEventCount(UUID id, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where action = ? and entity_id = ?::uuid")) {
      statement.setString(1, action);
      statement.setString(2, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Quantos produtos vivos existem com o código interno informado; os 4xx não podem criar. */
  private int countActiveByInternalCode(String internalCode) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from products where internal_code = ? and deleted_at is null")) {
      statement.setString(1, internalCode);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Quantos produtos vivos existem com o barcode informado; o 409 e o 403 não podem criar. */
  private int countActiveByBarcode(String barcode) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from products where barcode = ? and deleted_at is null")) {
      statement.setString(1, barcode);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
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

  /** Linha de {@code products} como o banco a guardou; preço no formato textual do numeric. */
  private record StoredProduct(
      String barcode, String internalCode, String price, boolean active, boolean deleted) {}

  /** Linha de {@code audit_events} do {@code PRODUCT_PRICE_CHANGED} com o antes/depois extraído. */
  private record PriceEvent(
      String entityType,
      String source,
      String actorUsername,
      String reason,
      String beforePrice,
      String afterPrice) {}

  /** Linha de {@code audit_events} do {@code PRODUCT_UPDATED} com o antes/depois já extraído. */
  private record UpdateEvent(
      String entityType,
      String source,
      String actorUsername,
      String beforeName,
      String beforeCategoryId,
      String beforeUnit,
      String beforeDescription,
      String beforeMinQuantity,
      String beforeInternalCode,
      String afterName,
      String afterCategoryId,
      String afterUnit,
      String afterDescription,
      String afterMinQuantity,
      String afterInternalCode) {}

  /**
   * Linha de {@code audit_events} do ciclo de vida do produto (passo 412), com o antes/depois do
   * {@code active} extraído do jsonb.
   */
  private record StatusEvent(
      String entityType,
      String entityId,
      String source,
      String actorUsername,
      String beforeActive,
      String afterActive) {}
}
