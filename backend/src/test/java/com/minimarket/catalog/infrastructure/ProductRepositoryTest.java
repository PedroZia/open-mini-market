package com.minimarket.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.CategoryStore;
import com.minimarket.catalog.application.NewCategory;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductSort;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link ProductRepository} contra PostgreSQL real (Dev Services). Cada teste roda em
 * transação revertida ao final ({@code @TestTransaction}).
 *
 * <p>O id da loja MATRIZ vem da porta {@link StoreLookup}, com o código configurado: o teste não
 * importa infrastructure de outro módulo.
 */
@QuarkusTest
class ProductRepositoryTest extends IntegrationTestBase {

  @Inject ProductRepository productRepository;

  @Inject CategoryStore categoryStore;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @TestTransaction
  @DisplayName("insere com id UUIDv7, timestamps e version zerada, e devolve o produto pelo id")
  void insertsAndFindsById() {
    UUID categoryId = category("Bebidas");
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", categoryId, "UN", "1.000");

    assertThat(id.version()).as("UUIDv7").isEqualTo(7);
    assertThat(productRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.storeId()).isEqualTo(storeId());
              assertThat(found.barcode()).isEqualTo("7891000000017");
              assertThat(found.name()).isEqualTo("Arroz 5kg");
              assertThat(found.description()).isEqualTo("descrição de Arroz 5kg");
              assertThat(found.categoryId()).isEqualTo(categoryId);
              assertThat(found.unit()).isEqualTo("UN");
              assertThat(found.price()).isEqualByComparingTo("24.90");
              assertThat(found.minQuantity()).isEqualByComparingTo("1.000");
              assertThat(found.active()).isTrue();
              assertThat(found.createdAt()).isNotNull();
              assertThat(found.updatedAt()).isNotNull();
              assertThat(found.deletedAt()).isNull();
              assertThat(found.version()).isZero();
            });

    assertThat(productRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("findByBarcode devolve o produto vivo do código e vazio para código desconhecido")
  void findsByBarcode() {
    UUID arroz = insert("Arroz 5kg", "7891000000017", "24.90", null);
    UUID feijao = insert("Feijão 1kg", "7891000000024", "8.49", null);

    assertThat(productRepository.findByBarcode("7891000000024"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(feijao);
              assertThat(found.name()).isEqualTo("Feijão 1kg");
              assertThat(found.price()).isEqualByComparingTo("8.49");
            });
    assertThat(productRepository.findByBarcode("7891000000017"))
        .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(arroz));
    assertThat(productRepository.findByBarcode("0000000000000")).isEmpty();
    assertThat(productRepository.findByBarcode(null)).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "findByInternalCode devolve o produto vivo do código interno e ignora o soft-deletado")
  void findsByInternalCode() {
    UUID banana = insertWithInternalCode("Banana prata kg", "00042", "6.99");
    insertWithInternalCode("Tomate kg", "00077", "9.90");

    assertThat(productRepository.findByInternalCode("00042"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(banana);
              assertThat(found.internalCode()).isEqualTo("00042");
              assertThat(found.unit()).isEqualTo("KG");
            });
    assertThat(productRepository.findByInternalCode("00077")).isPresent();
    assertThat(productRepository.findByInternalCode("99999")).isEmpty();
    assertThat(productRepository.findByInternalCode(null)).isEmpty();

    productRepository.softDelete(banana);
    entityManager.flush();
    entityManager.clear();

    assertThat(productRepository.findByInternalCode("00042")).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("search casa trecho do nome sem diferenciar maiúsculas e sem termo devolve tudo")
  void searchesByNameIgnoringCase() {
    insert("Arroz Integral 1kg", "7891000000017", "12.90", null);
    insert("Feijão Carioca 1kg", "7891000000024", "8.49", null);
    insert("Café Torrado 500g", "7891000000031", "18.90", null);

    assertThat(names(search("arroz", null, null, ProductSort.NAME, true)))
        .containsExactly("Arroz Integral 1kg");
    assertThat(names(search("INTEGRAL", null, null, ProductSort.NAME, true)))
        .containsExactly("Arroz Integral 1kg");
    assertThat(names(search("aRrOz InTeGrAl", null, null, ProductSort.NAME, true)))
        .containsExactly("Arroz Integral 1kg");
    assertThat(names(search("  ", null, null, ProductSort.NAME, true))).hasSize(3);
    assertThat(names(search(null, null, null, ProductSort.NAME, true))).hasSize(3);
    assertThat(names(search("sabão", null, null, ProductSort.NAME, true))).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("search filtra por categoria e por status, e as combina com o termo")
  void filtersByCategoryAndStatus() {
    UUID bebidas = category("Bebidas");
    UUID limpeza = category("Limpeza");
    insert("Arroz 5kg", "7891000000017", "24.90", bebidas);
    insert("Feijão 1kg", "7891000000024", "8.49", bebidas);
    UUID detergente = insert("Detergente 500ml", "7891000000031", "3.79", limpeza);
    setActive(detergente, false);

    assertThat(names(search(null, bebidas, null, ProductSort.NAME, true)))
        .containsExactly("Arroz 5kg", "Feijão 1kg");
    assertThat(names(search(null, limpeza, null, ProductSort.NAME, true)))
        .containsExactly("Detergente 500ml");
    assertThat(names(search(null, null, true, ProductSort.NAME, true)))
        .containsExactly("Arroz 5kg", "Feijão 1kg");
    assertThat(names(search(null, null, false, ProductSort.NAME, true)))
        .containsExactly("Detergente 500ml");
    assertThat(names(search(null, null, null, ProductSort.NAME, true))).hasSize(3);
    assertThat(names(search("arroz", limpeza, null, ProductSort.NAME, true))).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("search pagina por page/size sem repetir item e devolve vazio além da última página")
  void paginates() {
    insert("Arroz 5kg", null, "24.90", null);
    insert("Banana prata kg", null, "6.99", null);
    insert("Café 500g", null, "18.90", null);

    List<ProductSummary> firstPage =
        productRepository.search(null, null, null, ProductSort.NAME, true, 0, 2);
    List<ProductSummary> secondPage =
        productRepository.search(null, null, null, ProductSort.NAME, true, 1, 2);

    assertThat(names(firstPage)).containsExactly("Arroz 5kg", "Banana prata kg");
    assertThat(names(secondPage)).containsExactly("Café 500g");
    assertThat(secondPage)
        .extracting(ProductSummary::id)
        .doesNotContainAnyElementsOf(firstPage.stream().map(ProductSummary::id).toList());
    assertThat(productRepository.search(null, null, null, ProductSort.NAME, true, 5, 2)).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("count conta com os mesmos filtros da busca, sem ordenação nem paginação")
  void countsWithTheSameFiltersAsSearch() {
    UUID bebidas = category("Bebidas");
    UUID limpeza = category("Limpeza");
    insert("Arroz 5kg", "7891000000017", "24.90", bebidas);
    insert("Feijão 1kg", "7891000000024", "8.49", bebidas);
    UUID detergente = insert("Detergente 500ml", "7891000000031", "3.79", limpeza);
    setActive(detergente, false);

    assertThat(productRepository.count(null, bebidas, null)).isEqualTo(2);
    assertThat(productRepository.count("arroz", bebidas, null)).isEqualTo(1);
    assertThat(productRepository.count(null, bebidas, true)).isEqualTo(2);
    assertThat(productRepository.count(null, bebidas, false)).isZero();
    assertThat(productRepository.count(null, limpeza, false)).isEqualTo(1);
    assertThat(productRepository.count("sabão", bebidas, null)).isZero();

    // A contagem não pode divergir da lista para os mesmos filtros: é o totalItems da página.
    assertThat(productRepository.count("arroz", bebidas, null))
        .isEqualTo(search("arroz", bebidas, null, ProductSort.NAME, true).size());

    // Soft-deletado nunca conta, como também não aparece na busca.
    productRepository.softDelete(detergente);
    entityManager.flush();
    entityManager.clear();
    assertThat(productRepository.count(null, limpeza, null)).isZero();
  }

  @Test
  @TestTransaction
  @DisplayName("search ordena por nome, preço e criação, nas duas direções")
  void ordersByPriceNameAndCreation() {
    UUID arroz = insert("Arroz 5kg", null, "24.90", null);
    UUID banana = insert("Banana prata kg", null, "6.99", null);
    UUID cafe = insert("Café 500g", null, "18.90", null);

    assertThat(names(search(null, null, null, ProductSort.NAME, true)))
        .containsExactly("Arroz 5kg", "Banana prata kg", "Café 500g");
    assertThat(names(search(null, null, null, ProductSort.NAME, false)))
        .containsExactly("Café 500g", "Banana prata kg", "Arroz 5kg");
    assertThat(names(search(null, null, null, ProductSort.PRICE, true)))
        .containsExactly("Banana prata kg", "Café 500g", "Arroz 5kg");
    assertThat(names(search(null, null, null, ProductSort.PRICE, false)))
        .containsExactly("Arroz 5kg", "Café 500g", "Banana prata kg");

    // O relógio da máquina empata em inserts seguidos do mesmo teste: os instantes vão fixos no
    // banco para a ordenação por criação ficar determinística.
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    setCreatedAt(arroz, base.plusSeconds(2));
    setCreatedAt(banana, base.plusSeconds(1));
    setCreatedAt(cafe, base);

    assertThat(names(search(null, null, null, ProductSort.CREATED_AT, true)))
        .containsExactly("Café 500g", "Banana prata kg", "Arroz 5kg");
    assertThat(names(search(null, null, null, ProductSort.CREATED_AT, false)))
        .containsExactly("Arroz 5kg", "Banana prata kg", "Café 500g");
  }

  @Test
  @TestTransaction
  @DisplayName(
      "update grava os campos da edição, avança version e updated_at e preserva preço e barcode")
  void updatesDetails() {
    UUID bebidas = category("Bebidas");
    UUID limpeza = category("Limpeza");
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", bebidas);
    Instant before = productRepository.findById(id).orElseThrow().updatedAt();

    Optional<ProductSummary> updated =
        productRepository.update(
            id, "Arroz Tipo 1 5kg", limpeza, "KG", "grão longo", new BigDecimal("2.500"));

    assertThat(updated)
        .hasValueSatisfying(
            found -> {
              assertThat(found.name()).isEqualTo("Arroz Tipo 1 5kg");
              assertThat(found.categoryId()).isEqualTo(limpeza);
              assertThat(found.unit()).isEqualTo("KG");
              assertThat(found.description()).isEqualTo("grão longo");
              assertThat(found.minQuantity()).isEqualByComparingTo("2.500");
              assertThat(found.barcode())
                  .as("barcode não muda por aqui")
                  .isEqualTo("7891000000017");
              assertThat(found.price()).as("preço não muda por aqui").isEqualByComparingTo("24.90");
              assertThat(found.active()).isTrue();
              assertThat(found.version()).as("lock otimista avançou").isEqualTo(1);
            });

    entityManager.clear();
    assertThat(productRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.name()).isEqualTo("Arroz Tipo 1 5kg");
              assertThat(found.updatedAt()).isAfterOrEqualTo(before);
            });
    // Id desconhecido é no-op, como nas outras escritas por id.
    assertThat(productRepository.update(UUID.randomUUID(), "Fantasma", null, "UN", null, null))
        .isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "softDelete grava deleted_at sem mexer em active e tira o produto da busca e do barcode")
  void softDeletes() {
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", null);
    insert("Feijão 1kg", "7891000000024", "8.49", null);

    productRepository.softDelete(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(productRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.deletedAt()).isNotNull();
              assertThat(found.active()).as("o active é regra do caso de uso").isTrue();
              assertThat(found.version()).isEqualTo(1);
            });
    assertThat(productRepository.findByBarcode("7891000000017")).isEmpty();
    assertThat(names(search(null, null, null, ProductSort.NAME, true)))
        .containsExactly("Feijão 1kg");
    assertThat(names(search("arroz", null, null, ProductSort.NAME, true))).isEmpty();
    assertThat(productRepository.update(id, "Arroz Novo", null, "UN", null, null)).isEmpty();
    // Id desconhecido é no-op, como nas outras escritas por id.
    productRepository.softDelete(UUID.randomUUID());
  }

  @Test
  @TestTransaction
  @DisplayName(
      "disable grava active=false com deleted_at, tira o produto da busca e do barcode e libera o código")
  void disables() {
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", null);
    insert("Feijão 1kg", "7891000000024", "8.49", null);

    assertThat(productRepository.disable(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.active()).isFalse();
              assertThat(found.deletedAt()).as("soft delete acompanha a desativação").isNotNull();
              assertThat(found.version()).as("lock otimista avançou").isEqualTo(1);
            });

    entityManager.clear();
    assertThat(productRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.active()).isFalse();
              assertThat(found.deletedAt()).isNotNull();
            });
    assertThat(productRepository.findByBarcode("7891000000017")).isEmpty();
    assertThat(names(search(null, null, null, ProductSort.NAME, true)))
        .containsExactly("Feijão 1kg");
    // Desativado de novo e id desconhecido: vazio, como nas outras escritas por id.
    assertThat(productRepository.disable(id)).isEmpty();
    assertThat(productRepository.disable(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "enable enxerga o desativado, volta o produto à busca e ao barcode e limpa o deleted_at")
  void enables() {
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", null);
    productRepository.disable(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(productRepository.enable(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.active()).isTrue();
              assertThat(found.deletedAt()).isNull();
              assertThat(found.version()).as("desativar e reativar, dois updates").isEqualTo(2);
            });

    entityManager.clear();
    assertThat(productRepository.findByBarcode("7891000000017"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(id);
              assertThat(found.active()).isTrue();
            });
    assertThat(names(search(null, null, null, ProductSort.NAME, true)))
        .containsExactly("Arroz 5kg");
    assertThat(productRepository.existsActiveBarcode("7891000000017")).isTrue();
    // Id desconhecido: vazio, como nas outras escritas por id.
    assertThat(productRepository.enable(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "enable de produto cujo barcode já foi tomado vira ConflictException(BARCODE_ALREADY_EXISTS)")
  void translatesBarcodeConflictOnEnable() {
    UUID antigo = insert("Arroz 5kg", "7891000000017", "24.90", null);
    productRepository.disable(antigo);
    entityManager.flush();
    entityManager.clear();
    insert("Arroz 1kg", "7891000000017", "6.90", null);

    assertThatThrownBy(() -> productRepository.enable(antigo))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.BARCODE_ALREADY_EXISTS));
  }

  @Test
  @TestTransaction
  @DisplayName("existsActiveBarcode ignora produto soft-deletado, código desconhecido e nulo")
  void checksActiveBarcode() {
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", null);
    insert("Arroz granel", null, "5.00", null);

    assertThat(productRepository.existsActiveBarcode("7891000000017")).isTrue();
    assertThat(productRepository.existsActiveBarcode("0000000000000")).isFalse();
    assertThat(productRepository.existsActiveBarcode(null)).isFalse();

    productRepository.softDelete(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(productRepository.existsActiveBarcode("7891000000017")).isFalse();
  }

  @Test
  @TestTransaction
  @DisplayName("produtos sem barcode convivem: o índice único parcial ignora os nulos")
  void allowsMultipleProductsWithoutBarcode() {
    UUID arroz = insert("Arroz granel", null, "5.00", null);
    UUID banana = insert("Banana granel", null, "6.99", null);

    assertThat(productRepository.findById(arroz))
        .hasValueSatisfying(found -> assertThat(found.barcode()).isNull());
    assertThat(productRepository.findById(banana))
        .hasValueSatisfying(found -> assertThat(found.barcode()).isNull());
  }

  @Test
  @TestTransaction
  @DisplayName("soft delete libera o barcode: o produto novo com o mesmo código é aceito")
  void allowsBarcodeFreedBySoftDelete() {
    UUID antigo = insert("Arroz 5kg", "7891000000017", "24.90", null);
    productRepository.softDelete(antigo);
    entityManager.flush();
    entityManager.clear();

    UUID novo = insert("Arroz 5kg", "7891000000017", "26.90", null);

    assertThat(productRepository.findByBarcode("7891000000017"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(novo);
              assertThat(found.price()).isEqualByComparingTo("26.90");
            });
    assertThat(productRepository.existsActiveBarcode("7891000000017")).isTrue();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "barcode duplicado entre produtos vivos vira ConflictException(BARCODE_ALREADY_EXISTS)")
  void translatesDuplicateBarcodeOnInsert() {
    insert("Arroz 5kg", "7891000000017", "24.90", null);

    assertThatThrownBy(() -> insert("Arroz 1kg", "7891000000017", "6.90", null))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.BARCODE_ALREADY_EXISTS));
  }

  @Test
  @TestTransaction
  @DisplayName("soft delete libera o código interno: o produto novo com o mesmo PLU é aceito")
  void allowsInternalCodeFreedBySoftDelete() {
    UUID antigo = insertWithInternalCode("Banana prata kg", "00042", "6.99");
    productRepository.softDelete(antigo);
    entityManager.flush();
    entityManager.clear();

    UUID novo = insertWithInternalCode("Banana nanica kg", "00042", "5.99");

    assertThat(productRepository.findByInternalCode("00042"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(novo);
              assertThat(found.name()).isEqualTo("Banana nanica kg");
            });
  }

  @Test
  @TestTransaction
  @DisplayName("código interno duplicado entre produtos vivos falha no índice único parcial")
  void translatesDuplicateInternalCodeOnInsert() {
    insertWithInternalCode("Banana prata kg", "00042", "6.99");

    assertThatThrownBy(() -> insertWithInternalCode("Banana nanica kg", "00042", "5.99"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @TestTransaction
  @DisplayName(
      "update de linha já alterada por outra transação vira ConflictException(CONCURRENT_MODIFICATION)")
  void translatesStaleVersionOnUpdate() {
    UUID id = insert("Arroz 5kg", "7891000000017", "24.90", null);
    // A entidade entra no contexto com a versão 0: é ela que o If-Match do cliente leu.
    assertThat(productRepository.findById(id)).isPresent();
    // Outra transação grava primeiro, direto no banco: o update com "where version = 0" não acha
    // mais a linha e o flush antecipado traduz o stale do Hibernate — o backstop do lock otimista.
    entityManager
        .createNativeQuery("update products set version = version + 1 where id = :id")
        .setParameter("id", id)
        .executeUpdate();

    assertThatThrownBy(() -> productRepository.update(id, "Arroz Novo", null, "UN", null, null))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
  }

  /** Insere pelo repositório e limpa o contexto: o que o teste lê depois vem do banco. */
  private UUID insert(
      String name, String barcode, String price, UUID categoryId, String unit, String minQuantity) {
    UUID id =
        productRepository.insert(
            new NewProduct(
                storeId(),
                name,
                barcode,
                "descrição de " + name,
                categoryId,
                unit,
                new BigDecimal(price),
                minQuantity == null ? null : new BigDecimal(minQuantity)));
    entityManager.clear();
    return id;
  }

  /** Insere com os defaults que o teste não varia: unidade UN e quantidade mínima nula. */
  private UUID insert(String name, String barcode, String price, UUID categoryId) {
    return insert(name, barcode, price, categoryId, "UN", null);
  }

  /**
   * Insere com código interno (etiqueta de balança, passo 1104b1) e unidade KG — o cadastro pela
   * API ainda não informa o campo, então o teste usa o {@link NewProduct} completo.
   */
  private UUID insertWithInternalCode(String name, String internalCode, String price) {
    UUID id =
        productRepository.insert(
            new NewProduct(
                storeId(),
                name,
                null,
                internalCode,
                "descrição de " + name,
                null,
                "KG",
                new BigDecimal(price),
                null));
    entityManager.clear();
    return id;
  }

  private UUID category(String name) {
    UUID id = categoryStore.insert(new NewCategory(storeId(), name, null, 0));
    entityManager.clear();
    return id;
  }

  /**
   * Marca {@code active} direto pelo mapeamento: o caso de uso de desativar é do passo 412 e o
   * filtro por status precisa de um produto não deletado com {@code active = false}.
   */
  private void setActive(UUID id, boolean active) {
    entityManager
        .createQuery("update ProductEntity p set p.active = :active where p.id = :id")
        .setParameter("active", active)
        .setParameter("id", id)
        .executeUpdate();
    entityManager.clear();
  }

  /**
   * Fixa {@code created_at} direto pelo mapeamento: o relógio da máquina empata em inserts seguidos
   * do mesmo teste, e a ordenação por criação precisa de instantes distintos.
   */
  private void setCreatedAt(UUID id, Instant createdAt) {
    entityManager
        .createQuery("update ProductEntity p set p.createdAt = :createdAt where p.id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", id)
        .executeUpdate();
    entityManager.clear();
  }

  private List<ProductSummary> search(
      String term, UUID categoryId, Boolean active, ProductSort sort, boolean ascending) {
    return productRepository.search(term, categoryId, active, sort, ascending, 0, 50);
  }

  private static List<String> names(List<ProductSummary> products) {
    return products.stream().map(ProductSummary::name).toList();
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          storeLookup
              .findByCode(defaultStoreCode)
              .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
              .id();
    }
    return storeId;
  }
}
