package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link ChangeSaleItemQuantityUseCase}, sem Quarkus e sem banco: as portas são
 * dublês escritos à mão e o agregado é o de verdade, para o total da linha e os totais da venda
 * serem conferidos como o domínio os calcula. O gravador de auditoria também é dublê — o evento é
 * conferido como o caso de uso o entregou.
 */
class ChangeSaleItemQuantityUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SALE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000030");
  private static final UUID RICE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000040");
  private static final UUID BEANS_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000041");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID ANOTHER_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private ChangeSaleItemQuantityUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new ChangeSaleItemQuantityUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.auditRecorder = auditRecorder;
    saleStore.sale = saleWithItems();
  }

  @Test
  @DisplayName("troca a quantidade, recalcula o total da linha e os totais, e audita a mudança")
  void changesQuantityRecalculatingTotalsAndAuditing() {
    Sale sale =
        useCase.execute(
            new ChangeSaleItemQuantityCommand(
                SALE_ID, CASH_REGISTER_ID, RICE_ID, new BigDecimal("3")));

    SaleItem rice = sale.items().getFirst();
    assertThat(rice.productId()).isEqualTo(RICE_ID);
    assertThat(rice.quantity()).isEqualByComparingTo("3.000");
    assertThat(rice.lineTotal()).isEqualByComparingTo("29.70");
    assertThat(rice.unitPrice())
        .as("BR-01: o snapshot do preço não muda")
        .isEqualByComparingTo("9.90");
    assertThat(sale.itemCount()).as("trocar quantidade não muda o número de itens").isEqualTo(2);
    assertThat(sale.subtotal()).isEqualByComparingTo("38.20");
    assertThat(sale.discountAmount()).isEqualByComparingTo("0.00");
    assertThat(sale.total()).isEqualByComparingTo("38.20");
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_ITEM_QUANTITY_CHANGED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("productId", RICE_ID)
        .containsEntry("previousQuantity", new BigDecimal("2.000"))
        .containsEntry("quantity", new BigDecimal("3.000"))
        .containsEntry("lineTotal", new BigDecimal("29.70"))
        .containsEntry("subtotal", new BigDecimal("38.20"))
        .containsEntry("discountAmount", new BigDecimal("0.00"))
        .containsEntry("total", new BigDecimal("38.20"))
        .containsEntry("itemCount", 2);
  }

  @Test
  @DisplayName("item fora da venda lança NotFoundException(SALE_ITEM_NOT_FOUND) sem gravar")
  void rejectsItemNotInSale() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ChangeSaleItemQuantityCommand(
                        SALE_ID, CASH_REGISTER_ID, OPERATOR_ID, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_ITEM_NOT_FOUND);
              assertThat(error.getMessage()).contains(OPERATOR_ID.toString(), SALE_ID.toString());
            });

    assertThat(saleStore.sale.items().getFirst().quantity())
        .as("a quantidade do item existente não é tocada")
        .isEqualByComparingTo("2.000");
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ChangeSaleItemQuantityCommand(
                        SALE_ID, CASH_REGISTER_ID, RICE_ID, new BigDecimal("1"))))
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
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ChangeSaleItemQuantityCommand(
                        SALE_ID, ANOTHER_REGISTER_ID, RICE_ID, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.items().getFirst().quantity()).isEqualByComparingTo("2.000");
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) antes de mutar o agregado")
  void rejectsCompletedSale() {
    saleStore.sale.complete(NOW);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ChangeSaleItemQuantityCommand(
                        SALE_ID, CASH_REGISTER_ID, RICE_ID, new BigDecimal("1"))))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertThat(saleStore.sale.items().getFirst().quantity()).isEqualByComparingTo("2.000");
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Venda aberta com dois itens, como o 808 a deixa depois de dois bipes. */
  private static Sale saleWithItems() {
    Sale sale =
        new Sale(SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
    sale.addItem(
        RICE_ID, "7891000100103", "Arroz 5kg", "UN", new BigDecimal("9.90"), new BigDecimal("2"));
    sale.addItem(BEANS_ID, null, "Feijão 1kg", "UN", new BigDecimal("8.50"), new BigDecimal("1"));
    return sale;
  }
}
