package com.minimarket.inventory.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ApplyStockMovementCommand;
import com.minimarket.inventory.application.StockService;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consulta de estoque na API (passo 704): a lista com busca, filtro de estoque baixo e paginação, e
 * o detalhe com saldo e últimos movimentos, contra PostgreSQL real (Dev Services) — a rota de
 * verdade com o ADMIN da fixture, como nos testes de catálogo e caixa.
 *
 * <p>As fixtures saem das portas ({@code ProductStore}, {@code UserStore}) e do {@code
 * StockService} (passo 703), em transação própria: o saldo do cenário é o que o serviço aplicou de
 * verdade. O request HTTP commita, então o {@link #removeRowsCreatedByThisTest()} apaga ao fim de
 * cada teste na ordem das FKs — movimentos, saldos, produtos e o operador do ledger.
 */
@QuarkusTest
class StockResourceTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes e barcodes nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/stock";
  private static final String OPERATOR_USERNAME = "estoque.api." + SUFFIX;

  /** Portas para semear produtos e o operador do ledger, sem caso de uso nem auditoria. */
  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  /**
   * Caminho de escrita do saldo (passo 703): o cenário tem saldo de verdade, não SQL de fixture.
   */
  @Inject StockService stockService;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Id da loja do seed da V1, resolvido no primeiro uso. */
  private UUID storeId;

  /** Operador das FKs de {@code stock_movements}, criado no primeiro movimento. */
  private UUID operatorId;

  @Test
  @DisplayName(
      "lista devolve o saldo após os movimentos e zero para o produto que nunca teve movimento")
  void listsBalancesAfterMovements() {
    UUID arroz = seedProduct("Arroz", "111" + SUFFIX, null);
    seedProduct("Feijao", "222" + SUFFIX, null);
    receive(arroz, "10.000");
    applyMovement(arroz, StockMovementType.SALE_OUT, "-3.000");

    Response response = list("search", SUFFIX);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getInt("totalItems")).isEqualTo(2);
    assertThat(response.jsonPath().getString("page")).isEqualTo("0");
    assertThat(response.jsonPath().getInt("size")).isEqualTo(20);

    List<Map<String, Object>> items = itemsOf(response);
    assertThat(items).hasSize(2);
    Map<String, Object> arrozItem = itemNamed(items, name("Arroz"));
    assertThat(arrozItem)
        .as("contrato do item: nem storeId nem entidade do saldo vazam")
        .containsOnlyKeys(
            "productId", "name", "barcode", "unit", "quantity", "minQuantity", "lowStock");
    assertThat(arrozItem.get("productId")).isEqualTo(arroz.toString());
    assertThat(arrozItem.get("barcode")).isEqualTo("111" + SUFFIX);
    assertThat(arrozItem.get("unit")).isEqualTo("UN");
    assertThat(number(arrozItem.get("quantity")))
        .as("10 recebidos, 3 vendidos")
        .isEqualByComparingTo("7.000");
    assertThat(arrozItem.get("minQuantity")).as("produto sem mínimo configurado").isNull();
    assertThat(arrozItem.get("lowStock"))
        .as("sem mínimo configurado nunca é estoque baixo")
        .isEqualTo(false);

    Map<String, Object> feijaoItem = itemNamed(items, name("Feijao"));
    assertThat(number(feijaoItem.get("quantity")))
        .as("produto sem linha de saldo aparece com zero")
        .isEqualByComparingTo("0");
    assertThat(feijaoItem.get("lowStock")).isEqualTo(false);
  }

  @Test
  @DisplayName(
      "search casa trecho do nome sem diferenciar maiúsculas ou o barcode exato, nunca por trecho dele")
  void searchesByNameAndBarcode() {
    seedProduct("Arroz Integral", "111" + SUFFIX, null);
    seedProduct("Feijao Preto", "222" + SUFFIX, null);

    Response byName = list("search", name("integral").toUpperCase(Locale.ROOT));
    assertThat(byName.jsonPath().getList("items.name", String.class))
        .containsExactly(name("Arroz Integral"));

    Response byBarcode = list("search", "222" + SUFFIX);
    assertThat(byBarcode.jsonPath().getList("items.name", String.class))
        .containsExactly(name("Feijao Preto"));

    Response partialBarcode = list("search", "111" + SUFFIX.substring(0, 4));
    assertThat(partialBarcode.jsonPath().getList("items"))
        .as("o barcode casa exato, nunca por trecho")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "lowStock=true devolve só mínimo configurado com saldo no mínimo ou abaixo; false devolve o complemento")
  void filtersLowStock() {
    UUID arroz = seedProduct("Arroz", "333" + SUFFIX, new BigDecimal("5.000"));
    UUID cafe = seedProduct("Cafe", "444" + SUFFIX, new BigDecimal("5.000"));
    UUID banana = seedProduct("Banana", "555" + SUFFIX, null);
    receive(arroz, "5.000");
    receive(cafe, "6.000");
    receive(banana, "1.000");

    Response all = list("search", SUFFIX);
    assertThat(itemsOf(all)).hasSize(3);

    Response low = list("search", SUFFIX, "lowStock", "true");
    assertThat(low.jsonPath().getList("items.name", String.class))
        .as("saldo 5 no mínimo 5 é estoque baixo; 6 acima e sem mínimo não são")
        .containsExactly(name("Arroz"));
    assertThat(low.jsonPath().getBoolean("items[0].lowStock")).isTrue();

    Response notLow = list("search", SUFFIX, "lowStock", "false");
    assertThat(notLow.jsonPath().getList("items.name", String.class))
        .as("ordem estável por nome")
        .containsExactly(name("Banana"), name("Cafe"));
  }

  @Test
  @DisplayName(
      "paginação: page/size recortam a lista em ordem de nome e totalItems/totalPages vêm do count")
  void paginatesWithStableOrder() {
    seedProducts(25);

    Response firstPage = list("search", SUFFIX, "page", "0", "size", "10");

    assertThat(firstPage.statusCode()).isEqualTo(200);
    assertThat(firstPage.jsonPath().getInt("page")).isZero();
    assertThat(firstPage.jsonPath().getInt("size")).isEqualTo(10);
    assertThat(firstPage.jsonPath().getInt("totalItems")).isEqualTo(25);
    assertThat(firstPage.jsonPath().getInt("totalPages")).isEqualTo(3);
    assertThat(itemsOf(firstPage)).hasSize(10);
    assertThat(firstPage.jsonPath().getList("items.name", String.class))
        .startsWith(name("Estoque 01"));

    Response lastPage = list("search", SUFFIX, "page", "2", "size", "10");
    assertThat(itemsOf(lastPage)).hasSize(5);
    assertThat(lastPage.jsonPath().getList("items.name", String.class))
        .as("a página seguinte continua a ordem por nome")
        .startsWith(name("Estoque 21"));
  }

  @Test
  @DisplayName(
      "detalhe devolve o saldo e os últimos movimentos, do mais recente para o mais antigo")
  void showsDetailWithMovements() {
    UUID productId = seedProduct("Arroz", "777" + SUFFIX, new BigDecimal("2.000"));
    applyMovement(productId, StockMovementType.INITIAL, "10.000");
    applyMovement(productId, StockMovementType.PURCHASE_IN, "5.000");
    applyMovement(productId, StockMovementType.SALE_OUT, "-3.000");

    Response response = detail(productId);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    assertThat(json)
        .as("contrato do detalhe: nem storeId nem entidade do saldo vazam")
        .containsOnlyKeys(
            "productId",
            "name",
            "barcode",
            "unit",
            "quantity",
            "minQuantity",
            "lowStock",
            "movements");
    assertThat(json.get("productId")).isEqualTo(productId.toString());
    assertThat(json.get("name")).isEqualTo(name("Arroz"));
    assertThat(json.get("barcode")).isEqualTo("777" + SUFFIX);
    assertThat(json.get("unit")).isEqualTo("UN");
    assertThat(number(json.get("quantity"))).isEqualByComparingTo("12.000");
    assertThat(number(json.get("minQuantity"))).isEqualByComparingTo("2.000");
    assertThat(json.get("lowStock")).as("12 acima do mínimo de 2").isEqualTo(false);

    List<Map<String, Object>> movements = response.jsonPath().getList("movements");
    assertThat(movements).hasSize(3);
    assertThat(movements.get(0))
        .as("contrato do movimento")
        .containsOnlyKeys(
            "id",
            "type",
            "quantityDelta",
            "balanceAfter",
            "unitCost",
            "referenceType",
            "referenceId",
            "reason",
            "createdByUserId",
            "createdAt");
    assertThat(movements)
        .extracting(movement -> movement.get("type"))
        .containsExactly("SALE_OUT", "PURCHASE_IN", "INITIAL");
    assertThat(number(movements.get(0).get("quantityDelta"))).isEqualByComparingTo("-3.000");
    assertThat(number(movements.get(0).get("balanceAfter"))).isEqualByComparingTo("12.000");
    assertThat(number(movements.get(1).get("quantityDelta"))).isEqualByComparingTo("5.000");
    assertThat(number(movements.get(1).get("balanceAfter"))).isEqualByComparingTo("15.000");
    assertThat(number(movements.get(2).get("quantityDelta"))).isEqualByComparingTo("10.000");
    assertThat(number(movements.get(2).get("balanceAfter"))).isEqualByComparingTo("10.000");
    assertThat(movements)
        .extracting(movement -> movement.get("createdByUserId"))
        .containsOnly(operatorId().toString());
    assertThat(movements.get(0).get("unitCost")).isNull();
    assertThat(movements.get(0).get("referenceType")).isNull();
    assertThat(movements.get(0).get("reason")).isNull();
    assertThat(movements.get(0).get("createdAt")).isNotNull();
    assertThat(
            movements.stream()
                .map(movement -> Instant.parse(movement.get("createdAt").toString()))
                .toList())
        .as("do mais recente para o mais antigo")
        .isSortedAccordingTo(Comparator.reverseOrder());
  }

  @Test
  @DisplayName("detalhe de produto inexistente responde 404 PRODUCT_NOT_FOUND")
  void rejectsUnknownProductOnDetail() {
    Response response = detail(UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName("produto soft-deletado sai da lista e o detalhe dele responde 404 PRODUCT_NOT_FOUND")
  void hidesSoftDeletedProduct() {
    UUID productId = seedProduct("Arroz", "888" + SUFFIX, null);
    receive(productId, "1.000");
    QuarkusTransaction.requiringNew().run(() -> productStore.softDelete(productId));

    Response listResponse = list("search", SUFFIX);
    assertThat(listResponse.jsonPath().getList("items"))
        .as("soft-deletado fora da lista")
        .isEmpty();

    Response detailResponse = detail(productId);
    assertThat(detailResponse.statusCode()).isEqualTo(404);
    assertThat(detailResponse.jsonPath().getString("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  @DisplayName("page negativo ou size menor que 1 respondem 400 VALIDATION_ERROR")
  void rejectsInvalidPagination() {
    assertBadRequest(list("page", "-1"));
    assertBadRequest(list("size", "0"));
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou, na ordem das FKs — o
   * ledger antes do saldo, os dois antes do produto e o operador por último (as três FKs de {@code
   * stock_movements} são {@code on delete restrict}).
   */
  @AfterEach
  void removeRowsCreatedByThisTest() throws SQLException {
    String suffixLike = "%" + SUFFIX;
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from stock_movements where product_id in"
              + " (select id from products where name like ?)",
          suffixLike);
      delete(
          connection,
          "delete from product_stocks where product_id in"
              + " (select id from products where name like ?)",
          suffixLike);
      delete(connection, "delete from products where name like ?", suffixLike);
      delete(connection, "delete from users where username = ?", OPERATOR_USERNAME);
    }
  }

  /** Produto da fixture pela porta, sem caso de uso nem auditoria. */
  private UUID seedProduct(String base, String barcode, BigDecimal minQuantity) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        name(base),
                        barcode,
                        null,
                        null,
                        "UN",
                        new BigDecimal("9.90"),
                        minQuantity)));
  }

  /** {@code count} produtos de nome ordenável, numa transação só: a paginação precisa de massa. */
  private void seedProducts(int count) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (int index = 1; index <= count; index++) {
                productStore.insert(
                    new NewProduct(
                        storeId(),
                        name("Estoque %02d".formatted(index)),
                        null,
                        null,
                        null,
                        "UN",
                        new BigDecimal("10.00"),
                        null));
              }
            });
  }

  /** Entrada de mercadoria no cenário: delta positivo, sem custo nem referência. */
  private void receive(UUID productId, String quantity) {
    applyMovement(productId, StockMovementType.PURCHASE_IN, quantity);
  }

  /** Movimento aplicado pelo caminho de verdade (passo 703), com o operador da fixture. */
  private void applyMovement(UUID productId, StockMovementType type, String quantityDelta) {
    stockService.applyMovement(
        new ApplyStockMovementCommand(
            productId, type, new BigDecimal(quantityDelta), null, null, null, null, operatorId()));
  }

  /** Operador de verdade do ledger: a FK de {@code created_by_user_id} exige usuário real. */
  private UUID operatorId() {
    if (operatorId == null) {
      operatorId =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      userStore.insert(
                          new NewUser(OPERATOR_USERNAME, "Operador de estoque", "hash", "ACTIVE")));
    }
    return operatorId;
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
  private Response detail(UUID productId) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .when()
        .get(PATH + "/" + productId)
        .then()
        .extract()
        .response();
  }

  /** O 400 padrão de parâmetro inválido: problem+json com {@code VALIDATION_ERROR}. */
  private static void assertBadRequest(Response response) {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
  }

  /** Itens da página como o JSON os devolveu. */
  private static List<Map<String, Object>> itemsOf(Response response) {
    return response.jsonPath().getList("items");
  }

  /** Item da página pelo nome; falha quando o nome não está na página. */
  private static Map<String, Object> itemNamed(
      List<Map<String, Object>> items, String expectedName) {
    return items.stream()
        .filter(item -> expectedName.equals(item.get("name")))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "item %s não está na página: %s".formatted(expectedName, items)));
  }

  /**
   * Valor numérico do JSON como BigDecimal: o parser pode devolver Integer, Double ou BigDecimal.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no corpo da resposta").isNotNull();
    return new BigDecimal(value.toString());
  }

  /** Nome de produto com o sufixo da classe, para a limpeza no fim do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
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

  private static void delete(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }
}
