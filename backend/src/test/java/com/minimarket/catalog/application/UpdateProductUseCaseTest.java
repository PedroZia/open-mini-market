package com.minimarket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link UpdateProductUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão. O gravador de auditoria também é dublê — o evento é conferido como o caso de uso
 * o entregou, e a gravação de verdade contra o PostgreSQL fica com o teste de API do passo 410.
 */
class UpdateProductUseCaseTest {

  private static final UUID PRODUCT_ID = UUID.randomUUID();
  private static final UUID STORE_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

  private final FakeProductStore productStore = new FakeProductStore();
  private final FakeCategoryStore categoryStore = new FakeCategoryStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private UpdateProductUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new UpdateProductUseCase();
    useCase.productStore = productStore;
    useCase.categoryStore = categoryStore;
    useCase.auditRecorder = auditRecorder;
  }

  @Test
  @DisplayName("edita os cinco campos e devolve o produto relido com a versão nova")
  void updatesProduct() {
    UUID categoryId = categoryStore.existingCategory();
    BigDecimal minQuantity = new BigDecimal("2.500");

    ProductSummary updated =
        useCase.execute(
            command(0, "Arroz Tipo 1 5kg", categoryId, "KG", "grão longo tipo 1", minQuantity));

    assertThat(productStore.updateCall)
        .as("o caso de uso grava exatamente os campos da edição")
        .isEqualTo(
            new Update(
                PRODUCT_ID,
                "Arroz Tipo 1 5kg",
                categoryId,
                "KG",
                "grão longo tipo 1",
                minQuantity));
    assertThat(updated)
        .satisfies(
            product -> {
              assertThat(product.name()).isEqualTo("Arroz Tipo 1 5kg");
              assertThat(product.categoryId()).isEqualTo(categoryId);
              assertThat(product.unit()).isEqualTo("KG");
              assertThat(product.description()).isEqualTo("grão longo tipo 1");
              assertThat(product.minQuantity()).isEqualByComparingTo("2.500");
              assertThat(product.version()).as("lock otimista avançou").isEqualTo(1);
              assertThat(product.deletedAt()).isNull();
            });
    assertThat(updated.barcode()).as("barcode não muda por aqui").isEqualTo("7891000000017");
    assertThat(updated.price()).as("preço não muda por aqui").isEqualByComparingTo("24.90");
    assertThat(auditRecorder.recorded).as("a edição gera um evento").hasSize(1);
  }

  @Test
  @DisplayName(
      "versão diferente da lida no If-Match lança ConflictException(CONCURRENT_MODIFICATION) sem gravar")
  void rejectsStaleVersion() {
    productStore.stored = product(2, "Arroz 5kg", null, "UN", "grão longo", null, true, null);

    assertThatThrownBy(() -> useCase.execute(command(1)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));

    assertThat(productStore.updateCall).as("o 409 não toca na linha").isNull();
    assertThat(auditRecorder.recorded).as("edição recusada não inventa evento").isEmpty();
  }

  @Test
  @DisplayName("produto soft-deletado ou desativado conta como inexistente: 404 sem gravar")
  void rejectsProductOutOfCatalog() {
    for (ProductSummary out :
        List.of(
            product(0, "Arroz 5kg", null, "UN", "grão longo", null, false, null),
            product(
                0,
                "Arroz 5kg",
                null,
                "UN",
                "grão longo",
                null,
                true,
                Instant.parse("2026-01-03T00:00:00Z")))) {
      productStore.stored = out;

      assertThatThrownBy(() -> useCase.execute(command(0)))
          .isInstanceOfSatisfying(
              NotFoundException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));

      assertThat(productStore.updateCall).isNull();
    }
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("id desconhecido lança NotFoundException(PRODUCT_NOT_FOUND) sem gravar")
  void rejectsUnknownId() {
    productStore.stored = null;

    assertThatThrownBy(() -> useCase.execute(command(0)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));

    assertThat(productStore.updateCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("unidade fora de UN/KG ou ausente é erro de validação sem gravar")
  void rejectsInvalidUnit() {
    for (String unit : Arrays.asList(null, "CX", "un", "")) {
      assertThatThrownBy(() -> useCase.execute(command(0, "Arroz 5kg", null, unit, null, null)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(productStore.updateCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("categoria informada que não existe lança NotFoundException(CATEGORY_NOT_FOUND)")
  void rejectsUnknownCategory() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.execute(command(0, "Arroz 5kg", unknown, "UN", null, null)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
              assertThat(error.getMessage()).contains(unknown.toString());
            });

    assertThat(productStore.updateCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("a edição grava PRODUCT_UPDATED com o antes/depois dos cinco campos editados")
  void recordsProductUpdated() {
    UUID categoryId = categoryStore.existingCategory();

    useCase.execute(
        command(
            0, "Arroz Tipo 1 5kg", categoryId, "KG", "grão longo tipo 1", new BigDecimal("2.5")));

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("PRODUCT_UPDATED");
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.entityId()).isEqualTo(PRODUCT_ID);
    assertThat(event.reason()).isNull();

    Map<String, Object> before = snapshot(event.details(), "before");
    assertThat(before)
        .containsEntry("name", "Arroz 5kg")
        .containsEntry("unit", "UN")
        .containsEntry("description", "grão longo")
        .containsKey("categoryId")
        .containsKey("minQuantity");
    assertThat(before.get("categoryId")).as("produto nasceu sem categoria").isNull();
    assertThat(before.get("minQuantity")).as("produto nasceu sem mínimo").isNull();

    Map<String, Object> after = snapshot(event.details(), "after");
    assertThat(after)
        .containsEntry("name", "Arroz Tipo 1 5kg")
        .containsEntry("categoryId", categoryId)
        .containsEntry("unit", "KG")
        .containsEntry("description", "grão longo tipo 1")
        .containsEntry("minQuantity", new BigDecimal("2.5"));
  }

  /** Comando com os campos que o teste não varia: o produto do dublê começa com Arroz 5kg/UN. */
  private static UpdateProductCommand command(long expectedVersion) {
    return command(expectedVersion, "Arroz Tipo 1 5kg", null, "KG", "grão longo tipo 1", null);
  }

  private static UpdateProductCommand command(
      long expectedVersion,
      String name,
      UUID categoryId,
      String unit,
      String description,
      BigDecimal minQuantity) {
    return new UpdateProductCommand(
        PRODUCT_ID, expectedVersion, name, categoryId, unit, description, minQuantity);
  }

  /** Produto do "banco" do dublê; só o que cada teste varia entra por parâmetro. */
  private static ProductSummary product(
      long version,
      String name,
      UUID categoryId,
      String unit,
      String description,
      BigDecimal minQuantity,
      boolean active,
      Instant deletedAt) {
    return new ProductSummary(
        PRODUCT_ID,
        STORE_ID,
        "7891000000017",
        name,
        description,
        categoryId,
        unit,
        new BigDecimal("24.90"),
        minQuantity,
        active,
        CREATED_AT,
        CREATED_AT,
        deletedAt,
        version);
  }

  /** Antes/depois aninhado em {@code details}, como o caso de uso o entregou. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> snapshot(Map<String, Object> details, String key) {
    return (Map<String, Object>) details.get(key);
  }

  /**
   * Dublê de {@link ProductStore}: guarda um produto só — o "banco" que o caso de uso lê e
   * reescreve — e a última chamada de {@code update} para conferência.
   */
  private static final class FakeProductStore implements ProductStore {

    private ProductSummary stored =
        product(0, "Arroz 5kg", null, "UN", "grão longo", null, true, null);
    private Update updateCall;

    @Override
    public Optional<ProductSummary> findById(UUID id) {
      return Optional.ofNullable(stored).filter(product -> product.id().equals(id));
    }

    @Override
    public Optional<ProductSummary> update(
        UUID id,
        String name,
        UUID categoryId,
        String unit,
        String description,
        BigDecimal minQuantity) {
      updateCall = new Update(id, name, categoryId, unit, description, minQuantity);
      if (stored == null || !stored.id().equals(id)) {
        return Optional.empty();
      }
      stored = withDetails(stored, name, categoryId, unit, description, minQuantity);
      return Optional.of(stored);
    }

    /** O update do "banco" do dublê: troca os campos da edição e avança a versão. */
    private static ProductSummary withDetails(
        ProductSummary product,
        String name,
        UUID categoryId,
        String unit,
        String description,
        BigDecimal minQuantity) {
      return new ProductSummary(
          product.id(),
          product.storeId(),
          product.barcode(),
          name,
          description,
          categoryId,
          unit,
          product.price(),
          minQuantity,
          product.active(),
          product.createdAt(),
          product.updatedAt(),
          product.deletedAt(),
          product.version() + 1);
    }

    @Override
    public UUID insert(NewProduct product) {
      throw new UnsupportedOperationException("insert não é usado por UpdateProduct");
    }

    @Override
    public Optional<ProductSummary> updatePrice(UUID id, BigDecimal price) {
      throw new UnsupportedOperationException("updatePrice não é usado por UpdateProduct");
    }

    @Override
    public Optional<ProductSummary> findByBarcode(String barcode) {
      throw new UnsupportedOperationException("findByBarcode não é usado por UpdateProduct");
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
      throw new UnsupportedOperationException("search não é usado por UpdateProduct");
    }

    @Override
    public long count(String search, UUID categoryId, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por UpdateProduct");
    }

    @Override
    public void softDelete(UUID id) {
      throw new UnsupportedOperationException("softDelete não é usado por UpdateProduct");
    }

    @Override
    public boolean existsActiveBarcode(String barcode) {
      throw new UnsupportedOperationException("existsActiveBarcode não é usado por UpdateProduct");
    }

    @Override
    public Optional<ProductSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por UpdateProduct");
    }

    @Override
    public Optional<ProductSummary> enable(UUID id) {
      throw new UnsupportedOperationException("enable não é usado por UpdateProduct");
    }
  }

  /**
   * Dublê de {@link CategoryStore}: simula categorias existentes para a checagem do caso de uso.
   */
  private static final class FakeCategoryStore implements CategoryStore {

    private final Set<UUID> existing = new HashSet<>();

    private UUID existingCategory() {
      UUID id = UUID.randomUUID();
      existing.add(id);
      return id;
    }

    @Override
    public boolean existsById(UUID id) {
      return existing.contains(id);
    }

    @Override
    public UUID insert(NewCategory category) {
      throw new UnsupportedOperationException("insert não é usado por UpdateProduct");
    }

    @Override
    public Optional<CategorySummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por UpdateProduct");
    }

    @Override
    public List<CategorySummary> findAll() {
      throw new UnsupportedOperationException("findAll não é usado por UpdateProduct");
    }

    @Override
    public void update(UUID id, String name, UUID parentId, int sortOrder) {
      throw new UnsupportedOperationException("update não é usado por UpdateProduct");
    }

    @Override
    public void deactivate(UUID id) {
      throw new UnsupportedOperationException("deactivate não é usado por UpdateProduct");
    }

    @Override
    public boolean existsByName(String name) {
      throw new UnsupportedOperationException("existsByName não é usado por UpdateProduct");
    }

    @Override
    public boolean existsByNameExceptId(String name, UUID id) {
      throw new UnsupportedOperationException("existsByNameExceptId não é usado por UpdateProduct");
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

  /** Chamada de update que o caso de uso fez à porta. */
  private record Update(
      UUID id,
      String name,
      UUID categoryId,
      String unit,
      String description,
      BigDecimal minQuantity) {}
}
