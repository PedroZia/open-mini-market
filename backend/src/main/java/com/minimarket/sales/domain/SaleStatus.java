package com.minimarket.sales.domain;

/**
 * Status da venda (§5.3): a venda nasce {@code OPEN} no caixa que a criou (passo 805), conclui
 * quando o pagamento cobre o total (BR-05, passo 906) ou é cancelada se ainda não foi paga (BR-07,
 * passo 813). Os nomes são os aceitos pelo check constraint de {@code sales.status} e o índice
 * parcial {@code ix_sales_store_open} só enxerga as abertas.
 *
 * <p>{@code COMPLETED} e {@code CANCELLED} são estados finais: o agregado {@link Sale} recusa
 * qualquer mutação depois deles (BR-07).
 */
public enum SaleStatus {
  OPEN,
  COMPLETED,
  CANCELLED
}
