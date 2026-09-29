package com.minimarket.reports.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Totais das vendas concluídas de um período (passo 1212a): a quantidade de vendas e a soma do
 * {@code total} delas, as duas do banco. O ticket médio é derivado daqui — a regra do aceite fica
 * num lugar só e não depende de qual {@code groupBy} o cliente pediu.
 */
public record SalesTotals(long salesCount, BigDecimal total) {

  /** Escala do dinheiro (§4.4). */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /**
   * Ticket médio do período: {@code total / salesCount} na escala 2 com {@code HALF_UP} (§4.4);
   * período sem venda é zero na escala, nunca divisão por zero.
   */
  public BigDecimal ticketAverage() {
    if (salesCount == 0) {
      return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    }
    return total.divide(BigDecimal.valueOf(salesCount), SCALE, ROUNDING);
  }
}
