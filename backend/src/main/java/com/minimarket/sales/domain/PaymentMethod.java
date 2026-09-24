package com.minimarket.sales.domain;

/**
 * Forma de pagamento da venda (§5.3): os nomes são os aceitos pelo check constraint de {@code
 * payments.method}. O troco só existe em {@link #CASH} (BR-05) — as demais formas são debitadas
 * direto na conta ou no cartão do cliente, sem valor entregue.
 */
public enum PaymentMethod {
  CASH,
  PIX,
  DEBIT,
  CREDIT,
  VOUCHER
}
