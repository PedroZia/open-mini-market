package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.Sale;
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
 * Unitários puros do {@link RemoveSaleItemUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o agregado é o de verdade, para subtotal, total e itemCount serem conferidos
 * como o domínio os recalcula. O gravador de auditoria também é dublê — o evento é conferido como o
 * caso de uso o entregou.
 */
class RemoveSaleItemUseCaseTest {

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

  private RemoveSaleItemUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new RemoveSaleItemUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.auditRecorder = auditRecorder;
    saleStore.sale = saleWithItems();
  }

  @Test
  @DisplayName("remove o item, recalcula os totais e audita a remoção com o que saiu")
  void removesItemRecalculatingTotalsAndAuditing() {
    Sale sale = useCase.execute(new RemoveSaleItemCommand(SALE_ID, CASH_REGISTER_ID, RICE_ID));

    assertThat(sale.items()).as("sobrou só o feijão").hasSize(1);
    assertThat(sale.items().getFirst().productId()).isEqualTo(BEANS_ID);
    assertThat(sale.itemCount()).isEqualTo(1);
    assertThat(sale.subtotal()).isEqualByComparingTo("8.50");
    assertThat(sale.discountAmount()).isEqualByComparingTo("0.00");
    assertThat(sale.total()).isEqualByComparingTo("8.50");
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_ITEM_REMOVED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("productId", RICE_ID)
        .containsEntry("quantity", new BigDecimal("2.000"))
        .containsEntry("lineTotal", new BigDecimal("19.80"))
        .containsEntry("subtotal", new BigDecimal("8.50"))
        .containsEntry("discountAmount", new BigDecimal("0.00"))
        .containsEntry("total", new BigDecimal("8.50"))
        .containsEntry("itemCount", 1);
  }

  @Test
  @DisplayName("item fora da venda lança NotFoundException(SALE_ITEM_NOT_FOUND) sem gravar")
  void rejectsItemNotInSale() {
    assertThatThrownBy(
            () ->
                useCase.execute(new RemoveSaleItemCommand(SALE_ID, CASH_REGISTER_ID, OPERATOR_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_ITEM_NOT_FOUND);
              assertThat(error.getMessage()).contains(OPERATOR_ID.toString(), SALE_ID.toString());
            });

    assertThat(saleStore.sale.items()).as("nenhum item sai na recusa").hasSize(2);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(
            () -> useCase.execute(new RemoveSaleItemCommand(SALE_ID, CASH_REGISTER_ID, RICE_ID)))
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
            () -> useCase.execute(new RemoveSaleItemCommand(SALE_ID, ANOTHER_REGISTER_ID, RICE_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.items()).hasSize(2);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) antes de mutar o agregado")
  void rejectsCompletedSale() {
    saleStore.sale.complete(NOW);

    assertThatThrownBy(
            () -> useCase.execute(new RemoveSaleItemCommand(SALE_ID, CASH_REGISTER_ID, RICE_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertThat(saleStore.sale.items()).as("o item nem saiu do agregado").hasSize(2);
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
