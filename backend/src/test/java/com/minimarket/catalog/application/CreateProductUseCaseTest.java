package com.minimarket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Store;
import java.lang.reflect.RecordComponent;
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
 * Unitários puros do {@link CreateProductUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão. O gravador de auditoria também é dublê — o evento é conferido como o caso de uso
 * o entregou, e a gravação de verdade contra o PostgreSQL é coberta pelo {@code
 * CreateProductAuditTest}.
 */
class CreateProductUseCaseTest {

  private static final String STORE_CODE = "MATRIZ";

  private final FakeProductStore productStore = new FakeProductStore();
  private final FakeCategoryStore categoryStore = new FakeCategoryStore();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private CreateProductUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new CreateProductUseCase();
    useCase.productStore = productStore;
    useCase.categoryStore = categoryStore;
    useCase.storeLookup = storeLookup;
    useCase.auditRecorder = auditRecorder;
    useCase.defaultStoreCode = STORE_CODE;
  }

  @Test
  @DisplayName(
      "cria produto com barcode e preço normalizados, loja resolvida e evento PRODUCT_CREATED")
  void createsProduct() {
    UUID categoryId = categoryStore.existingCategory();

    CreateProductResult result =
        useCase.execute(
            new CreateProductCommand(
                "Arroz 5kg",
                " 789 1000 000017 ",
                "grão longo",
                categoryId,
                "UN",
                new BigDecimal("24.9"),
                new BigDecimal("1.000")));

    assertThat(result.id()).isEqualTo(productStore.generatedId);
    assertThat(result.product())
        .as("a resposta relê o produto gravado, com os defaults que o banco completou")
        .satisfies(
            product -> {
              assertThat(product.id()).isEqualTo(productStore.generatedId);
              assertThat(product.barcode()).isEqualTo("7891000000017");
              assertThat(product.price()).isEqualByComparingTo("24.90");
              assertThat(product.active()).isTrue();
              assertThat(product.deletedAt()).isNull();
              assertThat(product.version()).isZero();
              assertThat(product.createdAt()).isEqualTo(FakeProductStore.CREATED_AT);
            });
    assertThat(productStore.inserted.storeId())
        .as("loja vem da configuração, não do comando")
        .isEqualTo(storeLookup.storeId);
    assertThat(productStore.inserted.name()).isEqualTo("Arroz 5kg");
    assertThat(productStore.inserted.barcode())
        .as("trim e espaços internos removidos")
        .isEqualTo("7891000000017");
    assertThat(productStore.inserted.description()).isEqualTo("grão longo");
    assertThat(productStore.inserted.categoryId()).isEqualTo(categoryId);
    assertThat(productStore.inserted.unit()).isEqualTo("UN");
    assertThat(productStore.inserted.price())
        .as("dinheiro normalizado para escala 2")
        .isEqualByComparingTo("24.90");
    assertThat(productStore.inserted.price().scale()).isEqualTo(2);
    assertThat(productStore.inserted.minQuantity()).isEqualByComparingTo("1.000");
    assertThat(NewProduct.class.getRecordComponents())
        .as("produto nasce ativo e sem deletedAt: a inserção não carrega status")
        .extracting(RecordComponent::getName)
        .doesNotContain("active", "deletedAt");

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("PRODUCT_CREATED");
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.entityId()).isEqualTo(productStore.generatedId);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("name", "Arroz 5kg")
        .containsEntry("barcode", "7891000000017")
        .containsEntry("price", new BigDecimal("24.90"));
  }

  @Test
  @DisplayName("barcode em branco vira nulo sem checar duplicidade; categoria nula é aceita")
  void normalizesBlankBarcodeAndAllowsNoCategory() {
    CreateProductResult result = useCase.execute(command("   ", null));

    assertThat(productStore.inserted.barcode()).isNull();
    assertThat(productStore.barcodeChecked).as("sem barcode não há duplicidade a checar").isFalse();
    assertThat(productStore.inserted.categoryId()).isNull();
    assertThat(result.product().barcode()).as("o resultado relê o barcode nulo do banco").isNull();
    assertThat(categoryStore.existenceChecked)
        .as("sem categoria não há existência a checar")
        .isFalse();
  }

  @Test
  @DisplayName("barcode duplicado entre produtos vivos lança ConflictException sem inserir")
  void rejectsDuplicateBarcode() {
    productStore.existingBarcodes.add("7891000000017");

    assertThatThrownBy(() -> useCase.execute(command(" 789 1000 000017 ", null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.BARCODE_ALREADY_EXISTS));

    assertThat(productStore.inserted).isNull();
    assertThat(auditRecorder.recorded).as("criação recusada não inventa evento").isEmpty();
  }

  @Test
  @DisplayName("preço negativo ou ausente é erro de validação sem inserir")
  void rejectsInvalidPrice() {
    CreateProductCommand negative = commandWithPrice(new BigDecimal("-0.01"));
    CreateProductCommand absent = commandWithPrice(null);

    for (CreateProductCommand command : List.of(negative, absent)) {
      assertThatThrownBy(() -> useCase.execute(command))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(productStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("unidade fora de UN/KG ou ausente é erro de validação sem inserir")
  void rejectsInvalidUnit() {
    for (String unit : Arrays.asList(null, "CX", "un", "")) {
      assertThatThrownBy(() -> useCase.execute(commandWithUnit(unit)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(productStore.inserted).isNull();
  }

  @Test
  @DisplayName("categoria informada que não existe lança NotFoundException(CATEGORY_NOT_FOUND)")
  void rejectsUnknownCategory() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.execute(command(null, unknown)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
              assertThat(error.getMessage()).contains(unknown.toString());
            });

    assertThat(productStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  private static CreateProductCommand command(String barcode, UUID categoryId) {
    return new CreateProductCommand(
        "Arroz 5kg", barcode, "grão longo", categoryId, "UN", new BigDecimal("24.90"), null);
  }

  private static CreateProductCommand commandWithPrice(BigDecimal price) {
    return new CreateProductCommand("Arroz 5kg", null, "grão longo", null, "UN", price, null);
  }

  private static CreateProductCommand commandWithUnit(String unit) {
    return new CreateProductCommand(
        "Arroz 5kg", null, "grão longo", null, unit, new BigDecimal("24.90"), null);
  }

  /** Dublê de {@link ProductStore}: guarda a última inserção e simula barcodes já em uso. */
  private static final class FakeProductStore implements ProductStore {

    /** Instante fixo do "banco" do dublê: o resultado da criação é conferido campo a campo. */
    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private final Set<String> existingBarcodes = new HashSet<>();
    private final UUID generatedId = UUID.randomUUID();
    private NewProduct inserted;
    private boolean barcodeChecked;

    @Override
    public UUID insert(NewProduct product) {
      inserted = product;
      return generatedId;
    }

    @Override
    public boolean existsActiveBarcode(String barcode) {
      barcodeChecked = true;
      return existingBarcodes.contains(barcode);
    }

    /**
     * O que o adaptador JPA devolveria na releitura pós-insert: o produto gravado com os defaults
     * que o banco completou — ativo, sem deletedAt, {@code version} 0 e timestamps.
     */
    @Override
    public Optional<ProductSummary> findById(UUID id) {
      if (inserted == null || !generatedId.equals(id)) {
        return Optional.empty();
      }
      return Optional.of(
          new ProductSummary(
              generatedId,
              inserted.storeId(),
              inserted.barcode(),
              inserted.name(),
              inserted.description(),
              inserted.categoryId(),
              inserted.unit(),
              inserted.price(),
              inserted.minQuantity(),
              true,
              CREATED_AT,
              CREATED_AT,
              null,
              0L));
    }

    @Override
    public Optional<ProductSummary> findByBarcode(String barcode) {
      throw new UnsupportedOperationException("findByBarcode não é usado por CreateProduct");
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
      throw new UnsupportedOperationException("search não é usado por CreateProduct");
    }

    @Override
    public long count(String search, UUID categoryId, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por CreateProduct");
    }

    @Override
    public Optional<ProductSummary> update(
        UUID id,
        String name,
        UUID categoryId,
        String unit,
        String description,
        BigDecimal minQuantity) {
      throw new UnsupportedOperationException("update não é usado por CreateProduct");
    }

    @Override
    public void softDelete(UUID id) {
      throw new UnsupportedOperationException("softDelete não é usado por CreateProduct");
    }

    @Override
    public Optional<ProductSummary> updatePrice(UUID id, BigDecimal price) {
      throw new UnsupportedOperationException("updatePrice não é usado por CreateProduct");
    }
  }

  /**
   * Dublê de {@link CategoryStore}: simula categorias existentes para a checagem do caso de uso.
   */
  private static final class FakeCategoryStore implements CategoryStore {

    private final Set<UUID> existing = new HashSet<>();
    private boolean existenceChecked;

    private UUID existingCategory() {
      UUID id = UUID.randomUUID();
      existing.add(id);
      return id;
    }

    @Override
    public boolean existsById(UUID id) {
      existenceChecked = true;
      return existing.contains(id);
    }

    @Override
    public UUID insert(NewCategory category) {
      throw new UnsupportedOperationException("insert não é usado por CreateProduct");
    }

    @Override
    public Optional<CategorySummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por CreateProduct");
    }

    @Override
    public List<CategorySummary> findAll() {
      throw new UnsupportedOperationException("findAll não é usado por CreateProduct");
    }

    @Override
    public void update(UUID id, String name, UUID parentId, int sortOrder) {
      throw new UnsupportedOperationException("update não é usado por CreateProduct");
    }

    @Override
    public void deactivate(UUID id) {
      throw new UnsupportedOperationException("deactivate não é usado por CreateProduct");
    }

    @Override
    public boolean existsByName(String name) {
      throw new UnsupportedOperationException("existsByName não é usado por CreateProduct");
    }

    @Override
    public boolean existsByNameExceptId(String name, UUID id) {
      throw new UnsupportedOperationException("existsByNameExceptId não é usado por CreateProduct");
    }
  }

  /** Dublê de {@link StoreLookup}: devolve a loja configurada, como o seed da V1. */
  private static final class FakeStoreLookup implements StoreLookup {

    private final UUID storeId = UUID.randomUUID();

    @Override
    public Optional<Store> findByCode(String code) {
      return STORE_CODE.equals(code)
          ? Optional.of(new Store(storeId, STORE_CODE, "Matriz", false, new BigDecimal("10.00")))
          : Optional.empty();
    }

    @Override
    public Optional<Store> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por CreateProduct");
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
}
