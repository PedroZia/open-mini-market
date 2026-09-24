package com.minimarket.cash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros de {@link CashSessionAmounts}, sem Quarkus e sem banco: a regra única do saldo
 * esperado do caixa, que o agregado aplica em memória (605) e a consulta da sessão atual aplica
 * sobre os totais do banco (608).
 */
class CashSessionAmountsTest {

  @Test
  @DisplayName("saldo esperado soma abertura, venda e suprimento e subtrai a sangria assinada")
  void combinesOpeningWithSignedTotals() {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.put(CashMovementType.SALE, new BigDecimal("50.25"));
    totals.put(CashMovementType.SUPPLY, new BigDecimal("20.00"));
    totals.put(CashMovementType.WITHDRAWAL, new BigDecimal("-30.10"));

    assertThat(CashSessionAmounts.expectedAmount(new BigDecimal("100.00"), totals))
        .isEqualByComparingTo("140.15");
  }

  @Test
  @DisplayName(
      "OPENING presente no mapa não é contado de novo: a abertura já entra por openingAmount")
  void ignoresOpeningTotalFromMap() {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.put(CashMovementType.OPENING, new BigDecimal("100.00"));
    totals.put(CashMovementType.SALE, new BigDecimal("10.00"));

    assertThat(CashSessionAmounts.expectedAmount(new BigDecimal("100.00"), totals))
        .as("100 de abertura + 10 de venda; o OPENING do ledger é só o registro histórico")
        .isEqualByComparingTo("110.00");
  }

  @Test
  @DisplayName("tipo ausente do mapa conta como zero; mapa vazio devolve a própria abertura")
  void treatsMissingTypesAsZero() {
    Map<CashMovementType, BigDecimal> onlySale = new EnumMap<>(CashMovementType.class);
    onlySale.put(CashMovementType.SALE, new BigDecimal("19.90"));

    assertThat(CashSessionAmounts.expectedAmount(new BigDecimal("10.00"), onlySale))
        .isEqualByComparingTo("29.90");
    assertThat(
            CashSessionAmounts.expectedAmount(
                new BigDecimal("10.00"), new EnumMap<>(CashMovementType.class)))
        .isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("sangria deixa o esperado abaixo da abertura e pode chegar a negativo")
  void subtractsWithdrawal() {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.put(CashMovementType.WITHDRAWAL, new BigDecimal("-150.00"));

    assertThat(CashSessionAmounts.expectedAmount(new BigDecimal("100.00"), totals))
        .as("sangria maior que o caixa é problema da operação, não da conta")
        .isEqualByComparingTo("-50.00");
  }

  @Test
  @DisplayName("resultado sai na escala 2 com arredondamento HALF_UP")
  void roundsToCentsHalfUp() {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.put(CashMovementType.SALE, new BigDecimal("0.005"));

    BigDecimal expected = CashSessionAmounts.expectedAmount(new BigDecimal("1.00"), totals);

    assertThat(expected).isEqualByComparingTo("1.01");
    assertThat(expected.scale()).as("escala do dinheiro (§4.4)").isEqualTo(2);
  }
}
