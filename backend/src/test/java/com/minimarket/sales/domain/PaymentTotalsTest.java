package com.minimarket.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link PaymentTotals}, sem Quarkus e sem banco: soma dos aprovados (BR-05),
 * troco só do dinheiro, o que falta cobrar e a recusa de pagamento que excede o restante.
 */
class PaymentTotalsTest {

  private static final UUID SALE_ID = UUID.randomUUID();
  private static final UUID OPERATOR_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-09-24T12:00:00Z");
  private static final BigDecimal TOTAL = new BigDecimal("50.00");

  @Test
  @DisplayName("pagamento único em dinheiro: pago, troco e restante zerado")
  void singleCashPayment() {
    PaymentTotals totals =
        PaymentTotals.of(List.of(cash(new BigDecimal("50.00"), new BigDecimal("60.00"))));

    assertThat(totals.paidAmount()).isEqualTo(new BigDecimal("50.00"));
    assertThat(totals.changeAmount()).isEqualTo(new BigDecimal("10.00"));
    assertThat(totals.remaining(TOTAL)).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.isFullyPaid(TOTAL)).isTrue();
  }

  @Test
  @DisplayName("múltiplos pagamentos somam o valor pago e fecham a venda")
  void multiplePaymentsSum() {
    PaymentTotals totals =
        PaymentTotals.of(
            List.of(
                cash(new BigDecimal("20.00"), new BigDecimal("20.00")),
                card(PaymentMethod.CREDIT, new BigDecimal("30.00"))));

    assertThat(totals.paidAmount()).isEqualTo(new BigDecimal("50.00"));
    assertThat(totals.changeAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.remaining(TOTAL)).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.isFullyPaid(TOTAL)).isTrue();
  }

  @Test
  @DisplayName("troco soma só o dos aprovados em dinheiro")
  void changeSumsOnlyCash() {
    PaymentTotals totals =
        PaymentTotals.of(
            List.of(
                cash(new BigDecimal("20.00"), new BigDecimal("25.00")),
                card(PaymentMethod.DEBIT, new BigDecimal("30.00"))));

    assertThat(totals.paidAmount()).isEqualTo(new BigDecimal("50.00"));
    assertThat(totals.changeAmount()).isEqualTo(new BigDecimal("5.00"));
  }

  @Test
  @DisplayName("pagamento cancelado não conta na soma (BR-05)")
  void cancelledDoesNotCount() {
    Payment cancelled = card(PaymentMethod.PIX, new BigDecimal("30.00"));
    cancelled.cancel(Instant.parse("2026-09-24T12:05:00Z"));

    PaymentTotals totals =
        PaymentTotals.of(
            List.of(cash(new BigDecimal("20.00"), new BigDecimal("20.00")), cancelled));

    assertThat(totals.paidAmount())
        .as("o cancelado sai da soma")
        .isEqualTo(new BigDecimal("20.00"));
    assertThat(totals.changeAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.remaining(TOTAL)).isEqualTo(new BigDecimal("30.00"));
    assertThat(totals.isFullyPaid(TOTAL)).isFalse();
  }

  @Test
  @DisplayName("pagamento parcial não conclui: o restante é o que falta cobrar")
  void partialPaymentIsNotEnough() {
    PaymentTotals totals =
        PaymentTotals.of(List.of(cash(new BigDecimal("20.00"), new BigDecimal("20.00"))));

    assertThat(totals.isFullyPaid(TOTAL)).isFalse();
    assertThat(totals.remaining(TOTAL)).isEqualTo(new BigDecimal("30.00"));
  }

  @Test
  @DisplayName("pago exato conclui a venda")
  void exactPaymentCompletes() {
    PaymentTotals totals = PaymentTotals.of(List.of(card(PaymentMethod.PIX, TOTAL)));

    assertThat(totals.isFullyPaid(TOTAL)).isTrue();
    assertThat(totals.remaining(TOTAL)).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  @DisplayName("pago a mais não deixa restante negativo")
  void overpaidClampsRemaining() {
    PaymentTotals totals =
        PaymentTotals.of(List.of(card(PaymentMethod.VOUCHER, new BigDecimal("60.00"))));

    assertThat(totals.remaining(TOTAL)).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.isFullyPaid(TOTAL)).isTrue();
  }

  @Test
  @DisplayName("venda sem pagamento deve o total inteiro")
  void noPaymentsOweTheTotal() {
    PaymentTotals totals = PaymentTotals.of(List.of());

    assertThat(totals.paidAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.changeAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(totals.remaining(TOTAL)).isEqualTo(TOTAL);
    assertThat(totals.isFullyPaid(TOTAL)).isFalse();
  }

  @Test
  @DisplayName("pagamento que cabe no restante é aceito, inclusive o exato")
  void acceptsPaymentWithinRemaining() {
    PaymentTotals totals =
        PaymentTotals.of(List.of(cash(new BigDecimal("20.00"), new BigDecimal("20.00"))));

    totals.requireFitsRemaining(new BigDecimal("10.00"), TOTAL);
    totals.requireFitsRemaining(new BigDecimal("30.00"), TOTAL);

    assertThat(totals.paidAmount())
        .as("conferir não altera a soma")
        .isEqualTo(new BigDecimal("20.00"));
  }

  @Test
  @DisplayName("cartão acima do restante é recusado: nenhum pagamento excede o que falta (BR-05)")
  void rejectsPaymentAboveRemaining() {
    PaymentTotals totals =
        PaymentTotals.of(List.of(cash(new BigDecimal("20.00"), new BigDecimal("20.00"))));

    assertBusinessError(
        () -> totals.requireFitsRemaining(new BigDecimal("30.01"), TOTAL),
        "excede o restante de 30.00");

    PaymentTotals empty = PaymentTotals.of(List.of());
    assertBusinessError(
        () -> empty.requireFitsRemaining(new BigDecimal("60.00"), TOTAL),
        "excede o restante de 50.00");
  }

  @Test
  @DisplayName("valor não positivo não cabe no restante")
  void rejectsNonPositivePayment() {
    PaymentTotals totals = PaymentTotals.of(List.of());

    for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"))) {
      assertBusinessError(
          () -> totals.requireFitsRemaining(amount, TOTAL),
          "valor do pagamento deve ser maior que zero");
    }
  }

  @Test
  @DisplayName("pagamentos e total são obrigatórios")
  void rejectsMissingInputs() {
    PaymentTotals totals = PaymentTotals.of(List.of());

    assertBusinessError(() -> PaymentTotals.of(null), "pagamentos são obrigatórios");
    assertBusinessError(() -> totals.remaining(null), "total da venda é obrigatório");
    assertBusinessError(() -> totals.isFullyPaid(null), "total da venda é obrigatório");
    assertBusinessError(
        () -> totals.requireFitsRemaining(new BigDecimal("10.00"), null),
        "total da venda é obrigatório");
  }

  private static Payment cash(BigDecimal amount, BigDecimal tendered) {
    return new Payment(
        UUID.randomUUID(), SALE_ID, PaymentMethod.CASH, amount, tendered, OPERATOR_ID, CREATED_AT);
  }

  private static Payment card(PaymentMethod method, BigDecimal amount) {
    return new Payment(UUID.randomUUID(), SALE_ID, method, amount, null, OPERATOR_ID, CREATED_AT);
  }

  /** Violação de negócio com o código estável que a API devolve (422). */
  private static void assertBusinessError(ThrowingCallable call, String messageFragment) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.BUSINESS_ERROR);
              assertThat(exception).hasMessageContaining(messageFragment);
            });
  }
}
