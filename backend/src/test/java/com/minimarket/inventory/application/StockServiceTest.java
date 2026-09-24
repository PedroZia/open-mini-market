package com.minimarket.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ScaleEmbeddedField;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link StockService}, sem Quarkus e sem banco: as portas são dublês escritos à
 * mão e o relógio é fixo, para os movimentos serem conferidos campo a campo e a ordem dos locks ser
 * observável. O dublê de saldo mantém a linha em memória, imitando o insert sob demanda, o lock e a
 * gravação — a aplicação de verdade contra o PostgreSQL (transação, {@code FOR UPDATE} e rollback
 * do movimento recusado) é coberta pelo {@code StockServiceIntegrationTest}.
 */
class StockServiceTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID PRODUCT_A = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID PRODUCT_B = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final Instant NOW = Instant.parse("2026-09-24T14:00:00Z");

  private final FakeProductStockStore stockStore = new FakeProductStockStore();
  private final FakeStockMovementStore movementStore = new FakeStockMovementStore();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();

  private StockService service;

  @BeforeEach
  void setUp() {
    service = new StockService();
    service.productStockStore = stockStore;
    service.stockMovementStore = movementStore;
    service.storeLookup = storeLookup;
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service.defaultStoreCode = "MATRIZ";
  }

  @Test
  @DisplayName(
      "entrada aplica o delta sob lock e grava o movimento com custo, referência e balance_after")
  void appliesInboundMovement() {
    UUID referenceId = UUID.randomUUID();

    AppliedStockMovement result =
        service.applyMovement(
            new ApplyStockMovementCommand(
                PRODUCT_A,
                StockMovementType.PURCHASE_IN,
                new BigDecimal("5.000"),
                new BigDecimal("9.90"),
                "PURCHASE_RECEIPT",
                referenceId,
                "entrada de mercadoria",
                OPERATOR_ID));

    assertThat(result.movementId()).as("o id vem do insert do ledger").isNotNull();
    assertThat(result.productId()).isEqualTo(PRODUCT_A);
    assertThat(result.balanceBefore()).isEqualByComparingTo("0");
    assertThat(result.balanceAfter()).isEqualByComparingTo("5.000");

    assertThat(stockStore.events)
        .as("insert sob demanda antes do lock; o saldo só é gravado depois de travar")
        .containsExactly(
            "insertIfAbsent:" + PRODUCT_A,
            "lock:" + PRODUCT_A,
            "updateQuantity:" + stockStore.stockId(PRODUCT_A));

    NewStockMovement movement = movementStore.movements.getFirst();
    assertThat(movement.storeId())
        .as("a loja vem da configuração, não do comando")
        .isEqualTo(STORE_ID);
    assertThat(movement.productId()).isEqualTo(PRODUCT_A);
    assertThat(movement.type()).isEqualTo(StockMovementType.PURCHASE_IN);
    assertThat(movement.quantityDelta()).isEqualByComparingTo("5.000");
    assertThat(movement.balanceAfter()).isEqualByComparingTo("5.000");
    assertThat(movement.unitCost()).isEqualByComparingTo("9.90");
    assertThat(movement.referenceType()).isEqualTo("PURCHASE_RECEIPT");
    assertThat(movement.referenceId()).isEqualTo(referenceId);
    assertThat(movement.reason()).isEqualTo("entrada de mercadoria");
    assertThat(movement.createdByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(movement.createdAt()).as("o instante vem do relógio do caso de uso").isEqualTo(NOW);

    assertThat(stockStore.quantityOf(PRODUCT_A))
        .as("o saldo materializado acompanha o balance_after")
        .isEqualByComparingTo("5.000");
    assertThat(stockStore.updates).hasSize(1);
    assertThat(stockStore.updates.getFirst())
        .as("a gravação do saldo é a do balance_after do movimento")
        .isEqualByComparingTo("5.000");
  }

  @Test
  @DisplayName("saída desconta do saldo e grava o movimento negativo com o balance_after")
  void appliesOutboundMovement() {
    stockStore.seed(PRODUCT_A, "10.000");

    AppliedStockMovement result =
        service.applyMovement(command(PRODUCT_A, StockMovementType.SALE_OUT, "-3.000"));

    assertThat(result.balanceBefore()).isEqualByComparingTo("10.000");
    assertThat(result.balanceAfter()).isEqualByComparingTo("7.000");
    assertThat(movementStore.movements.getFirst().quantityDelta()).isEqualByComparingTo("-3.000");
    assertThat(movementStore.movements.getFirst().balanceAfter()).isEqualByComparingTo("7.000");
    assertThat(stockStore.quantityOf(PRODUCT_A)).isEqualByComparingTo("7.000");
  }

  @Test
  @DisplayName(
      "saldo insuficiente com loja sem saldo negativo: 422 INSUFFICIENT_STOCK e nada gravado")
  void rejectsMovementThatWouldGoNegativeWhenStoreForbidsIt() {
    storeLookup.allowNegativeStock = false;
    stockStore.seed(PRODUCT_A, "2.000");

    assertThatThrownBy(
            () -> service.applyMovement(command(PRODUCT_A, StockMovementType.LOSS, "-5.000")))
        .as("BR-09: a loja não permite saldo negativo")
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
              assertThat(error).hasMessageContaining(PRODUCT_A.toString());
            });

    assertThat(stockStore.updates).as("o saldo bloqueado não é gravado").isEmpty();
    assertThat(movementStore.movements)
        .as("o movimento recusado não vira linha no ledger")
        .isEmpty();
    assertThat(stockStore.quantityOf(PRODUCT_A))
        .as("o saldo continua o de antes")
        .isEqualByComparingTo("2.000");
  }

  @Test
  @DisplayName(
      "saldo insuficiente com a loja permitindo negativo: saldo fica negativo e é registrado")
  void allowsNegativeBalanceWhenStoreFlagIsOn() {
    stockStore.seed(PRODUCT_A, "2.000");

    AppliedStockMovement result =
        service.applyMovement(command(PRODUCT_A, StockMovementType.LOSS, "-5.000"));

    assertThat(result.balanceBefore()).isEqualByComparingTo("2.000");
    assertThat(result.balanceAfter()).isEqualByComparingTo("-3.000");
    assertThat(movementStore.movements.getFirst().balanceAfter())
        .as("BR-09: com a flag ligada o saldo negativo é registrado, não bloqueado")
        .isEqualByComparingTo("-3.000");
    assertThat(stockStore.quantityOf(PRODUCT_A)).isEqualByComparingTo("-3.000");
  }

  @Test
  @DisplayName(
      "lote aplica na ordem de product_id e usa um único instante para todos os movimentos")
  void appliesBatchInProductIdOrderWithSingleInstant() {
    List<AppliedStockMovement> applied =
        service.applyMovements(
            List.of(
                command(PRODUCT_B, StockMovementType.INITIAL, "2.000"),
                command(PRODUCT_A, StockMovementType.INITIAL, "1.000")));

    assertThat(stockStore.lockedProductIds)
        .as("§8: a ordem total por product_id evita deadlock entre lotes com ordens opostas")
        .containsExactly(PRODUCT_A, PRODUCT_B);
    assertThat(applied)
        .extracting(AppliedStockMovement::productId)
        .as("o resultado sai na ordem em que foi aplicado")
        .containsExactly(PRODUCT_A, PRODUCT_B);
    assertThat(applied.getFirst().balanceAfter()).isEqualByComparingTo("1.000");
    assertThat(applied.getLast().balanceAfter()).isEqualByComparingTo("2.000");
    assertThat(movementStore.movements)
        .extracting(NewStockMovement::createdAt)
        .as("a chamada usa um único clock.instant()")
        .containsExactly(NOW, NOW);
  }

  @Test
  @DisplayName("lista vazia devolve lista vazia sem tocar nas portas")
  void returnsEmptyListWithoutTouchingPorts() {
    assertThat(service.applyMovements(List.of())).isEmpty();
    assertThat(stockStore.events).isEmpty();
    assertThat(movementStore.movements).isEmpty();
  }

  @Test
  @DisplayName("loja configurada ausente é IllegalStateException sem gravar nada")
  void failsWhenConfiguredStoreIsMissing() {
    storeLookup.missing = true;

    assertThatThrownBy(
            () -> service.applyMovement(command(PRODUCT_A, StockMovementType.INITIAL, "1.000")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MATRIZ");

    assertThat(stockStore.events).isEmpty();
    assertThat(movementStore.movements).isEmpty();
  }

  /** Comando do cenário: sem custo nem referência, com o operador fixo. */
  private static ApplyStockMovementCommand command(
      UUID productId, StockMovementType type, String quantityDelta) {
    return new ApplyStockMovementCommand(
        productId, type, new BigDecimal(quantityDelta), null, null, null, null, OPERATOR_ID);
  }

  /** Saldo do produto como o adaptador o projetaria: uma linha por par (loja, produto). */
  private static ProductStockSummary summary(UUID productId, String quantity) {
    return new ProductStockSummary(
        UUID.randomUUID(), STORE_ID, productId, new BigDecimal(quantity), NOW, 0L);
  }

  /**
   * Dublê de {@link ProductStockStore}: guarda a linha de saldo em memória, registra a ordem das
   * chamadas (insert → lock → update) e aplica a gravação, como o banco faria sob o lock.
   */
  private static final class FakeProductStockStore implements ProductStockStore {

    private final Map<UUID, ProductStockSummary> stocks = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final List<UUID> lockedProductIds = new ArrayList<>();
    private final List<BigDecimal> updates = new ArrayList<>();

    /** Saldo já existente do produto, como se um movimento anterior o tivesse criado. */
    void seed(UUID productId, String quantity) {
      stocks.put(productId, summary(productId, quantity));
    }

    BigDecimal quantityOf(UUID productId) {
      return stocks.get(productId).quantity();
    }

    UUID stockId(UUID productId) {
      return stocks.get(productId).id();
    }

    @Override
    public Optional<ProductStockSummary> findByProduct(UUID storeId, UUID productId) {
      throw new UnsupportedOperationException("findByProduct não é usado pelo StockService");
    }

    @Override
    public Optional<ProductStockSummary> lockByProduct(UUID storeId, UUID productId) {
      events.add("lock:" + productId);
      lockedProductIds.add(productId);
      return Optional.ofNullable(stocks.get(productId));
    }

    @Override
    public void insertIfAbsent(UUID storeId, UUID productId) {
      events.add("insertIfAbsent:" + productId);
      stocks.computeIfAbsent(productId, id -> summary(id, "0"));
    }

    @Override
    public void updateQuantity(UUID id, BigDecimal newQuantity) {
      events.add("updateQuantity:" + id);
      updates.add(newQuantity);
      stocks.replaceAll(
          (productId, stock) ->
              stock.id().equals(id)
                  ? new ProductStockSummary(
                      stock.id(),
                      stock.storeId(),
                      stock.productId(),
                      newQuantity,
                      NOW,
                      stock.version() + 1)
                  : stock);
    }
  }

  /** Dublê de {@link StockMovementStore}: guarda o movimento e devolve um id determinístico. */
  private static final class FakeStockMovementStore implements StockMovementStore {

    private final List<NewStockMovement> movements = new ArrayList<>();
    private int nextId;

    @Override
    public UUID insert(NewStockMovement movement) {
      movements.add(movement);
      nextId++;
      return UUID.fromString("0199a2b3-0000-7000-8000-%012d".formatted(nextId));
    }

    @Override
    public List<StockMovementSummary> listByProduct(UUID productId, int limit) {
      throw new UnsupportedOperationException("listByProduct não é usado pelo StockService");
    }

    @Override
    public Map<StockMovementType, BigDecimal> sumByType(UUID productId) {
      throw new UnsupportedOperationException("sumByType não é usado pelo StockService");
    }
  }

  /** Dublê de {@link StoreLookup}: a loja configurada, com a flag de saldo negativo ajustável. */
  private static final class FakeStoreLookup implements StoreLookup {

    private boolean allowNegativeStock = true;
    private boolean missing;

    @Override
    public Optional<Store> findByCode(String code) {
      if (missing) {
        return Optional.empty();
      }
      return Optional.of(
          new Store(
              STORE_ID,
              code,
              "Matriz",
              allowNegativeStock,
              null,
              "2",
              5,
              ScaleEmbeddedField.WEIGHT,
              3));
    }

    @Override
    public Optional<Store> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado pelo StockService");
    }
  }
}
