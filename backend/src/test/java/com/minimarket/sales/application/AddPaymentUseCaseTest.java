package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.BusinessException;
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
 * Unitários puros do {@link AddPaymentUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão, o agregado é o de verdade e o relógio é fixo — o instante do pagamento é
 * conferido como o caso de uso o gravou. O gravador de auditoria também é dublê: o evento é
 * conferido como o caso de uso o entregou, e a gravação contra o PostgreSQL é coberta pelo {@code
 * AddPaymentIntegrationTest}.
 */
class AddPaymentUseCaseTest {

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
  private static final Instant PAID_AT = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakePaymentStore paymentStore = new FakePaymentStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();
  private final FakeAuthorizationService authorizationService = new FakeAuthorizationService();

  private AddPaymentUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new AddPaymentUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.paymentStore = paymentStore;
    useCase.auditRecorder = auditRecorder;
    useCase.authorizationService = authorizationService;
    useCase.clock = Clock.fixed(PAID_AT, ZoneOffset.UTC);
    authorizationService.granted.add(Permission.PAYMENT_ADD);
    saleStore.sale = openSaleWithItems();
  }

  @Test
  @DisplayName(
      "pagamento parcial deixa a venda com o pago somado, sem troco, e audita PAYMENT_ADDED")
  void addsPartialPaymentAndAudits() {
    Sale sale = useCase.execute(command(PaymentMethod.PIX, "20.00", null));

    assertThat(sale.paidAmount()).isEqualByComparingTo("20.00");
    assertThat(sale.changeAmount()).isEqualByComparingTo("0.00");
    assertThat(sale.total()).as("pagar não mexe no total").isEqualByComparingTo("50.00");
    assertThat(saleStore.updated)
        .as("o agregado com o pago atualizado é o que vai para o banco")
        .isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    Payment stored = paymentStore.inserted.getFirst();
    assertThat(stored.id().version()).as("id do pagamento é UUIDv7").isEqualTo(7);
    assertThat(stored.saleId()).isEqualTo(SALE_ID);
    assertThat(stored.method()).isEqualTo(PaymentMethod.PIX);
    assertThat(stored.amount()).isEqualByComparingTo("20.00");
    assertThat(stored.tenderedAmount()).as("fora do dinheiro não há valor entregue").isNull();
    assertThat(stored.changeAmount()).as("sem troco fora do dinheiro").isEqualByComparingTo("0.00");
    assertThat(stored.createdByUserId())
        .as("o autor é o do comando, que a API monta do OperationContext")
        .isEqualTo(OPERATOR_ID);
    assertThat(stored.createdAt()).as("o instante é o do Clock injetado").isEqualTo(PAID_AT);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("PAYMENT_ADDED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("paymentId", stored.id())
        .containsEntry("method", "PIX")
        .containsEntry("amount", new BigDecimal("20.00"))
        .containsEntry("tenderedAmount", null)
        .containsEntry("changeAmount", new BigDecimal("0.00"))
        .containsEntry("paidAmount", new BigDecimal("20.00"));
  }

  @Test
  @DisplayName("segundo pagamento soma no anterior e completa a venda")
  void addsSecondPaymentToCompleteSale() {
    paymentStore.payments.add(existingPayment(PaymentMethod.PIX, "20.00", null));

    Sale sale = useCase.execute(command(PaymentMethod.CREDIT, "30.00", null));

    assertThat(sale.paidAmount()).isEqualByComparingTo("50.00");
    assertThat(sale.isPaidBy(sale.paidAmount())).as("pago cobre o total (BR-05)").isTrue();
    assertThat(saleStore.updateCount).isEqualTo(1);
    assertThat(paymentStore.inserted).hasSize(1);
    assertThat(auditRecorder.only().details())
        .as("o evento leva o total pago resultante, não só o pagamento desta chamada")
        .containsEntry("paidAmount", new BigDecimal("50.00"));
  }

  @Test
  @DisplayName(
      "dinheiro com troco: troco é tendered − amount e o pago só conta o valor do pagamento")
  void givesChangeOnCashPayment() {
    Sale sale = useCase.execute(command(PaymentMethod.CASH, "30.00", "50.00"));

    Payment stored = paymentStore.inserted.getFirst();
    assertThat(stored.tenderedAmount()).isEqualByComparingTo("50.00");
    assertThat(stored.changeAmount()).as("troco do dinheiro (BR-05)").isEqualByComparingTo("20.00");
    assertThat(sale.paidAmount())
        .as("o valor entregue a mais é troco, não valor pago")
        .isEqualByComparingTo("30.00");
    assertThat(sale.changeAmount()).isEqualByComparingTo("20.00");

    assertThat(auditRecorder.only().details())
        .containsEntry("tenderedAmount", new BigDecimal("50.00"))
        .containsEntry("changeAmount", new BigDecimal("20.00"))
        .containsEntry("paidAmount", new BigDecimal("30.00"));
  }

  @Test
  @DisplayName("pagamento sem troco depois do dinheiro não zera o troco da venda")
  void keepsSaleChangeWhenAnotherPaymentCompletes() {
    paymentStore.payments.add(existingPayment(PaymentMethod.CASH, "30.00", "50.00"));

    Sale sale = useCase.execute(command(PaymentMethod.PIX, "20.00", null));

    assertThat(sale.paidAmount()).isEqualByComparingTo("50.00");
    assertThat(sale.changeAmount())
        .as("o troco é a soma dos pagamentos em dinheiro")
        .isEqualByComparingTo("20.00");
    assertThat(auditRecorder.only().details())
        .as("o changeAmount do evento é o troco do pagamento desta chamada")
        .containsEntry("changeAmount", new BigDecimal("0.00"))
        .containsEntry("paidAmount", new BigDecimal("50.00"));
  }

  @Test
  @DisplayName("dinheiro sem valor entregue lança BusinessException(INVALID_TENDERED_AMOUNT)")
  void rejectsCashWithoutTenderedAmount() {
    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.CASH, "30.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_TENDERED_AMOUNT));

    assertNothingWritten();
  }

  @Test
  @DisplayName("dinheiro com valor entregue menor que o pagamento é recusado")
  void rejectsCashWithTenderedBelowAmount() {
    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.CASH, "30.00", "29.99")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_TENDERED_AMOUNT));

    assertNothingWritten();
  }

  @Test
  @DisplayName("valor entregue fora do dinheiro é recusado: troco só existe em CASH (BR-05)")
  void rejectsTenderedOutsideCash() {
    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.PIX, "20.00", "50.00")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_TENDERED_AMOUNT));

    assertNothingWritten();
  }

  @Test
  @DisplayName("cartão acima do restante é 422 PAYMENT_EXCEEDS_TOTAL, sem gravar pagamento")
  void rejectsPaymentAboveRemaining() {
    paymentStore.payments.add(existingPayment(PaymentMethod.PIX, "20.00", null));

    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.CREDIT, "30.01", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.PAYMENT_EXCEEDS_TOTAL);
              assertThat(error.getMessage()).contains("30.00");
            });

    assertNothingWritten();
    assertThat(saleStore.sale.paidAmount())
        .as("a venda não é tocada na recusa")
        .isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("valor não positivo é 400 VALIDATION_ERROR, sem consultar pagamento")
  void rejectsNonPositiveAmount() {
    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.PIX, "0.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.PIX, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

    assertThat(paymentStore.listedSaleId).as("nem a consulta de pagamentos acontece").isNull();
    assertNothingWritten();
  }

  @Test
  @DisplayName("pagamento sem forma é 400 VALIDATION_ERROR")
  void rejectsMissingMethod() {
    assertThatThrownBy(() -> useCase.execute(command(null, "20.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

    assertNothingWritten();
  }

  @Test
  @DisplayName("sessão sem payment.add lança ForbiddenException(ACCESS_DENIED) sem tocar na venda")
  void deniesWithoutPermission() {
    authorizationService.granted.clear();

    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.PIX, "20.00", null)))
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

    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.PIX, "20.00", null)))
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
                useCase.execute(
                    new AddPaymentCommand(
                        SALE_ID,
                        ANOTHER_REGISTER_ID,
                        PaymentMethod.PIX,
                        new BigDecimal("20.00"),
                        null,
                        OPERATOR_ID)))
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
    saleStore.sale.complete(PAID_AT.minusSeconds(60));

    assertThatThrownBy(() -> useCase.execute(command(PaymentMethod.PIX, "20.00", null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

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

  /** Pagamento já aprovado na venda, como uma chamada anterior do caso de uso o deixou. */
  private static Payment existingPayment(PaymentMethod method, String amount, String tendered) {
    return new Payment(
        UuidCreator.getTimeOrderedEpoch(),
        SALE_ID,
        method,
        new BigDecimal(amount),
        tendered == null ? null : new BigDecimal(tendered),
        OPERATOR_ID,
        PAID_AT.minusSeconds(60));
  }

  private static AddPaymentCommand command(
      PaymentMethod method, String amount, String tenderedAmount) {
    return new AddPaymentCommand(
        SALE_ID,
        CASH_REGISTER_ID,
        method,
        amount == null ? null : new BigDecimal(amount),
        tenderedAmount == null ? null : new BigDecimal(tenderedAmount),
        OPERATOR_ID);
  }

  /** A recusa não pode deixar rastro: nada inserido, nada atualizado, nenhum evento. */
  private void assertNothingWritten() {
    assertThat(paymentStore.inserted).isEmpty();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }
}
