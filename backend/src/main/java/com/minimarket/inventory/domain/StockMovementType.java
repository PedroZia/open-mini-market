package com.minimarket.inventory.domain;

/**
 * Tipo do movimento do ledger de estoque (§5.3): os nomes são os aceitos pelo check constraint de
 * {@code stock_movements.type}. O {@code quantityDelta} é sempre assinado — {@code INITIAL}, {@code
 * PURCHASE_IN} e {@code RETURN_IN} entram positivos, {@code SALE_OUT} e {@code LOSS} negativos e
 * {@code ADJUSTMENT} pode vir dos dois lados —, então o saldo é a soma dos deltas (passo 703).
 */
public enum StockMovementType {
  INITIAL,
  PURCHASE_IN,
  SALE_OUT,
  RETURN_IN,
  ADJUSTMENT,
  LOSS
}
