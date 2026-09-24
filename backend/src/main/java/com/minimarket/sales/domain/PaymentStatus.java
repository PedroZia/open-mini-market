package com.minimarket.sales.domain;

/**
 * Status do pagamento (§5.3): nasce {@link #APPROVED} e só sai daí ao ser cancelado — pagamento não
 * é editável, corrigir é cancelar e registrar outro. Os nomes são os aceitos pelo check constraint
 * de {@code payments.status}, e só os aprovados entram na soma que paga a venda (BR-05).
 */
public enum PaymentStatus {
  APPROVED,
  CANCELLED
}
