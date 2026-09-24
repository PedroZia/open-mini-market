package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Permission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link RemoveDiscountUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o agregado é o de verdade, para a volta ao subtotal ser conferida como o domínio
 * a calcula. A permissão vem de um dublê de {@code AuthorizationService}, como no teste do {@link
 * ApplyDiscountUseCase}.
 */
class RemoveDiscountUseCaseTest {

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
  private static final UUID AUTHORIZER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000003");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();
  private final FakeAuthorizationService authorizationService = new FakeAuthorizationService();

  private RemoveDiscountUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new RemoveDiscountUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.auditRecorder = auditRecorder;
    useCase.authorizationService = authorizationService;
    authorizationService.granted.add(Permission.SALE_DISCOUNT_APPLY);
    saleStore.sale = saleWithDiscount();
  }

  @Test
  @DisplayName("remove o desconto, volta os totais ao subtotal e audita o que saiu")
  void removesDiscountRestoringSubtotalAndAuditing() {
    Sale sale = useCase.execute(new RemoveDiscountCommand(SALE_ID, CASH_REGISTER_ID));

    assertThat(sale.discountType()).isNull();
    assertThat(sale.discountValue()).isNull();
    assertThat(sale.discountReason()).isNull();
    assertThat(sale.discountAuthorizedByUserId()).as("o autor sai junto (passo 1009)").isNull();
    assertThat(sale.discountAmount()).isEqualByComparingTo("0.00");
    assertThat(sale.subtotal())
        .as("o subtotal é dos itens e não muda")
        .isEqualByComparingTo("28.30");
    assertThat(sale.total())
        .as("sem desconto o total volta ao subtotal")
        .isEqualByComparingTo("28.30");
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_DISCOUNT_REMOVED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).as("o motivo do desconto que saiu").isEqualTo("cliente fidelidade");
    assertThat(event.details())
        .containsEntry("type", "PERCENT")
        .containsEntry("value", new BigDecimal("10.00"))
        .containsEntry("discountAmount", new BigDecimal("2.83"))
        .containsEntry("total", new BigDecimal("28.30"));
  }

  @Test
  @DisplayName("venda sem desconto é no-op: devolve o agregado sem gravar nem auditar")
  void removalWithoutDiscountIsNoOp() {
    saleStore.sale = openSale();

    Sale sale = useCase.execute(new RemoveDiscountCommand(SALE_ID, CASH_REGISTER_ID));

    assertThat(sale).isSameAs(saleStore.sale);
    assertThat(sale.discountType()).isNull();
    assertThat(sale.total()).isEqualByComparingTo("0.00");
    assertThat(saleStore.updateCount).as("no-op não grava").isZero();
    assertThat(auditRecorder.recorded).as("no-op não inventa evento").isEmpty();
  }

  @Test
  @DisplayName(
      "sessão sem sale.discount.apply lança ForbiddenException(ACCESS_DENIED) sem ler a venda")
  void deniesSessionWithoutDiscountPermission() {
    authorizationService.granted.clear();

    assertThatThrownBy(() -> useCase.execute(new RemoveDiscountCommand(SALE_ID, CASH_REGISTER_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(Permission.SALE_DISCOUNT_APPLY.code());
            });

    assertThat(saleStore.sale.discountType())
        .as("a venda não é tocada na recusa")
        .isEqualTo(DiscountType.PERCENT);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) antes de mutar o agregado")
  void rejectsCompletedSale() {
    saleStore.sale.complete(NOW);

    assertThatThrownBy(() -> useCase.execute(new RemoveDiscountCommand(SALE_ID, CASH_REGISTER_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertThat(saleStore.sale.discountType())
        .as("o desconto nem saiu do agregado")
        .isEqualTo(DiscountType.PERCENT);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSaleOfAnotherRegister() {
    assertThatThrownBy(
            () -> useCase.execute(new RemoveDiscountCommand(SALE_ID, ANOTHER_REGISTER_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.discountType()).isEqualTo(DiscountType.PERCENT);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(() -> useCase.execute(new RemoveDiscountCommand(SALE_ID, CASH_REGISTER_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Venda aberta com dois itens e desconto de 10%: 19,80 + 8,50 = 28,30, desconto de 2,83. */
  private static Sale saleWithDiscount() {
    Sale sale = openSale();
    sale.addItem(
        RICE_ID, "7891000100103", "Arroz 5kg", "UN", new BigDecimal("9.90"), new BigDecimal("2"));
    sale.addItem(BEANS_ID, null, "Feijão 1kg", "UN", new BigDecimal("8.50"), BigDecimal.ONE);
    sale.applyDiscount(
        DiscountType.PERCENT, new BigDecimal("10"), "cliente fidelidade", AUTHORIZER_ID);
    return sale;
  }

  /** Venda aberta e vazia, como o 805 a cria. */
  private static Sale openSale() {
    return new Sale(
        SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
  }
}
