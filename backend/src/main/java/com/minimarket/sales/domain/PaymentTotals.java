package com.minimarket.sales.domain;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Totais da venda derivados dos pagamentos (BR-05, BR-12): {@code paidAmount} é a soma dos
 * aprovados e {@code changeAmount} a soma do troco deles — como só o dinheiro tem troco, na prática
 * é a soma dos aprovados em {@code CASH}. Ninguém escreve esses valores de fora; o caso de uso
 * (passo 904) leva a conta pronta para a venda.
 *
 * <p>O que falta cobrar é {@code max(total − paidAmount, 0)}: nenhum pagamento pode exceder o
 * restante, em nenhuma forma — dinheiro a mais é troco no próprio pagamento (valor entregue), nunca
 * valor pago. Quem recusa é {@link #requireFitsRemaining(BigDecimal, BigDecimal)} e o 904 converte
 * a violação no 422 {@code PAYMENT_EXCEEDS_TOTAL}. Record imutável, Java puro — sem JPA, Quarkus,
 * Jackson ou HTTP.
 */
public record PaymentTotals(BigDecimal paidAmount, BigDecimal changeAmount) {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** Dinheiro em escala 2 (§3 do plano), venha de onde vier a soma. */
  public PaymentTotals {
    if (paidAmount == null || changeAmount == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "valor pago e troco são obrigatórios");
    }
    paidAmount = money(paidAmount);
    changeAmount = money(changeAmount);
  }

  /**
   * Soma os pagamentos aprovados: cancelado não conta (BR-05) — nem o valor, nem o troco.
   *
   * @param payments pagamentos da venda, em qualquer ordem
   * @throws BusinessException se a lista não for informada
   */
  public static PaymentTotals of(List<Payment> payments) {
    if (payments == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "pagamentos são obrigatórios");
    }
    BigDecimal paid = zeroMoney();
    BigDecimal change = zeroMoney();
    for (Payment payment : payments) {
      if (payment.status() != PaymentStatus.APPROVED) {
        continue;
      }
      paid = paid.add(payment.amount());
      change = change.add(payment.changeAmount());
    }
    return new PaymentTotals(paid, change);
  }

  /**
   * Quanto falta para cobrir a venda: {@code max(total − paidAmount, 0)} — pago a mais não deixa
   * restante negativo.
   *
   * @param total total da venda
   * @throws BusinessException se o total não for informado
   */
  public BigDecimal remaining(BigDecimal total) {
    return money(requiredTotal(total).subtract(paidAmount).max(BigDecimal.ZERO));
  }

  /**
   * O total pago cobre a venda? Cobre quando é igual ou maior — a conclusão (BR-05) usa isto.
   *
   * @param total total da venda
   * @throws BusinessException se o total não for informado
   */
  public boolean isFullyPaid(BigDecimal total) {
    return paidAmount.compareTo(requiredTotal(total)) >= 0;
  }

  /**
   * Confere que o pagamento cabe no que falta cobrar: nenhum pagamento pode exceder o restante
   * (BR-05), em nenhuma forma — o valor a mais em dinheiro é troco (valor entregue), não valor
   * pago. O caso de uso (passo 904) converte a violação no 422 {@code PAYMENT_EXCEEDS_TOTAL}.
   *
   * @param amount valor do pagamento que se quer registrar, maior que zero
   * @param total total da venda
   * @throws BusinessException se o valor não for positivo ou exceder o restante
   */
  public void requireFitsRemaining(BigDecimal amount, BigDecimal total) {
    if (amount == null || amount.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor do pagamento deve ser maior que zero");
    }
    BigDecimal missing = remaining(total);
    if (money(amount).compareTo(missing) > 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR,
          "pagamento de %s excede o restante de %s da venda".formatted(money(amount), missing));
    }
  }

  private static BigDecimal requiredTotal(BigDecimal total) {
    if (total == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "total da venda é obrigatório");
    }
    return total;
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, ROUNDING);
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE);
  }
}
