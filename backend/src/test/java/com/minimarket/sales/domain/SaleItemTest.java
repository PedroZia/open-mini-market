package com.minimarket.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link SaleItem}: o snapshot do produto (BR-01) normalizado nas escalas do
 * banco e o total da linha {@code round(quantity × unit_price, 2, HALF_UP)} (BR-02).
 */
class SaleItemTest {

  private static final UUID RICE = UUID.randomUUID();

  @Test
  @DisplayName("total da linha é round(quantity × unit_price, 2, HALF_UP)")
  void computesLineTotalWithHalfUpRounding() {
    SaleItem item = item("1.00", "1.005");

    assertThat(item.lineTotal()).isEqualTo(new BigDecimal("1.01"));
  }

  @Test
  @DisplayName("venda por peso soma centavos normalmente")
  void computesWeightedLineTotal() {
    SaleItem item = item("10.00", "0.335");

    assertThat(item.quantity()).isEqualTo(new BigDecimal("0.335"));
    assertThat(item.lineTotal()).isEqualTo(new BigDecimal("3.35"));
  }

  @Test
  @DisplayName("snapshot normaliza preço e quantidade nas escalas do banco")
  void normalizesSnapshotScales() {
    SaleItem item = item("10.5", "1.5");

    assertThat(item.productId()).isEqualTo(RICE);
    assertThat(item.name()).isEqualTo("Arroz 5kg");
    assertThat(item.unit()).isEqualTo("UN");
    assertThat(item.unitPrice()).isEqualTo(new BigDecimal("10.50"));
    assertThat(item.quantity()).isEqualTo(new BigDecimal("1.500"));
    assertThat(item.barcode()).as("produto sem código é aceito").isNull();
  }

  @Test
  @DisplayName("código de barras do produto entra no snapshot quando existe")
  void keepsBarcodeSnapshot() {
    SaleItem item =
        new SaleItem(
            RICE, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);

    assertThat(item.barcode()).isEqualTo("7891000315507");
  }

  @Test
  @DisplayName("quantidade não positiva ou que arredonda para zero é violação de negócio")
  void rejectsNonPositiveQuantity() {
    for (BigDecimal quantity :
        Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.000"), new BigDecimal("0.0004"))) {
      assertBusinessError(
          () -> new SaleItem(RICE, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), quantity),
          "quantidade deve ser maior que zero");
    }
  }

  @Test
  @DisplayName("preço unitário negativo é violação de negócio")
  void rejectsNegativeUnitPrice() {
    for (BigDecimal unitPrice : Arrays.asList(null, new BigDecimal("-0.01"))) {
      assertBusinessError(
          () -> new SaleItem(RICE, null, "Arroz 5kg", "UN", unitPrice, BigDecimal.ONE),
          "preço unitário deve ser zero ou positivo");
    }
  }

  @Test
  @DisplayName("produto, nome e unidade são obrigatórios")
  void rejectsMissingProductNameAndUnit() {
    assertBusinessError(
        () -> new SaleItem(null, null, "Arroz 5kg", "UN", new BigDecimal("10.00"), BigDecimal.ONE),
        "produto do item é obrigatório");
    assertBusinessError(
        () -> new SaleItem(RICE, null, "  ", "UN", new BigDecimal("10.00"), BigDecimal.ONE),
        "nome do item é obrigatório");
    assertBusinessError(
        () -> new SaleItem(RICE, null, "Arroz 5kg", null, new BigDecimal("10.00"), BigDecimal.ONE),
        "unidade do item é obrigatória");
  }

  @Test
  @DisplayName("mudar a quantidade recalcula o total da linha")
  void changeQuantityRecalculatesLineTotal() {
    SaleItem item = item("10.00", "0.335");

    item.changeQuantity(new BigDecimal("2"));

    assertThat(item.quantity()).isEqualTo(new BigDecimal("2.000"));
    assertThat(item.lineTotal()).isEqualTo(new BigDecimal("20.00"));
    assertBusinessError(
        () -> item.changeQuantity(BigDecimal.ZERO), "quantidade deve ser maior que zero");
  }

  private static SaleItem item(String unitPrice, String quantity) {
    return new SaleItem(
        RICE, null, "Arroz 5kg", "UN", new BigDecimal(unitPrice), new BigDecimal(quantity));
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
