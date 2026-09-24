package com.minimarket.cash.domain;

/**
 * Tipo do movimento de dinheiro da sessão (§5.3): os nomes são os aceitos pelo check constraint de
 * {@code cash_movements.type}. O {@code amount} é sempre assinado — {@code OPENING}, {@code SALE} e
 * {@code SUPPLY} entram positivos e {@code WITHDRAWAL} negativo —, então o saldo esperado é a soma
 * dos movimentos com a abertura (passo 605).
 */
public enum CashMovementType {
  OPENING,
  SALE,
  WITHDRAWAL,
  SUPPLY
}
