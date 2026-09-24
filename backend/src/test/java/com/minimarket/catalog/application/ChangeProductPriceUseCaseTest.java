package com.minimarket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link ChangeProductPriceUseCase}, sem Quarkus e sem banco: a porta é um dublê
 * escrito à mão. O gravador de auditoria também é dublê — o evento é conferido como o caso de uso o
 * entregou, e a gravação de verdade contra o PostgreSQL fica com o teste de API do passo 411.
 */
class ChangeProductPriceUseCaseTest {

  private static final UUID PRODUCT_ID = UUID.randomUUID();
  private static final UUID STORE_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");
  private static final BigDecimal CURRENT_PRICE = new BigDecimal("24.90");

  private final FakeProductStore productStore = new FakeProductStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private ChangeProductPriceUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new ChangeProductPriceUseCase();
    useCase.productStore = productStore;
    useCase.auditRecorder = auditRecorder;
  }

  @Test
  @DisplayName(
      "grava o preço normalizado em escala 2 (HALF_UP) e devolve o produto com a versão nova")
  void changesPrice() {
    ProductSummary updated = useCase.execute(command(new BigDecimal("19.999"), "promoção do dia"));

    assertThat(productStore.updatePriceCall)
        .as("o caso de uso grava o preço já normalizado")
        .isEqualTo(new PriceUpdate(PRODUCT_ID, new BigDecimal("20.00")));
    assertThat(updated)
        .satisfies(
            product -> {
              assertThat(product.price()).isEqualByComparingTo("20.00");
              assertThat(product.version()).as("lock otimista avançou").isEqualTo(1);
              assertThat(product.name()).as("o preço não mexe no cadastro").isEqualTo("Arroz 5kg");
              assertThat(product.barcode()).isEqualTo("7891000000017");
              assertThat(product.active()).isTrue();
              assertThat(product.deletedAt()).isNull();
            });
    assertThat(auditRecorder.recorded).as("a alteração gera um evento").hasSize(1);
  }

  @Test
  @DisplayName("preço igual ao atual é no-op: devolve o produto como está, sem gravar nem auditar")
  void keepsPriceWhenUnchanged() {
    // 24.9 e 24.90 são o mesmo preço: a normalização em escala 2 não pode inventar um reajuste.
    ProductSummary unchanged = useCase.execute(command(new BigDecimal("24.9"), "sem efeito"));

    assertThat(unchanged.price()).isEqualByComparingTo("24.90");
    assertThat(productStore.updatePriceCall).as("o no-op não toca na linha").isNull();
    assertThat(auditRecorder.recorded).as("o no-op não inventa evento").isEmpty();
  }

  @Test
  @DisplayName("preço nulo ou negativo é erro de validação sem gravar")
  void rejectsInvalidPrice() {
    for (BigDecimal price : Arrays.asList(null, new BigDecimal("-0.01"))) {
      assertThatThrownBy(() -> useCase.execute(command(price, "promoção do dia")))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(productStore.updatePriceCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("motivo ausente ou em branco é erro de validação sem gravar")
  void rejectsBlankReason() {
    for (String reason : Arrays.asList(null, "", "   ")) {
      assertThatThrownBy(() -> useCase.execute(command(new BigDecimal("19.99"), reason)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(productStore.updatePriceCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName(
      "produto inexistente, soft-deletado ou desativado conta como inexistente: 404 sem gravar")
  void rejectsProductOutOfCatalog() {
    for (ProductSummary out :
        Arrays.asList(
            product(CURRENT_PRICE, false, null),
            product(CURRENT_PRICE, true, Instant.parse("2026-01-03T00:00:00Z")),
            null)) {
      productStore.stored = out;

      assertThatThrownBy(() -> useCase.execute(command(new BigDecimal("19.99"), "promoção")))
          .isInstanceOfSatisfying(
              NotFoundException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));

      assertThat(productStore.updatePriceCall).isNull();
    }
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("a alteração grava PRODUCT_PRICE_CHANGED com o motivo e o antes/depois do preço")
  void recordsProductPriceChanged() {
    useCase.execute(command(new BigDecimal("19.99"), "promoção do dia"));

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("PRODUCT_PRICE_CHANGED");
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.entityId()).isEqualTo(PRODUCT_ID);
    assertThat(event.reason()).isEqualTo("promoção do dia");

    Map<String, Object> before = snapshot(event.details(), "before");
    assertThat(before).as("§7.2: só o campo que a operação muda").containsOnlyKeys("price");
    assertThat((BigDecimal) before.get("price")).isEqualByComparingTo("24.90");

    Map<String, Object> after = snapshot(event.details(), "after");
    assertThat(after).containsOnlyKeys("price");
    assertThat((BigDecimal) after.get("price")).isEqualByComparingTo("19.99");
  }

  private static ChangeProductPriceCommand command(BigDecimal price, String reason) {
    return new ChangeProductPriceCommand(PRODUCT_ID, price, reason);
  }

  /** Produto do "banco" do dublê; só o que cada teste varia entra por parâmetro. */
  private static ProductSummary product(BigDecimal price, boolean active, Instant deletedAt) {
    return new ProductSummary(
        PRODUCT_ID,
        STORE_ID,
        "7891000000017",
        "Arroz 5kg",
        "grão longo",
        null,
        "UN",
        price,
        null,
        active,
        CREATED_AT,
        CREATED_AT,
        deletedAt,
        0);
  }

  /** Antes/depois aninhado em {@code details}, como o caso de uso o entregou. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> snapshot(Map<String, Object> details, String key) {
    return (Map<String, Object>) details.get(key);
  }

  /**
   * Dublê de {@link ProductStore}: guarda um produto só — o "banco" que o caso de uso lê e
   * reescreve — e a última chamada de {@code updatePrice} para conferência.
   */
  private static final class FakeProductStore implements ProductStore {

    private ProductSummary stored = product(CURRENT_PRICE, true, null);
    private PriceUpdate updatePriceCall;

    @Override
    public Optional<ProductSummary> findById(UUID id) {
      return Optional.ofNullable(stored).filter(product -> product.id().equals(id));
    }

    @Override
    public Optional<ProductSummary> updatePrice(UUID id, BigDecimal price) {
      updatePriceCall = new PriceUpdate(id, price);
      if (stored == null || !stored.id().equals(id)) {
        return Optional.empty();
      }
      stored = withPrice(stored, price);
      return Optional.of(stored);
    }

    @Override
    public void updateCostPrice(UUID id, BigDecimal costPrice) {
      throw new UnsupportedOperationException("updateCostPrice não é usado por ChangeProductPrice");
    }

    /** O updatePrice do "banco" do dublê: troca o preço e avança a versão. */
    private static ProductSummary withPrice(ProductSummary product, BigDecimal price) {
      return new ProductSummary(
          product.id(),
          product.storeId(),
          product.barcode(),
          product.name(),
          product.description(),
          product.categoryId(),
          product.unit(),
          price,
          product.minQuantity(),
          product.active(),
          product.createdAt(),
          product.updatedAt(),
          product.deletedAt(),
          product.version() + 1);
    }

    @Override
    public UUID insert(NewProduct product) {
      throw new UnsupportedOperationException("insert não é usado por ChangeProductPrice");
    }

    @Override
    public Optional<ProductSummary> findByBarcode(String barcode) {
      throw new UnsupportedOperationException("findByBarcode não é usado por ChangeProductPrice");
    }

    @Override
    public Optional<ProductSummary> findByInternalCode(String internalCode) {
      throw new UnsupportedOperationException(
          "findByInternalCode não é usado por ChangeProductPrice");
    }

    @Override
    public List<ProductSummary> search(
        String search,
        UUID categoryId,
        Boolean active,
        ProductSort sort,
        boolean ascending,
        int page,
        int size) {
      throw new UnsupportedOperationException("search não é usado por ChangeProductPrice");
    }

    @Override
    public long count(String search, UUID categoryId, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por ChangeProductPrice");
    }

    @Override
    public Optional<ProductSummary> update(
        UUID id,
        String name,
        String internalCode,
        UUID categoryId,
        String unit,
        String description,
        BigDecimal minQuantity) {
      throw new UnsupportedOperationException("update não é usado por ChangeProductPrice");
    }

    @Override
    public void softDelete(UUID id) {
      throw new UnsupportedOperationException("softDelete não é usado por ChangeProductPrice");
    }

    @Override
    public boolean existsActiveBarcode(String barcode) {
      throw new UnsupportedOperationException(
          "existsActiveBarcode não é usado por ChangeProductPrice");
    }

    @Override
    public boolean existsActiveInternalCode(String internalCode) {
      throw new UnsupportedOperationException(
          "existsActiveInternalCode não é usado por ChangeProductPrice");
    }

    @Override
    public boolean existsActiveInternalCodeExceptId(String internalCode, UUID id) {
      throw new UnsupportedOperationException(
          "existsActiveInternalCodeExceptId não é usado por ChangeProductPrice");
    }

    @Override
    public Optional<ProductSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por ChangeProductPrice");
    }

    @Override
    public Optional<ProductSummary> enable(UUID id) {
      throw new UnsupportedOperationException("enable não é usado por ChangeProductPrice");
    }
  }

  /**
   * Dublê de {@link AuditRecorder}: guarda o que o caso de uso pediu para gravar, sem CDI e sem
   * banco. A subclasse só sobrescreve {@code record} — o caminho de verdade (contexto + INSERT) é
   * do passo 303 e tem teste próprio contra PostgreSQL.
   */
  private static final class FakeAuditRecorder extends AuditRecorder {

    private final List<Recorded> recorded = new ArrayList<>();

    @Override
    public void record(
        String action,
        String entityType,
        UUID entityId,
        String reason,
        Map<String, Object> details) {
      recorded.add(new Recorded(action, entityType, entityId, reason, details));
    }

    /** Único evento do cenário; o teste falha se o caso de uso gravou zero ou dois. */
    private Recorded only() {
      assertThat(recorded).as("eventos de auditoria do cenário").hasSize(1);
      return recorded.getFirst();
    }
  }

  /** Evento como o caso de uso o entregou ao gravador. */
  private record Recorded(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details) {}

  /** Chamada de updatePrice que o caso de uso fez à porta. */
  private record PriceUpdate(UUID id, BigDecimal price) {}
}
