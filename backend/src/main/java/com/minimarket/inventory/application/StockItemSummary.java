package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Projeção do saldo de um produto para a consulta de estoque (passo 704): o produto com o saldo da
 * loja, sem entidade JPA atravessando para {@code application}. {@code quantity} já vem com o
 * {@code coalesce} da consulta — produto sem linha de saldo responde zero — e {@code minQuantity} é
 * nulo quando o produto não tem mínimo configurado.
 *
 * <p>{@link #lowStock()} é a regra de estoque baixo num lugar só: a lista e o detalhe usam esta
 * derivação e o filtro {@code lowStock} da consulta repete a mesma expressão em SQL.
 */
public record StockItemSummary(
    UUID productId,
    String name,
    String barcode,
    String unit,
    BigDecimal quantity,
    BigDecimal minQuantity) {

  /**
   * Estoque baixo (§5.3): produto com mínimo configurado cujo saldo chegou ao mínimo ou ficou
   * abaixo. Produto sem mínimo nunca é baixo — cláusula igual à do filtro {@code lowStock} da
   * consulta, para o flag e o filtro não divergirem.
   */
  public boolean lowStock() {
    return minQuantity != null && quantity.compareTo(minQuantity) <= 0;
  }
}
