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
 * Unitários puros do agregado {@link Sale}, sem Quarkus e sem banco: totais recalculados dos itens
 * (BR-02), desconto por valor e percentual (BR-03, incluindo o que zeraria o total), snapshot de
 * preço (BR-01) e imutabilidade depois de concluída (BR-07).
 */
class SaleTest {

  private static final UUID SALE_ID = UUID.randomUUID();
  private static final UUID STORE_ID = UUID.randomUUID();
  private static final UUID CASH_SESSION_ID = UUID.randomUUID();
  private static final UUID CASH_REGISTER_ID = UUID.randomUUID();
  private static final UUID OPERATOR_ID = UUID.randomUUID();
  private static final UUID RICE = UUID.randomUUID();
  private static final UUID BEANS = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-09-24T12:00:00Z");

  @Test
  @DisplayName("venda nova nasce aberta, sem cliente, sem itens e com totais zerados")
  void startsOpenAndEmpty() {
    Sale sale = openSale();

    assertThat(sale.id()).isEqualTo(SALE_ID);
    assertThat(sale.storeId()).isEqualTo(STORE_ID);
    assertThat(sale.number()).isEqualTo(42L);
    assertThat(sale.cashSessionId()).isEqualTo(CASH_SESSION_ID);
    assertThat(sale.cashRegisterId()).isEqualTo(CASH_REGISTER_ID);
    assertThat(sale.operatorUserId()).isEqualTo(OPERATOR_ID);
    assertThat(sale.customerId()).isNull();
    assertThat(sale.notes()).isEqualTo("venda de teste");
    assertThat(sale.createdAt()).isEqualTo(CREATED_AT);
    assertThat(sale.status()).isEqualTo(SaleStatus.OPEN);
    assertThat(sale.items()).isEmpty();
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.itemCount()).isZero();
    assertThat(sale.completedAt()).isNull();
  }

  @Test
  @DisplayName("totais de um item saem do preço snapshot × quantidade")
  void totalsSingleItem() {
    Sale sale = openSale();

    sale.addItem(
        RICE, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));

    assertThat(sale.items())
        .singleElement()
        .satisfies(item -> assertThat(item.lineTotal()).isEqualTo(new BigDecimal("50.00")));
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("50.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("50.00"));
    assertThat(sale.itemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("subtotal soma as linhas de vários itens")
  void totalsSumItems() {
    Sale sale = openSale();

    sale.addItem(
        RICE, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));
    sale.addItem(BEANS, null, "Feijão 1kg", "UN", new BigDecimal("4.50"), BigDecimal.ONE);

    assertThat(sale.items()).hasSize(2);
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("54.50"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("54.50"));
    assertThat(sale.itemCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("quantidade é fracionada na venda por peso: linha arredonda HALF_UP")
  void roundsFractionalQuantity() {
    Sale sale = openSale();

    sale.addItem(RICE, null, "Banana prata", "KG", new BigDecimal("1.00"), new BigDecimal("1.005"));

    assertThat(sale.items())
        .singleElement()
        .satisfies(item -> assertThat(item.lineTotal()).isEqualTo(new BigDecimal("1.01")));
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("1.01"));
  }

  @Test
  @DisplayName("item repetido soma a quantidade no item existente")
  void sumsQuantityOfRepeatedProduct() {
    Sale sale = openSale();

    sale.addItem(RICE, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    sale.addItem(
        RICE, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));

    assertThat(sale.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.quantity()).isEqualTo(new BigDecimal("3.000"));
              assertThat(item.lineTotal()).isEqualTo(new BigDecimal("75.00"));
            });
    assertThat(sale.itemCount()).isEqualTo(1);
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("75.00"));
  }

  @Test
  @DisplayName("preço do produto alterado depois não muda o item já incluído (BR-01)")
  void keepsPriceSnapshotOfExistingItem() {
    Sale sale = openSale();
    sale.addItem(RICE, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);

    BigDecimal changedProductPrice = new BigDecimal("31.90");
    sale.addItem(RICE, "7891000315507", "Arroz 5kg", "UN", changedProductPrice, BigDecimal.ONE);

    assertThat(sale.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.unitPrice()).isEqualTo(new BigDecimal("25.00"));
              assertThat(item.quantity()).isEqualTo(new BigDecimal("2.000"));
            });
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("50.00"));
  }

  @Test
  @DisplayName("changeQuantity recalcula a linha e os totais")
  void changeQuantityRecalculatesTotals() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), new BigDecimal("2"));

    sale.changeQuantity(RICE, new BigDecimal("1.5"));

    assertThat(sale.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.quantity()).isEqualTo(new BigDecimal("1.500"));
              assertThat(item.lineTotal()).isEqualTo(new BigDecimal("15.00"));
            });
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("15.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("15.00"));
    assertThat(sale.itemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("removeItem recalcula e a venda sem itens volta a zero")
  void removeItemRecalculatesTotals() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), new BigDecimal("2"));
    sale.addItem(BEANS, null, "Feijão 1kg", "UN", new BigDecimal("4.50"), BigDecimal.ONE);

    sale.removeItem(BEANS);

    assertThat(sale.items())
        .singleElement()
        .satisfies(item -> assertThat(item.productId()).isEqualTo(RICE));
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("20.00"));
    assertThat(sale.itemCount()).isEqualTo(1);

    sale.removeItem(RICE);

    assertThat(sale.items()).isEmpty();
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.itemCount()).isZero();
  }

  @Test
  @DisplayName("desconto percentual incide sobre o subtotal (BR-03)")
  void appliesPercentDiscount() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("16.67"), new BigDecimal("2"));

    sale.applyDiscount(DiscountType.PERCENT, new BigDecimal("10"), "cliente fidelidade");

    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("33.34"));
    assertThat(sale.discountType()).isEqualTo(DiscountType.PERCENT);
    assertThat(sale.discountValue()).isEqualTo(new BigDecimal("10.00"));
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("3.33"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("30.01"));
    assertThat(sale.discountReason()).isEqualTo("cliente fidelidade");
  }

  @Test
  @DisplayName("desconto por valor é subtraído do subtotal")
  void appliesValueDiscount() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));

    sale.applyDiscount(DiscountType.VALUE, new BigDecimal("4.5"), "arredondamento do caixa");

    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("50.00"));
    assertThat(sale.discountValue()).isEqualTo(new BigDecimal("4.50"));
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("4.50"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("45.50"));
  }

  @Test
  @DisplayName("desconto maior que o subtotal zera o total, nunca deixa negativo (BR-02)")
  void discountNeverMakesTotalNegative() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);

    sale.applyDiscount(DiscountType.VALUE, new BigDecimal("25.00"), "cortesia");

    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("25.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("0.00"));

    sale.applyDiscount(DiscountType.PERCENT, new BigDecimal("150"), "cortesia");

    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("15.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  @DisplayName("desconto sem tipo ou com valor não positivo é violação de negócio")
  void rejectsInvalidDiscount() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);

    assertBusinessError(
        () -> sale.applyDiscount(null, new BigDecimal("5.00"), "cortesia"),
        "tipo de desconto é obrigatório");
    for (BigDecimal value : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"))) {
      assertBusinessError(
          () -> sale.applyDiscount(DiscountType.VALUE, value, "cortesia"),
          "valor do desconto deve ser maior que zero");
    }

    assertThat(sale.discountType()).as("desconto recusado não muda a venda").isNull();
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  @DisplayName("removeDiscount zera o desconto e o total volta ao subtotal (BR-02/BR-03)")
  void removeDiscountRestoresSubtotal() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));
    sale.applyDiscount(DiscountType.PERCENT, new BigDecimal("10"), "cliente fidelidade");

    sale.removeDiscount();

    assertThat(sale.discountType()).as("tipo, valor e motivo voltam a nulo").isNull();
    assertThat(sale.discountValue()).isNull();
    assertThat(sale.discountReason()).isNull();
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.subtotal())
        .as("o subtotal não muda: depende só dos itens")
        .isEqualTo(new BigDecimal("50.00"));
    assertThat(sale.total())
        .as("sem desconto o total é o subtotal")
        .isEqualTo(new BigDecimal("50.00"));
  }

  @Test
  @DisplayName("removeDiscount em venda sem desconto é no-op de estado")
  void removeDiscountWithoutDiscountChangesNothing() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));

    sale.removeDiscount();

    assertThat(sale.discountType()).isNull();
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("0.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("50.00"));
    assertThat(sale.itemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("venda concluída não aceita removeDiscount (BR-07)")
  void rejectsRemoveDiscountAfterCompletion() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);
    sale.applyDiscount(DiscountType.VALUE, new BigDecimal("1.00"), "cortesia");
    sale.complete(Instant.parse("2026-09-24T12:30:00Z"));

    assertBusinessError(sale::removeDiscount, "não aceita alteração");

    assertThat(sale.discountType())
        .as("o desconto da venda concluída fica onde está")
        .isEqualTo(DiscountType.VALUE);
    assertThat(sale.discountAmount()).isEqualTo(new BigDecimal("1.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("9.00"));
  }

  @Test
  @DisplayName("linkCustomer vincula o cliente e vincular outro substitui o anterior")
  void linksCustomerReplacingThePreviousOne() {
    Sale sale = openSale();
    UUID ana = UUID.randomUUID();
    UUID bruno = UUID.randomUUID();

    sale.linkCustomer(ana);

    assertThat(sale.customerId()).isEqualTo(ana);
    assertThat(sale.total())
        .as("o vínculo não mexe em item nem total")
        .isEqualTo(new BigDecimal("0.00"));

    sale.linkCustomer(bruno);

    assertThat(sale.customerId()).as("a venda guarda um cliente por vez").isEqualTo(bruno);
  }

  @Test
  @DisplayName("unlinkCustomer devolve a venda anônima e sem vínculo é no-op de estado")
  void unlinksCustomer() {
    Sale sale = openSale();

    sale.unlinkCustomer();

    assertThat(sale.customerId()).as("venda sem cliente não muda no desvínculo").isNull();

    sale.linkCustomer(UUID.randomUUID());
    sale.unlinkCustomer();

    assertThat(sale.customerId()).isNull();
    assertThat(sale.itemCount()).isZero();
  }

  @Test
  @DisplayName("linkCustomer sem cliente é violação de negócio")
  void rejectsLinkWithoutCustomer() {
    Sale sale = openSale();

    assertBusinessError(() -> sale.linkCustomer(null), "cliente é obrigatório");
    assertThat(sale.customerId()).isNull();
  }

  @Test
  @DisplayName("venda concluída não aceita linkCustomer nem unlinkCustomer (BR-07)")
  void rejectsCustomerMutationAfterCompletion() {
    Sale sale = openSale();
    UUID ana = UUID.randomUUID();
    sale.linkCustomer(ana);
    sale.complete(Instant.parse("2026-09-24T12:30:00Z"));

    assertBusinessError(() -> sale.linkCustomer(UUID.randomUUID()), "não aceita alteração");
    assertBusinessError(sale::unlinkCustomer, "não aceita alteração");

    assertThat(sale.customerId()).as("o cliente da venda concluída fica onde está").isEqualTo(ana);
  }

  @Test
  @DisplayName("recalculate é idempotente")
  void recalculateIsIdempotent() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("16.67"), new BigDecimal("2"));
    sale.applyDiscount(DiscountType.PERCENT, new BigDecimal("10"), "cliente fidelidade");
    sale.recalculate();

    BigDecimal subtotal = sale.subtotal();
    BigDecimal discountAmount = sale.discountAmount();
    BigDecimal total = sale.total();
    int itemCount = sale.itemCount();

    sale.recalculate();
    sale.recalculate();

    assertThat(sale.subtotal()).isEqualTo(subtotal);
    assertThat(sale.discountAmount()).isEqualTo(discountAmount);
    assertThat(sale.total()).isEqualTo(total);
    assertThat(sale.itemCount()).isEqualTo(itemCount);
  }

  @Test
  @DisplayName("conclusão grava o instante e muda o status para COMPLETED")
  void completesOpenSale() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);
    Instant completedAt = Instant.parse("2026-09-24T12:30:00Z");

    sale.complete(completedAt);

    assertThat(sale.status()).isEqualTo(SaleStatus.COMPLETED);
    assertThat(sale.completedAt()).isEqualTo(completedAt);
    assertThat(sale.total()).as("concluir não mexe nos totais").isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  @DisplayName("concluir sem instante ou duas vezes é violação de negócio")
  void rejectsInvalidCompletion() {
    Sale sale = openSale();
    Instant completedAt = Instant.parse("2026-09-24T12:30:00Z");

    assertBusinessError(() -> sale.complete(null), "instante de conclusão é obrigatório");

    sale.complete(completedAt);

    assertBusinessError(
        () -> sale.complete(completedAt.plusSeconds(60)), "não está aberta para ser concluída");
    assertThat(sale.completedAt())
        .as("a segunda conclusão não sobrescreve a primeira")
        .isEqualTo(completedAt);
  }

  @Test
  @DisplayName("venda concluída é imutável: item e desconto são recusados (BR-07)")
  void rejectsMutationAfterCompletion() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), new BigDecimal("2"));
    sale.complete(Instant.parse("2026-09-24T12:30:00Z"));

    assertBusinessError(
        () -> sale.addItem(BEANS, null, "Feijão 1kg", "UN", new BigDecimal("8.00"), BigDecimal.ONE),
        "não aceita alteração");
    assertBusinessError(
        () -> sale.changeQuantity(RICE, new BigDecimal("3.000")), "não aceita alteração");
    assertBusinessError(() -> sale.removeItem(RICE), "não aceita alteração");
    assertBusinessError(
        () -> sale.applyDiscount(DiscountType.VALUE, new BigDecimal("1.00"), "cortesia"),
        "não aceita alteração");

    assertThat(sale.items()).hasSize(1);
    assertThat(sale.subtotal()).isEqualTo(new BigDecimal("20.00"));
    assertThat(sale.total()).isEqualTo(new BigDecimal("20.00"));
    assertThat(sale.itemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("isPaidBy compara o valor pago com o total da venda")
  void comparesPaidAmountWithTotal() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), new BigDecimal("2"));

    assertThat(sale.isPaidBy(new BigDecimal("20.00"))).as("pagou o total").isTrue();
    assertThat(sale.isPaidBy(new BigDecimal("20.01"))).as("pagou a mais").isTrue();
    assertThat(sale.isPaidBy(new BigDecimal("19.99"))).as("pagou a menos").isFalse();
    assertBusinessError(() -> sale.isPaidBy(null), "valor pago é obrigatório");
  }

  @Test
  @DisplayName("quantidade não positiva é violação de negócio e não entra na venda")
  void rejectsNonPositiveQuantity() {
    Sale sale = openSale();

    for (BigDecimal quantity : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.000"))) {
      assertBusinessError(
          () -> sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), quantity),
          "quantidade deve ser maior que zero");
    }
    assertThat(sale.items()).isEmpty();

    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);

    assertBusinessError(
        () -> sale.changeQuantity(RICE, BigDecimal.ZERO), "quantidade deve ser maior que zero");
    assertThat(sale.items())
        .singleElement()
        .satisfies(item -> assertThat(item.quantity()).isEqualTo(new BigDecimal("1.000")));
  }

  @Test
  @DisplayName("produto fora da venda é violação de negócio em changeQuantity e removeItem")
  void rejectsUnknownProduct() {
    Sale sale = openSale();
    sale.addItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE);

    assertBusinessError(
        () -> sale.changeQuantity(BEANS, BigDecimal.ONE), "item não encontrado na venda");
    assertBusinessError(() -> sale.removeItem(BEANS), "item não encontrado na venda");
    assertThat(sale.items()).hasSize(1);
  }

  @Test
  @DisplayName("venda sem identificação completa ou com número não positivo é violação de negócio")
  void rejectsIncompleteIdentity() {
    assertBusinessError(
        () ->
            new Sale(
                null,
                STORE_ID,
                1L,
                CASH_SESSION_ID,
                CASH_REGISTER_ID,
                OPERATOR_ID,
                null,
                CREATED_AT),
        "são obrigatórios");
    assertBusinessError(
        () ->
            new Sale(
                SALE_ID, STORE_ID, 1L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, null),
        "são obrigatórios");
    assertBusinessError(
        () ->
            new Sale(
                SALE_ID,
                STORE_ID,
                0L,
                CASH_SESSION_ID,
                CASH_REGISTER_ID,
                OPERATOR_ID,
                null,
                CREATED_AT),
        "número da venda deve ser positivo");
  }

  @Test
  @DisplayName("a lista de itens é uma cópia imutável do agregado")
  void exposesItemsAsImmutableCopy() {
    Sale sale = openSale();

    assertThatThrownBy(() -> sale.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static Sale openSale() {
    return new Sale(
        SALE_ID,
        STORE_ID,
        42L,
        CASH_SESSION_ID,
        CASH_REGISTER_ID,
        OPERATOR_ID,
        "venda de teste",
        CREATED_AT);
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
