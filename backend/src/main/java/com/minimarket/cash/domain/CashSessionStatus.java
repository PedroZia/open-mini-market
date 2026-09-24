package com.minimarket.cash.domain;

/**
 * Status da sessão de caixa (§5.3): a sessão nasce {@code OPEN} na abertura e vira {@code CLOSED}
 * no fechamento. Os nomes são os aceitos pelo check constraint de {@code cash_sessions.status} e o
 * índice único parcial {@code ux_cash_session_open} só enxerga as abertas — o histórico de fechadas
 * convive no mesmo caixa.
 */
public enum CashSessionStatus {
  OPEN,
  CLOSED
}
