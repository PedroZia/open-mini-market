package com.minimarket.cash.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do agregado {@link CashSession}, sem Quarkus e sem banco: o cálculo do saldo
 * esperado e as invariantes de caixa que o §4.4 cobra (BR-10) rodam na memória.
 */
class CashSessionTest {

  @Test
  @DisplayName("saldo esperado soma abertura, venda e suprimento e subtrai a sangria")
  void combinesOpeningSalesSuppliesAndWithdrawals() {
    CashSession session = new CashSession(new BigDecimal("100.00"));

    session.recordSale(new BigDecimal("50.25"));
    session.recordSupply(new BigDecimal("20.00"), "troco");
    session.recordWithdrawal(new BigDecimal("30.10"), "depósito bancário");

    assertThat(session.expectedAmount()).isEqualTo(new BigDecimal("140.15"));
    assertThat(session.openingAmount()).isEqualTo(new BigDecimal("100.00"));
    assertThat(session.status()).isEqualTo(CashSessionStatus.OPEN);
  }

  @Test
  @DisplayName("valores são normalizados para duas casas com arredondamento HALF_UP")
  void roundsAmountsToCents() {
    CashSession session = new CashSession(new BigDecimal("10.5"));

    session.recordSale(new BigDecimal("1.005"));

    assertThat(session.openingAmount()).isEqualTo(new BigDecimal("10.50"));
    assertThat(session.expectedAmount()).isEqualTo(new BigDecimal("11.51"));
  }

  @Test
  @DisplayName("abertura nula ou negativa é violação de negócio")
  void rejectsNegativeOpeningAmount() {
    for (BigDecimal openingAmount : List.of(new BigDecimal("-0.01"), new BigDecimal("-100.00"))) {
      assertBusinessError(
          () -> new CashSession(openingAmount), "valor de abertura deve ser zero ou positivo");
    }
    assertBusinessError(() -> new CashSession(null), "valor de abertura deve ser zero ou positivo");
  }

  @Test
  @DisplayName("movimento com valor zero ou negativo é violação de negócio")
  void rejectsNonPositiveMovementAmount() {
    CashSession session = new CashSession(new BigDecimal("10.00"));

    for (BigDecimal amount : List.of(new BigDecimal("0.00"), new BigDecimal("-1.00"))) {
      assertBusinessError(
          () -> session.recordSupply(amount, "troco"),
          "valor do movimento deve ser maior que zero");
      assertBusinessError(
          () -> session.recordWithdrawal(amount, "depósito"),
          "valor do movimento deve ser maior que zero");
      assertBusinessError(
          () -> session.recordSale(amount), "valor do movimento deve ser maior que zero");
    }

    assertThat(session.expectedAmount())
        .as("movimento recusado não muda o saldo esperado")
        .isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  @DisplayName("sangria sem motivo é violação de negócio")
  void rejectsWithdrawalWithoutReason() {
    CashSession session = new CashSession(new BigDecimal("100.00"));

    for (String reason : List.of("", "   ")) {
      assertBusinessError(
          () -> session.recordWithdrawal(new BigDecimal("10.00"), reason),
          "motivo é obrigatório em sangria e suprimento");
    }
    assertBusinessError(
        () -> session.recordWithdrawal(new BigDecimal("10.00"), null),
        "motivo é obrigatório em sangria e suprimento");
  }

  @Test
  @DisplayName("suprimento sem motivo é violação de negócio")
  void rejectsSupplyWithoutReason() {
    CashSession session = new CashSession(new BigDecimal("100.00"));

    for (String reason : List.of("", "   ")) {
      assertBusinessError(
          () -> session.recordSupply(new BigDecimal("10.00"), reason),
          "motivo é obrigatório em sangria e suprimento");
    }
    assertBusinessError(
        () -> session.recordSupply(new BigDecimal("10.00"), null),
        "motivo é obrigatório em sangria e suprimento");
  }

  @Test
  @DisplayName("venda não exige motivo")
  void acceptsSaleWithoutReason() {
    CashSession session = new CashSession(BigDecimal.ZERO);

    session.recordSale(new BigDecimal("19.90"));

    assertThat(session.expectedAmount()).isEqualTo(new BigDecimal("19.90"));
  }

  @Test
  @DisplayName("fechamento grava contado, observações e diferença contra o esperado")
  void computesClosingDifference() {
    CashSession session = new CashSession(new BigDecimal("100.00"));
    session.recordSale(new BigDecimal("50.00"));
    session.recordWithdrawal(new BigDecimal("10.00"), "depósito");

    session.close(new BigDecimal("135.50"), "faltou troco");

    assertThat(session.status()).isEqualTo(CashSessionStatus.CLOSED);
    assertThat(session.expectedAmount()).isEqualTo(new BigDecimal("140.00"));
    assertThat(session.countedAmount()).isEqualTo(new BigDecimal("135.50"));
    assertThat(session.differenceAmount()).isEqualTo(new BigDecimal("-4.50"));
    assertThat(session.closingNotes()).isEqualTo("faltou troco");
  }

  @Test
  @DisplayName("sobra no fechamento vira diferença positiva")
  void computesPositiveClosingDifference() {
    CashSession session = new CashSession(new BigDecimal("20.00"));

    session.close(new BigDecimal("20.01"), null);

    assertThat(session.differenceAmount()).isEqualTo(new BigDecimal("0.01"));
    assertThat(session.closingNotes()).isNull();
  }

  @Test
  @DisplayName("fechar sessão já fechada é violação de negócio")
  void rejectsClosingTwice() {
    CashSession session = new CashSession(new BigDecimal("10.00"));
    session.close(new BigDecimal("10.00"), "confere");

    assertBusinessError(() -> session.close(new BigDecimal("99.00"), "de novo"), "já está fechada");

    assertThat(session.countedAmount())
        .as("o segundo fechamento não sobrescreve o primeiro")
        .isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  @DisplayName("sessão fechada não aceita movimento")
  void rejectsMovementAfterClose() {
    CashSession session = new CashSession(new BigDecimal("10.00"));
    session.close(new BigDecimal("10.00"), null);

    assertBusinessError(
        () -> session.recordSale(new BigDecimal("5.00")), "fechada não aceita movimento");
    assertBusinessError(
        () -> session.recordSupply(new BigDecimal("5.00"), "troco"),
        "fechada não aceita movimento");
    assertBusinessError(
        () -> session.recordWithdrawal(new BigDecimal("5.00"), "depósito"),
        "fechada não aceita movimento");

    assertThat(session.expectedAmount()).isEqualTo(new BigDecimal("10.00"));
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
