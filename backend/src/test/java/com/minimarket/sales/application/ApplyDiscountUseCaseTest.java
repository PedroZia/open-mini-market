package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Permission;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link ApplyDiscountUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o agregado é o de verdade, para o desconto e os totais serem conferidos como o
 * domínio os calcula. A permissão vem de um dublê de {@code AuthorizationService} — o cenário do
 * OPERADOR sem {@code sale.discount.apply} é coberto sem subir a identidade do Quarkus.
 */
class ApplyDiscountUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final String STORE_CODE = "LOJA-01";
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

  /** Subtotal da venda do cenário: 2 × 20,00 de arroz + 10,00 de feijão. */
  private static final String SUBTOTAL = "50.00";

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();
  private final FakeAuthorizationService authorizationService = new FakeAuthorizationService();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();

  private ApplyDiscountUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new ApplyDiscountUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.storeLookup = storeLookup;
    useCase.auditRecorder = auditRecorder;
    useCase.authorizationService = authorizationService;
    useCase.defaultStoreCode = STORE_CODE;
    authorizationService.granted.add(Permission.SALE_DISCOUNT_APPLY);
    storeLookup.store = store("100.00");
    saleStore.sale = saleWithItems();
  }

  @Test
  @DisplayName(
      "aplica desconto percentual, recalcula os totais e grava SALE_DISCOUNT_APPLIED com o motivo")
  void appliesPercentDiscountRecalculatingTotalsAndAuditing() {
    Sale sale =
        useCase.execute(
            new ApplyDiscountCommand(
                SALE_ID,
                CASH_REGISTER_ID,
                DiscountType.PERCENT,
                new BigDecimal("10"),
                "cliente fidelidade"));

    assertThat(sale.discountType()).isEqualTo(DiscountType.PERCENT);
    assertThat(sale.discountValue()).isEqualByComparingTo("10.00");
    assertThat(sale.discountReason()).isEqualTo("cliente fidelidade");
    assertThat(sale.subtotal())
        .as("o subtotal é dos itens, não do desconto")
        .isEqualByComparingTo(SUBTOTAL);
    assertThat(sale.discountAmount()).as("10% de 50,00").isEqualByComparingTo("5.00");
    assertThat(sale.total()).isEqualByComparingTo("45.00");
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_DISCOUNT_APPLIED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason())
        .as("o motivo do desconto vai no reason do evento")
        .isEqualTo("cliente fidelidade");
    assertThat(event.details())
        .containsEntry("type", "PERCENT")
        .containsEntry("value", new BigDecimal("10.00"))
        .containsEntry("discountAmount", new BigDecimal("5.00"))
        .containsEntry("total", new BigDecimal("45.00"));
  }

  @Test
  @DisplayName("aplica desconto por valor: o valor sai do subtotal e o total é recalculado")
  void appliesValueDiscountRecalculatingTotals() {
    Sale sale =
        useCase.execute(
            new ApplyDiscountCommand(
                SALE_ID,
                CASH_REGISTER_ID,
                DiscountType.VALUE,
                new BigDecimal("12.50"),
                "arredondamento do caixa"));

    assertThat(sale.discountType()).isEqualTo(DiscountType.VALUE);
    assertThat(sale.discountValue()).isEqualByComparingTo("12.50");
    assertThat(sale.discountAmount()).isEqualByComparingTo("12.50");
    assertThat(sale.total()).isEqualByComparingTo("37.50");

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.reason()).isEqualTo("arredondamento do caixa");
    assertThat(event.details())
        .containsEntry("type", "VALUE")
        .containsEntry("value", new BigDecimal("12.50"))
        .containsEntry("discountAmount", new BigDecimal("12.50"))
        .containsEntry("total", new BigDecimal("37.50"));
  }

  @Test
  @DisplayName(
      "desconto percentual acima do limite da loja lança DISCOUNT_LIMIT_EXCEEDED sem gravar")
  void rejectsPercentAboveStoreLimit() {
    storeLookup.store = store("10.00");

    assertLimitExceeded(
        () ->
            useCase.execute(
                new ApplyDiscountCommand(
                    SALE_ID,
                    CASH_REGISTER_ID,
                    DiscountType.PERCENT,
                    new BigDecimal("10.01"),
                    "cliente fidelidade")));

    assertThat(saleStore.sale.discountType()).as("a venda não é tocada na recusa").isNull();
    assertThat(saleStore.sale.total()).isEqualByComparingTo(SUBTOTAL);
  }

  @Test
  @DisplayName("desconto por valor acima do limite da loja lança DISCOUNT_LIMIT_EXCEEDED")
  void rejectsValueAboveStoreLimit() {
    storeLookup.store = store("10.00");

    // 5,01 de 50,00 é 10,02% — passa do limite de 10%.
    assertLimitExceeded(
        () ->
            useCase.execute(
                new ApplyDiscountCommand(
                    SALE_ID,
                    CASH_REGISTER_ID,
                    DiscountType.VALUE,
                    new BigDecimal("5.01"),
                    "cortesia")));

    assertThat(saleStore.sale.discountType()).isNull();
    assertThat(saleStore.sale.total()).isEqualByComparingTo(SUBTOTAL);
  }

  @Test
  @DisplayName("desconto percentual exatamente no limite da loja passa")
  void acceptsPercentAtExactLimit() {
    storeLookup.store = store("10.00");

    Sale sale =
        useCase.execute(
            new ApplyDiscountCommand(
                SALE_ID,
                CASH_REGISTER_ID,
                DiscountType.PERCENT,
                new BigDecimal("10.00"),
                "cliente fidelidade"));

    assertThat(sale.discountAmount()).isEqualByComparingTo("5.00");
    assertThat(saleStore.updateCount).isEqualTo(1);
  }

  @Test
  @DisplayName("desconto por valor exatamente no limite da loja passa")
  void acceptsValueAtExactLimit() {
    storeLookup.store = store("10.00");

    Sale sale =
        useCase.execute(
            new ApplyDiscountCommand(
                SALE_ID, CASH_REGISTER_ID, DiscountType.VALUE, new BigDecimal("5.00"), "cortesia"));

    assertThat(sale.discountAmount())
        .as("5,00 de 50,00 é exatamente 10%")
        .isEqualByComparingTo("5.00");
    assertThat(saleStore.updateCount).isEqualTo(1);
  }

  @Test
  @DisplayName("desconto por valor em venda sem subtotal conta como 100% do limite da loja")
  void countsValueDiscountOnEmptySaleAsHundredPercent() {
    saleStore.sale = openSale();

    storeLookup.store = store("99.99");
    assertLimitExceeded(
        () ->
            useCase.execute(
                new ApplyDiscountCommand(
                    SALE_ID,
                    CASH_REGISTER_ID,
                    DiscountType.VALUE,
                    new BigDecimal("5.00"),
                    "cortesia")));

    storeLookup.store = store("100.00");
    Sale sale =
        useCase.execute(
            new ApplyDiscountCommand(
                SALE_ID, CASH_REGISTER_ID, DiscountType.VALUE, new BigDecimal("5.00"), "cortesia"));

    assertThat(sale.discountAmount())
        .as("desconto maior que o subtotal não deixa total negativo")
        .isEqualByComparingTo("5.00");
    assertThat(sale.total()).isEqualByComparingTo("0.00");
    assertThat(saleStore.updateCount).isEqualTo(1);
    assertThat(auditRecorder.recorded).hasSize(1);
  }

  @Test
  @DisplayName(
      "sessão sem sale.discount.apply lança ForbiddenException(ACCESS_DENIED) sem ler a venda")
  void deniesSessionWithoutDiscountPermission() {
    authorizationService.granted.clear();

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ApplyDiscountCommand(
                        SALE_ID,
                        CASH_REGISTER_ID,
                        DiscountType.VALUE,
                        new BigDecimal("1.00"),
                        "cortesia")))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(Permission.SALE_DISCOUNT_APPLY.code());
            });

    assertThat(storeLookup.lookedUpCode)
        .as("deny by default: sem permissão nem a loja é consultada")
        .isNull();
    assertThat(saleStore.sale.discountType()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("motivo em branco lança BusinessException(VALIDATION_ERROR) sem gravar")
  void rejectsBlankReason() {
    for (String reason : Arrays.asList(null, "", "   ")) {
      assertValidationError(
          () ->
              useCase.execute(
                  new ApplyDiscountCommand(
                      SALE_ID,
                      CASH_REGISTER_ID,
                      DiscountType.VALUE,
                      new BigDecimal("1.00"),
                      reason)),
          "motivo do desconto é obrigatório");
    }

    assertThat(saleStore.sale.discountType()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("tipo ausente lança BusinessException(VALIDATION_ERROR) sem gravar")
  void rejectsMissingType() {
    assertValidationError(
        () ->
            useCase.execute(
                new ApplyDiscountCommand(
                    SALE_ID, CASH_REGISTER_ID, null, new BigDecimal("1.00"), "cortesia")),
        "tipo do desconto é obrigatório");

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("valor ausente ou não positivo lança BusinessException(VALIDATION_ERROR) sem gravar")
  void rejectsMissingOrNonPositiveValue() {
    for (BigDecimal value : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"))) {
      assertValidationError(
          () ->
              useCase.execute(
                  new ApplyDiscountCommand(
                      SALE_ID, CASH_REGISTER_ID, DiscountType.VALUE, value, "cortesia")),
          "valor do desconto");
    }

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
                    new ApplyDiscountCommand(
                        SALE_ID,
                        CASH_REGISTER_ID,
                        DiscountType.VALUE,
                        new BigDecimal("1.00"),
                        "cortesia")))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertThat(saleStore.sale.discountType()).as("o desconto nem entrou no agregado").isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSaleOfAnotherRegister() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ApplyDiscountCommand(
                        SALE_ID,
                        ANOTHER_REGISTER_ID,
                        DiscountType.VALUE,
                        new BigDecimal("1.00"),
                        "cortesia")))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.discountType()).isNull();
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
                    new ApplyDiscountCommand(
                        SALE_ID,
                        CASH_REGISTER_ID,
                        DiscountType.VALUE,
                        new BigDecimal("1.00"),
                        "cortesia")))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Recusa do limite da loja: 422 {@code DISCOUNT_LIMIT_EXCEEDED}, sem gravar nem auditar. */
  private void assertLimitExceeded(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.DISCOUNT_LIMIT_EXCEEDED);
              assertThat(error.getMessage()).contains("limite");
            });

    assertThat(saleStore.updateCount).as("recusa antes de gravar").isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Forma inválida do comando: 400 {@code VALIDATION_ERROR} antes de qualquer gravação. */
  private static void assertValidationError(ThrowingCallable call, String messageFragment) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(error.getMessage()).contains(messageFragment);
            });
  }

  /** Venda aberta com dois itens: subtotal 50,00, como o 808/809 a deixam. */
  private static Sale saleWithItems() {
    Sale sale = openSale();
    sale.addItem(
        RICE_ID, "7891000100103", "Arroz 5kg", "UN", new BigDecimal("20.00"), new BigDecimal("2"));
    sale.addItem(BEANS_ID, null, "Feijão 1kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);
    return sale;
  }

  /** Venda aberta e vazia, como o 805 a cria. */
  private static Sale openSale() {
    return new Sale(
        SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
  }

  /** Loja do cenário com o limite de desconto informado. */
  private static Store store(String maxDiscountPercent) {
    return new Store(STORE_ID, STORE_CODE, "Minimercado", true, new BigDecimal(maxDiscountPercent));
  }

  /**
   * Dublê de {@link StoreLookup}: devolve a loja do cenário pelo código configurado e guarda o
   * código consultado — o teste do 403 prova que sem permissão a consulta nem acontece.
   */
  private static final class FakeStoreLookup implements StoreLookup {

    private Store store;
    private String lookedUpCode;

    @Override
    public Optional<Store> findByCode(String code) {
      lookedUpCode = code;
      return Optional.ofNullable(store).filter(found -> found.code().equals(code));
    }

    @Override
    public Optional<Store> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por ApplyDiscount");
    }
  }
}
