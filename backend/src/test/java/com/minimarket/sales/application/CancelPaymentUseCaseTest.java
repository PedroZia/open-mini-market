package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentStatus;
import com.minimarket.sales.domain.PaymentTotals;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Permission;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link CancelPaymentUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão, o agregado é o de verdade e o relógio é fixo — o instante do cancelamento é
 * conferido como o caso de uso o gravou. O efeito no banco (status, pago recalculado e evento) é
 * coberto pelo {@code PaymentResourceTest}, contra PostgreSQL real.
 */
class CancelPaymentUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SALE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000030");
  private static final UUID RICE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000040");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID ANOTHER_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant OPENED_AT = Instant.parse("2026-09-24T12:00:00Z");
  private static final Instant CANCELLED_AT = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakePaymentStore paymentStore = new FakePaymentStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();
  private final FakeAuthorizationService authorizationService = new FakeAuthorizationService();

  private CancelPaymentUseCase useCase;

  private Payment pix;
  private Payment cash;

  @BeforeEach
  void setUp() {
    useCase = new CancelPaymentUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.paymentStore = paymentStore;
    useCase.auditRecorder = auditRecorder;
    useCase.authorizationService = authorizationService;
    useCase.clock = Clock.fixed(CANCELLED_AT, ZoneOffset.UTC);
    authorizationService.granted.add(Permission.PAYMENT_ADD);

    Sale sale = openSaleWithItems();
    pix = existingPayment(sale, PaymentMethod.PIX, "20.00", null);
    cash = existingPayment(sale, PaymentMethod.CASH, "30.00", "50.00");
    paymentStore.payments.add(pix);
    paymentStore.payments.add(cash);
    sale.applyPaymentTotals(PaymentTotals.of(paymentStore.payments));
    saleStore.sale = sale;
  }

  @Test
  @DisplayName("cancela o pagamento, recalcula o pago da venda e audita PAYMENT_CANCELLED")
  void cancelsPaymentAndAudits() {
    Sale sale = useCase.execute(command(cash.id()));

    assertThat(pix.status())
        .as("o outro pagamento segue aprovado")
        .isEqualTo(PaymentStatus.APPROVED);
    assertThat(cash.status()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(cash.cancelledAt()).as("o instante é o do Clock injetado").isEqualTo(CANCELLED_AT);
    assertThat(paymentStore.cancelled).containsExactly(cash);
    assertThat(sale.paidAmount())
        .as("o pago volta a ser a soma dos aprovados (BR-05)")
        .isEqualByComparingTo("20.00");
    assertThat(sale.changeAmount())
        .as("o troco do pagamento cancelado sai da venda")
        .isEqualByComparingTo("0.00");
    assertThat(saleStore.updated)
        .as("o agregado com o pago recalculado é o que vai para o banco")
        .isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("PAYMENT_CANCELLED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("paymentId", cash.id())
        .containsEntry("method", "CASH")
        .containsEntry("amount", new BigDecimal("30.00"))
        .containsEntry("paidAmount", new BigDecimal("20.00"));
  }

  @Test
  @DisplayName("pagamento já cancelado é no-op: sem gravar e sem novo evento")
  void treatsSecondCancellationAsNoOp() {
    cash.cancel(CANCELLED_AT.minusSeconds(60));
    saleStore.sale.applyPaymentTotals(PaymentTotals.of(paymentStore.payments));

    Sale sale = useCase.execute(command(cash.id()));

    assertThat(sale.paidAmount())
        .as("o pago já gravado é o dos aprovados, como a primeira chamada o deixou")
        .isEqualByComparingTo("20.00");
    assertThat(paymentStore.cancelled).isEmpty();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("pagamento fora da venda lança NotFoundException(PAYMENT_NOT_FOUND) sem gravar")
  void rejectsPaymentOutsideSale() {
    assertThatThrownBy(() -> useCase.execute(command(UUID.randomUUID())))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));

    assertNothingWritten();
  }

  @Test
  @DisplayName("sessão sem payment.add lança ForbiddenException(ACCESS_DENIED) sem tocar na venda")
  void deniesWithoutPermission() {
    authorizationService.granted.clear();

    assertThatThrownBy(() -> useCase.execute(command(cash.id())))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED));

    assertThat(paymentStore.listedSaleId).as("a recusa vem antes de ler os pagamentos").isNull();
    assertNothingWritten();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(() -> useCase.execute(command(cash.id())))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertNothingWritten();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem gravar")
  void rejectsSaleOfAnotherRegister() {
    assertThatThrownBy(
            () ->
                useCase.execute(new CancelPaymentCommand(SALE_ID, ANOTHER_REGISTER_ID, cash.id())))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertNothingWritten();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) sem gravar")
  void rejectsCompletedSale() {
    saleStore.sale.complete(CANCELLED_AT.minusSeconds(60));

    assertThatThrownBy(() -> useCase.execute(command(cash.id())))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertNothingWritten();
  }

  /** Venda aberta como o 805 a cria, com um item de 2 × 25.00 — total de 50.00. */
  private static Sale openSaleWithItems() {
    Sale sale =
        new Sale(
            SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, OPENED_AT);
    sale.addItem(
        RICE_ID, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));
    return sale;
  }

  /** Pagamento já aprovado na venda, como uma chamada anterior do 904 o deixou. */
  private static Payment existingPayment(
      Sale sale, PaymentMethod method, String amount, String tendered) {
    return new Payment(
        UuidCreator.getTimeOrderedEpoch(),
        sale.id(),
        method,
        new BigDecimal(amount),
        tendered == null ? null : new BigDecimal(tendered),
        OPERATOR_ID,
        OPENED_AT.plusSeconds(60));
  }

  private static CancelPaymentCommand command(UUID paymentId) {
    return new CancelPaymentCommand(SALE_ID, CASH_REGISTER_ID, paymentId);
  }

  /** A recusa não pode deixar rastro: nada cancelado, nada atualizado, nenhum evento. */
  private void assertNothingWritten() {
    assertThat(paymentStore.cancelled).isEmpty();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }
}
