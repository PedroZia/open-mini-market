package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductSort;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link AddSaleItemUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o agregado é o de verdade, para o snapshot e os totais serem conferidos como o
 * domínio os calcula. O gravador de auditoria também é dublê — o evento é conferido como o caso de
 * uso o entregou, e a gravação contra o PostgreSQL é coberta pelo {@code
 * AddSaleItemIntegrationTest}.
 */
class AddSaleItemUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SALE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000030");
  private static final UUID PRODUCT_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000040");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID ANOTHER_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");
  private static final String BARCODE = "7891000100103";

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeProductStore productStore = new FakeProductStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private AddSaleItemUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new AddSaleItemUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.productStore = productStore;
    useCase.auditRecorder = auditRecorder;
    saleStore.sale = openSale();
  }

  @Test
  @DisplayName(
      "bipe adiciona o item com o snapshot do produto, recalcula os totais e grava SALE_ITEM_ADDED")
  void addsItemFromBarcodeWithSnapshotAndAudit() {
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", true, null);

    Sale sale =
        useCase.execute(
            new AddSaleItemCommand(
                SALE_ID, CASH_REGISTER_ID, " 7891000100103 ", null, new BigDecimal("2")));

    assertThat(productStore.lookedUpBarcode)
        .as("o barcode chega normalizado ao ProductStore, como no bipe do passo 409")
        .isEqualTo(BARCODE);
    assertThat(sale.items()).hasSize(1);
    SaleItem item = sale.items().getFirst();
    assertThat(item.productId()).isEqualTo(PRODUCT_ID);
    assertThat(item.barcode())
        .as("snapshot do código do produto, não a string lida")
        .isEqualTo(BARCODE);
    assertThat(item.name()).isEqualTo("Arroz 5kg");
    assertThat(item.unit()).isEqualTo("UN");
    assertThat(item.unitPrice()).isEqualByComparingTo("9.90");
    assertThat(item.quantity()).isEqualByComparingTo("2.000");
    assertThat(item.lineTotal()).isEqualByComparingTo("19.80");
    assertThat(sale.itemCount()).isEqualTo(1);
    assertThat(sale.subtotal()).isEqualByComparingTo("19.80");
    assertThat(sale.discountAmount()).isEqualByComparingTo("0.00");
    assertThat(sale.total()).isEqualByComparingTo("19.80");
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_ITEM_ADDED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.cashSessionId())
        .as("a sessão de caixa da venda vai no evento (passo 1006)")
        .isEqualTo(CASH_SESSION_ID);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("productId", PRODUCT_ID)
        .containsEntry("barcode", BARCODE)
        .containsEntry("quantity", new BigDecimal("2"))
        .containsEntry("subtotal", new BigDecimal("19.80"))
        .containsEntry("discountAmount", new BigDecimal("0.00"))
        .containsEntry("total", new BigDecimal("19.80"))
        .containsEntry("itemCount", 1);
  }

  @Test
  @DisplayName(
      "produto pelo id: item com snapshot do cadastro, barcode nulo quando o produto não tem")
  void addsItemFromProductIdWithoutBarcode() {
    productStore.byId = product(null, "Banana prata", "KG", "7.49", true, null);

    Sale sale =
        useCase.execute(
            new AddSaleItemCommand(
                SALE_ID, CASH_REGISTER_ID, null, PRODUCT_ID, new BigDecimal("1.235")));

    assertThat(productStore.lookedUpBarcode).as("sem barcode não há consulta por código").isNull();
    SaleItem item = sale.items().getFirst();
    assertThat(item.productId()).isEqualTo(PRODUCT_ID);
    assertThat(item.barcode()).as("produto sem código gera item sem barcode no snapshot").isNull();
    assertThat(item.unit()).isEqualTo("KG");
    assertThat(item.quantity()).isEqualByComparingTo("1.235");
    assertThat(item.lineTotal()).as("round(1.235 × 7.49, 2, HALF_UP)").isEqualByComparingTo("9.25");
    assertThat(sale.total()).isEqualByComparingTo("9.25");

    assertThat(auditRecorder.only().details()).containsEntry("barcode", null);
  }

  @Test
  @DisplayName("mesmo produto de novo soma a quantidade numa linha só e mantém o snapshot antigo")
  void sumsRepeatedProductInSingleLineKeepingSnapshot() {
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", true, null);
    useCase.execute(
        new AddSaleItemCommand(SALE_ID, CASH_REGISTER_ID, BARCODE, null, new BigDecimal("2")));
    productStore.byBarcode = product(BARCODE, "Arroz 5kg promocional", "UN", "5.00", true, null);

    Sale sale =
        useCase.execute(
            new AddSaleItemCommand(SALE_ID, CASH_REGISTER_ID, BARCODE, null, new BigDecimal("3")));

    assertThat(sale.items()).as("uma linha por produto, não duas").hasSize(1);
    SaleItem item = sale.items().getFirst();
    assertThat(item.name()).as("BR-01: o snapshot é o da primeira inclusão").isEqualTo("Arroz 5kg");
    assertThat(item.unitPrice()).isEqualByComparingTo("9.90");
    assertThat(item.quantity()).isEqualByComparingTo("5.000");
    assertThat(item.lineTotal()).isEqualByComparingTo("49.50");
    assertThat(sale.itemCount()).isEqualTo(1);
    assertThat(sale.subtotal()).isEqualByComparingTo("49.50");
    assertThat(sale.total()).isEqualByComparingTo("49.50");
    assertThat(saleStore.updateCount).as("as duas inclusões passam pelo update").isEqualTo(2);
    assertThat(auditRecorder.recorded).hasSize(2);
  }

  @Test
  @DisplayName("produto inexistente lança NotFoundException(PRODUCT_NOT_FOUND) sem gravar")
  void rejectsUnknownBarcode() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, CASH_REGISTER_ID, BARCODE, null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
              assertThat(error.getMessage()).contains(BARCODE);
            });

    assertThat(saleStore.sale.items()).as("a venda não é tocada na recusa").isEmpty();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("produto inativo lança BusinessException(PRODUCT_INACTIVE) sem gravar")
  void rejectsInactiveProduct() {
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", false, null);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, CASH_REGISTER_ID, BARCODE, null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.PRODUCT_INACTIVE);
              assertThat(error.getMessage()).contains(PRODUCT_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("produto soft-deletado pelo id conta como inativo: PRODUCT_INACTIVE, sem gravar")
  void rejectsSoftDeletedProductFoundById() {
    productStore.byId = product(null, "Arroz 5kg", "UN", "9.90", false, NOW);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, CASH_REGISTER_ID, null, PRODUCT_ID, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.PRODUCT_INACTIVE));

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) antes de mutar o agregado")
  void rejectsCompletedSale() {
    saleStore.sale.complete(NOW);
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", true, null);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, CASH_REGISTER_ID, BARCODE, null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.items()).as("o item nem entrou no agregado").isEmpty();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", true, null);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, CASH_REGISTER_ID, BARCODE, null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSaleOfAnotherRegister() {
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", true, null);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, ANOTHER_REGISTER_ID, BARCODE, null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(productStore.lookedUpBarcode)
        .as("quem não é dono da venda nem descobre se o produto existe")
        .isNull();
    assertThat(saleStore.sale.items()).isEmpty();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName(
      "sessão sem caixa vinculado lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSessionWithoutBoundRegister() {
    productStore.byBarcode = product(BARCODE, "Arroz 5kg", "UN", "9.90", true, null);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(SALE_ID, null, BARCODE, null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED));

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("sem barcode e sem productId é 400 VALIDATION_ERROR, sem consultar nada")
  void rejectsCommandWithoutBarcodeAndProductId() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new AddSaleItemCommand(
                        SALE_ID, CASH_REGISTER_ID, "   ", null, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

    assertThat(productStore.lookedUpBarcode).as("nem a consulta por barcode acontece").isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Venda aberta como o 805 a cria: sem itens e com os totais zerados. */
  private static Sale openSale() {
    return new Sale(
        SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
  }

  /** Produto como o catálogo o projeta; {@code deletedAt} preenchido é o soft-deletado. */
  private static ProductSummary product(
      String barcode, String name, String unit, String price, boolean active, Instant deletedAt) {
    return new ProductSummary(
        PRODUCT_ID,
        STORE_ID,
        barcode,
        name,
        null,
        null,
        unit,
        new BigDecimal(price),
        null,
        active,
        NOW,
        NOW,
        deletedAt,
        0L);
  }

  /**
   * Dublê de {@link ProductStore}: devolve o produto do cenário pelas duas consultas do caso de uso
   * e guarda o barcode consultado — o resto do contrato do catálogo é exercitado pelos testes do
   * módulo {@code catalog}.
   */
  private static final class FakeProductStore implements ProductStore {

    private ProductSummary byBarcode;
    private ProductSummary byId;
    private String lookedUpBarcode;

    @Override
    public Optional<ProductSummary> findByBarcode(String barcode) {
      lookedUpBarcode = barcode;
      return Optional.ofNullable(byBarcode);
    }

    @Override
    public Optional<ProductSummary> findById(UUID id) {
      return Optional.ofNullable(byId).filter(product -> product.id().equals(id));
    }

    @Override
    public UUID insert(NewProduct product) {
      throw new UnsupportedOperationException("insert não é usado por AddSaleItem");
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
      throw new UnsupportedOperationException("search não é usado por AddSaleItem");
    }

    @Override
    public long count(String search, UUID categoryId, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por AddSaleItem");
    }

    @Override
    public Optional<ProductSummary> update(
        UUID id,
        String name,
        UUID categoryId,
        String unit,
        String description,
        BigDecimal minQuantity) {
      throw new UnsupportedOperationException("update não é usado por AddSaleItem");
    }

    @Override
    public Optional<ProductSummary> updatePrice(UUID id, BigDecimal price) {
      throw new UnsupportedOperationException("updatePrice não é usado por AddSaleItem");
    }

    @Override
    public void updateCostPrice(UUID id, BigDecimal costPrice) {
      throw new UnsupportedOperationException("updateCostPrice não é usado por AddSaleItem");
    }

    @Override
    public void softDelete(UUID id) {
      throw new UnsupportedOperationException("softDelete não é usado por AddSaleItem");
    }

    @Override
    public Optional<ProductSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por AddSaleItem");
    }

    @Override
    public Optional<ProductSummary> enable(UUID id) {
      throw new UnsupportedOperationException("enable não é usado por AddSaleItem");
    }

    @Override
    public boolean existsActiveBarcode(String barcode) {
      throw new UnsupportedOperationException("existsActiveBarcode não é usado por AddSaleItem");
    }
  }
}
