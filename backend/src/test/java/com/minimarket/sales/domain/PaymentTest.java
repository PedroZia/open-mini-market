package com.minimarket.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link Payment}, sem Quarkus e sem banco: valor positivo, troco só em dinheiro
 * (BR-05) e a transição de cancelamento (APPROVED → CANCELLED, repetir é no-op).
 */
class PaymentTest {

  private static final UUID PAYMENT_ID = UUID.randomUUID();
  private static final UUID SALE_ID = UUID.randomUUID();
  private static final UUID OPERATOR_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-09-24T12:00:00Z");
  private static final BigDecimal FIFTY = new BigDecimal("50.00");

  @Test
  @DisplayName("pagamento em dinheiro nasce aprovado e calcula o troco do valor entregue")
  void cashPaymentComputesChange() {
    Payment payment = cash(FIFTY, new BigDecimal("100.00"));

    assertThat(payment.id()).isEqualTo(PAYMENT_ID);
    assertThat(payment.saleId()).isEqualTo(SALE_ID);
    assertThat(payment.method()).isEqualTo(PaymentMethod.CASH);
    assertThat(payment.amount()).isEqualTo(FIFTY);
    assertThat(payment.tenderedAmount()).isEqualTo(new BigDecimal("100.00"));
    assertThat(payment.changeAmount()).isEqualTo(new BigDecimal("50.00"));
    assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(payment.createdByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(payment.createdAt()).isEqualTo(CREATED_AT);
    assertThat(payment.cancelledAt()).isNull();
  }

  @Test
  @DisplayName("dinheiro com valor entregue exato não tem troco")
  void cashPaymentWithoutChange() {
    Payment payment = cash(FIFTY, FIFTY);

    assertThat(payment.changeAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
  }

  @Test
  @DisplayName("nas demais formas não há valor entregue nem troco")
  void otherMethodsHaveNoChange() {
    for (PaymentMethod method : PaymentMethod.values()) {
      if (method == PaymentMethod.CASH) {
        continue;
      }
      Payment payment =
          new Payment(PAYMENT_ID, SALE_ID, method, FIFTY, null, OPERATOR_ID, CREATED_AT);

      assertThat(payment.tenderedAmount()).as("%s sem valor entregue", method).isNull();
      assertThat(payment.changeAmount())
          .as("%s sem troco", method)
          .isEqualTo(new BigDecimal("0.00"));
      assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
    }
  }

  @Test
  @DisplayName("valor entregue fora do dinheiro é recusado: troco só existe em CASH (BR-05)")
  void rejectsTenderedOutsideCash() {
    assertBusinessError(
        () ->
            new Payment(
                PAYMENT_ID,
                SALE_ID,
                PaymentMethod.CREDIT,
                FIFTY,
                new BigDecimal("60.00"),
                OPERATOR_ID,
                CREATED_AT),
        "valor entregue só é aceito em pagamento em dinheiro");
  }

  @Test
  @DisplayName("dinheiro sem valor entregue ou com valor insuficiente é recusado")
  void rejectsInvalidTendered() {
    assertBusinessError(() -> cash(FIFTY, null), "pagamento em dinheiro exige o valor entregue");
    for (BigDecimal tendered : Arrays.asList(new BigDecimal("49.99"), new BigDecimal("0.00"))) {
      assertBusinessError(() -> cash(FIFTY, tendered), "valor entregue não cobre o pagamento");
    }
  }

  @Test
  @DisplayName("valor do pagamento não positivo é violação de negócio")
  void rejectsNonPositiveAmount() {
    for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"))) {
      assertBusinessError(
          () ->
              new Payment(
                  PAYMENT_ID, SALE_ID, PaymentMethod.CASH, amount, FIFTY, OPERATOR_ID, CREATED_AT),
          "valor do pagamento deve ser maior que zero");
    }
  }

  @Test
  @DisplayName("identificação incompleta do pagamento é violação de negócio")
  void rejectsIncompleteIdentity() {
    assertBusinessError(
        () -> new Payment(null, SALE_ID, PaymentMethod.CASH, FIFTY, FIFTY, OPERATOR_ID, CREATED_AT),
        "são obrigatórios");
    assertBusinessError(
        () ->
            new Payment(
                PAYMENT_ID, null, PaymentMethod.CASH, FIFTY, FIFTY, OPERATOR_ID, CREATED_AT),
        "são obrigatórios");
    assertBusinessError(
        () -> new Payment(PAYMENT_ID, SALE_ID, null, FIFTY, FIFTY, OPERATOR_ID, CREATED_AT),
        "são obrigatórios");
    assertBusinessError(
        () -> new Payment(PAYMENT_ID, SALE_ID, PaymentMethod.CASH, FIFTY, FIFTY, null, CREATED_AT),
        "são obrigatórios");
    assertBusinessError(
        () -> new Payment(PAYMENT_ID, SALE_ID, PaymentMethod.CASH, FIFTY, FIFTY, OPERATOR_ID, null),
        "são obrigatórios");
  }

  @Test
  @DisplayName("dinheiro normaliza valor e valor entregue na escala 2")
  void normalizesMoneyScale() {
    Payment payment = cash(new BigDecimal("10.5"), new BigDecimal("20"));

    assertThat(payment.amount()).isEqualTo(new BigDecimal("10.50"));
    assertThat(payment.tenderedAmount()).isEqualTo(new BigDecimal("20.00"));
    assertThat(payment.changeAmount()).isEqualTo(new BigDecimal("9.50"));
  }

  @Test
  @DisplayName("cancelar transiciona para CANCELLED e guarda o instante")
  void cancelsPayment() {
    Payment payment = cash(FIFTY, new BigDecimal("100.00"));
    Instant cancelledAt = Instant.parse("2026-09-24T12:10:00Z");

    payment.cancel(cancelledAt);

    assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(payment.cancelledAt()).isEqualTo(cancelledAt);
    assertThat(payment.amount()).as("cancelar não mexe no valor nem no troco").isEqualTo(FIFTY);
    assertThat(payment.changeAmount()).isEqualTo(new BigDecimal("50.00"));
  }

  @Test
  @DisplayName("cancelar de novo é no-op: o primeiro instante é o que vale")
  void secondCancelIsNoop() {
    Payment payment = cash(FIFTY, FIFTY);
    Instant firstCancel = Instant.parse("2026-09-24T12:10:00Z");

    payment.cancel(firstCancel);
    payment.cancel(firstCancel.plusSeconds(60));

    assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(payment.cancelledAt()).isEqualTo(firstCancel);
  }

  @Test
  @DisplayName("cancelar sem instante é violação de negócio e não cancela")
  void rejectsCancelWithoutInstant() {
    Payment payment = cash(FIFTY, FIFTY);

    assertBusinessError(() -> payment.cancel(null), "instante do cancelamento é obrigatório");

    assertThat(payment.status()).as("recusa não cancela").isEqualTo(PaymentStatus.APPROVED);
    assertThat(payment.cancelledAt()).isNull();
  }

  private static Payment cash(BigDecimal amount, BigDecimal tendered) {
    return new Payment(
        PAYMENT_ID, SALE_ID, PaymentMethod.CASH, amount, tendered, OPERATOR_ID, CREATED_AT);
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
