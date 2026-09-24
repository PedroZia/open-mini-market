package com.minimarket.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ProductStockSummary;
import com.minimarket.shared.application.StoreLookup;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link ProductStockRepository} contra PostgreSQL real (Dev Services). Cada teste
 * roda em transação revertida ao final ({@code @TestTransaction}), então nada do que é criado fica
 * no banco — importante porque as FKs de {@code product_stocks} são {@code on delete restrict}.
 *
 * <p>O produto sai da porta {@link ProductStore} e o id da loja da porta {@link StoreLookup}: o
 * teste não importa infrastructure de outro módulo.
 */
@QuarkusTest
class ProductStockRepositoryTest extends IntegrationTestBase {

  @Inject ProductStockRepository stockRepository;

  @Inject ProductStore productStore;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @TestTransaction
  @DisplayName("insertIfAbsent cria o saldo zerado e a segunda chamada não cria outra linha")
  void insertsAbsentStockRowOnlyOnce() {
    UUID productId = newProduct("estoque.repo.saldo");

    stockRepository.insertIfAbsent(storeId(), productId);
    flushAndClear();

    ProductStockSummary created =
        stockRepository
            .findByProduct(storeId(), productId)
            .orElseThrow(() -> new AssertionError("saldo criado sob demanda não encontrado"));
    assertThat(created.id().version()).as("UUIDv7").isEqualTo(7);
    assertThat(created.storeId()).isEqualTo(storeId());
    assertThat(created.productId()).isEqualTo(productId);
    assertThat(created.quantity()).as("o saldo nasce zerado").isEqualByComparingTo("0");
    assertThat(created.updatedAt()).isNotNull();
    assertThat(created.version()).isZero();

    stockRepository.insertIfAbsent(storeId(), productId);
    flushAndClear();

    assertThat(stockRows(productId)).as("a segunda chamada não cria outra linha").isEqualTo(1);
    assertThat(stockRepository.findByProduct(storeId(), productId).orElseThrow().id())
        .as("a linha do primeiro insert continuou a mesma")
        .isEqualTo(created.id());
  }

  @Test
  @TestTransaction
  @DisplayName("findByProduct busca pelo par loja/produto e devolve vazio sem linha")
  void findsByStoreAndProductOnly() {
    UUID productId = newProduct("estoque.repo.busca");
    stockRepository.insertIfAbsent(storeId(), productId);
    flushAndClear();

    assertThat(stockRepository.findByProduct(storeId(), productId)).isPresent();
    assertThat(stockRepository.findByProduct(storeId(), UUID.randomUUID()))
        .as("produto sem movimento não tem saldo")
        .isEmpty();
    assertThat(stockRepository.findByProduct(UUID.randomUUID(), productId))
        .as("o saldo é do par (loja, produto)")
        .isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("updateQuantity grava o saldo e avança version e updated_at")
  void updatesQuantityWithVersionAndTimestamp() {
    UUID productId = newProduct("estoque.repo.ajuste");
    stockRepository.insertIfAbsent(storeId(), productId);
    flushAndClear();
    UUID stockId = stockRepository.findByProduct(storeId(), productId).orElseThrow().id();
    // Envelhece o updated_at: o avanço do @PreUpdate fica observável no relógio do teste.
    entityManager
        .createNativeQuery(
            "update product_stocks"
                + " set updated_at = timestamp with time zone '2020-01-01 00:00:00+00'"
                + " where id = :id")
        .setParameter("id", stockId)
        .executeUpdate();
    flushAndClear();

    stockRepository.updateQuantity(stockId, new BigDecimal("12.500"));
    flushAndClear();

    ProductStockSummary updated = stockRepository.findByProduct(storeId(), productId).orElseThrow();
    assertThat(updated.id()).isEqualTo(stockId);
    assertThat(updated.quantity()).isEqualByComparingTo("12.500");
    assertThat(updated.version()).as("o @Version avança a cada gravação").isEqualTo(1);
    assertThat(updated.updatedAt())
        .as("o @PreUpdate repõe o instante")
        .isAfter(Instant.parse("2020-01-01T00:00:00Z"));
  }

  /** Linhas de saldo do produto: prova que o segundo insertIfAbsent foi no-op. */
  private long stockRows(UUID productId) {
    return entityManager
        .createQuery(
            "select count(s) from ProductStockEntity s where s.storeId = :storeId"
                + " and s.productId = :productId",
            Long.class)
        .setParameter("storeId", storeId())
        .setParameter("productId", productId)
        .getSingleResult();
  }

  /** Produto vivo da loja, pela porta do catálogo. */
  private UUID newProduct(String name) {
    return productStore.insert(
        new NewProduct(storeId(), name, null, null, null, "UN", new BigDecimal("9.90"), null));
  }

  /** Limpa o contexto: o que o teste lê depois vem do banco, não da cópia gerenciada. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
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
