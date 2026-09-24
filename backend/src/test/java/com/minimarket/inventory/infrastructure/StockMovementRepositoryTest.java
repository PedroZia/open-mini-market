package com.minimarket.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.NewStockMovement;
import com.minimarket.inventory.application.StockMovementSummary;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link StockMovementRepository} contra PostgreSQL real (Dev Services). Cada teste
 * roda em transação revertida ao final ({@code @TestTransaction}), então nada do que é criado fica
 * no banco — importante porque as FKs do ledger são {@code on delete restrict}.
 *
 * <p>As fixtures saem das portas de {@code catalog}, {@code users} e {@code shared}: o teste não
 * importa infrastructure de outro módulo.
 */
@QuarkusTest
class StockMovementRepositoryTest extends IntegrationTestBase {

  @Inject StockMovementRepository movementRepository;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @TestTransaction
  @DisplayName("insert grava todos os campos do movimento com id UUIDv7")
  void insertsAllFields() {
    UUID productId = newProduct("estoque.repo.movimento");
    UUID userId = newUser("estoque.repo.movimento");
    UUID referenceId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");

    UUID id =
        createMovement(
            new NewStockMovement(
                storeId(),
                productId,
                StockMovementType.PURCHASE_IN,
                new BigDecimal("5.000"),
                new BigDecimal("5.000"),
                new BigDecimal("9.90"),
                "PURCHASE_RECEIPT",
                referenceId,
                "entrada de mercadoria",
                userId,
                createdAt));

    assertThat(id.version()).as("UUIDv7").isEqualTo(7);
    StockMovementEntity stored = entityManager.find(StockMovementEntity.class, id);
    assertThat(stored.getStoreId()).isEqualTo(storeId());
    assertThat(stored.getProductId()).isEqualTo(productId);
    assertThat(stored.getMovementType()).isEqualTo(StockMovementType.PURCHASE_IN);
    assertThat(stored.getQuantityDelta()).isEqualByComparingTo("5.000");
    assertThat(stored.getBalanceAfter()).isEqualByComparingTo("5.000");
    assertThat(stored.getUnitCost()).isEqualByComparingTo("9.90");
    assertThat(stored.getReferenceType()).isEqualTo("PURCHASE_RECEIPT");
    assertThat(stored.getReferenceId()).isEqualTo(referenceId);
    assertThat(stored.getReason()).isEqualTo("entrada de mercadoria");
    assertThat(stored.getCreatedByUserId()).isEqualTo(userId);
    assertThat(stored.getCreatedAt())
        .as("o instante vem do caso de uso, não do now() do JPA")
        .isEqualTo(createdAt);
  }

  @Test
  @TestTransaction
  @DisplayName("listByProduct devolve do mais recente para o mais antigo e respeita o limite")
  void listsByProductNewestFirst() {
    UUID productId = newProduct("estoque.repo.historico");
    UUID otherProductId = newProduct("estoque.repo.historico.outro");
    UUID userId = newUser("estoque.repo.historico");
    Instant oldest = Instant.parse("2026-01-01T10:00:00Z");
    Instant middle = Instant.parse("2026-01-01T11:00:00Z");
    Instant newest = Instant.parse("2026-01-01T12:00:00Z");
    createMovement(
        movement(productId, userId, StockMovementType.INITIAL, new BigDecimal("1.000"), oldest));
    createMovement(
        movement(productId, userId, StockMovementType.INITIAL, new BigDecimal("2.000"), middle));
    createMovement(
        movement(productId, userId, StockMovementType.INITIAL, new BigDecimal("3.000"), newest));
    createMovement(
        movement(
            otherProductId, userId, StockMovementType.INITIAL, new BigDecimal("4.000"), newest));

    assertThat(movementRepository.listByProduct(productId, 2))
        .as("o limite corta o mais antigo")
        .extracting(StockMovementSummary::createdAt)
        .containsExactly(newest, middle);
    assertThat(movementRepository.listByProduct(productId, 10))
        .extracting(StockMovementSummary::createdAt)
        .containsExactly(newest, middle, oldest);
    assertThat(movementRepository.listByProduct(otherProductId, 10))
        .as("o histórico é por produto")
        .hasSize(1);
    assertThat(movementRepository.listByProduct(UUID.randomUUID(), 10)).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("sumByType soma os deltas assinados por tipo e omite o tipo sem movimento")
  void sumsSignedDeltasByType() {
    UUID productId = newProduct("estoque.repo.soma");
    UUID userId = newUser("estoque.repo.soma");
    Instant base = Instant.parse("2026-01-01T10:00:00Z");
    createMovement(
        movement(productId, userId, StockMovementType.PURCHASE_IN, new BigDecimal("5.000"), base));
    createMovement(
        movement(
            productId,
            userId,
            StockMovementType.PURCHASE_IN,
            new BigDecimal("3.000"),
            base.plusSeconds(1)));
    createMovement(
        movement(
            productId,
            userId,
            StockMovementType.LOSS,
            new BigDecimal("-1.000"),
            base.plusSeconds(2)));
    createMovement(
        movement(
            productId,
            userId,
            StockMovementType.SALE_OUT,
            new BigDecimal("-4.000"),
            base.plusSeconds(3)));

    Map<StockMovementType, BigDecimal> totals = movementRepository.sumByType(productId);
    assertThat(totals)
        .as("tipo sem movimento fica fora do mapa")
        .containsOnlyKeys(
            StockMovementType.PURCHASE_IN, StockMovementType.SALE_OUT, StockMovementType.LOSS);
    assertThat(totals.get(StockMovementType.PURCHASE_IN))
        .as("5.000 + 3.000")
        .isEqualByComparingTo("8.000");
    assertThat(totals.get(StockMovementType.SALE_OUT))
        .as("a saída entra negativa")
        .isEqualByComparingTo("-4.000");
    assertThat(totals.get(StockMovementType.LOSS)).isEqualByComparingTo("-1.000");
    assertThat(movementRepository.sumByType(UUID.randomUUID())).isEmpty();
  }

  /** Movimento com o saldo já aplicado: o suficiente para ordenação, histórico e soma. */
  private NewStockMovement movement(
      UUID productId, UUID userId, StockMovementType type, BigDecimal delta, Instant at) {
    return new NewStockMovement(
        storeId(), productId, type, delta, delta, null, null, null, null, userId, at);
  }

  /** Insere o movimento pelo repositório e limpa o contexto. */
  private UUID createMovement(NewStockMovement movement) {
    UUID id = movementRepository.insert(movement);
    entityManager.flush();
    entityManager.clear();
    return id;
  }

  /** Produto vivo da loja, pela porta do catálogo. */
  private UUID newProduct(String name) {
    return productStore.insert(
        new NewProduct(storeId(), name, null, null, null, "UN", new BigDecimal("9.90"), null));
  }

  private UUID newUser(String username) {
    return userStore.insert(new NewUser(username, "Operador de estoque", "hash", "ACTIVE"));
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
