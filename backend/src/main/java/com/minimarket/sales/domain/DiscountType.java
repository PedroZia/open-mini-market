package com.minimarket.sales.domain;

/**
 * Tipo do desconto da venda (§5.3): os nomes são os aceitos pelo check constraint de {@code
 * sales.discount_type}. O valor informado é sempre interpretado pelo tipo — {@code VALUE} em reais
 * e {@code PERCENT} em percentual do subtotal — e o {@code discount_amount} sai da conta do
 * servidor (BR-03), nunca do cliente. O limite configurado na loja ({@code
 * stores.max_discount_percent}) e a permissão de quem aplica são do caso de uso (passo 810).
 */
public enum DiscountType {
  VALUE,
  PERCENT
}
