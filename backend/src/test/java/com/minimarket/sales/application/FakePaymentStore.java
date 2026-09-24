package com.minimarket.sales.application;

import com.minimarket.sales.domain.Payment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dublê de {@link PaymentStore} dos unitários do registro (passo 904) e do cancelamento (passo
 * 905): guarda os pagamentos já gravados da venda, o que o caso de uso mandou inserir ou cancelar e
 * a venda consultada. O round-trip real (insert/list/sum/cancel contra PostgreSQL) é exercitado
 * pelo {@code PaymentRepositoryTest} e pelos testes de API — aqui o que importa é o que o caso de
 * uso fez com o agregado e o que repassou à porta.
 */
final class FakePaymentStore implements PaymentStore {

  /** Pagamentos já gravados da venda; o caso de uso os lê para somar os aprovados. */
  final List<Payment> payments = new ArrayList<>();

  /** Pagamentos que o caso de uso mandou inserir, na ordem. */
  final List<Payment> inserted = new ArrayList<>();

  /** Pagamentos que o caso de uso mandou cancelar, na ordem. */
  final List<Payment> cancelled = new ArrayList<>();

  /** Venda da última {@code listBySale}; nula quando não houve consulta. */
  UUID listedSaleId;

  @Override
  public List<Payment> listBySale(UUID saleId) {
    listedSaleId = saleId;
    return payments.stream().filter(payment -> payment.saleId().equals(saleId)).toList();
  }

  /** Insere como o banco faria: a próxima soma da venda enxerga o pagamento. */
  @Override
  public void insert(Payment payment) {
    inserted.add(payment);
    payments.add(payment);
  }

  /** Cancela como o banco faria: o estado do domínio já está no pagamento da lista. */
  @Override
  public void cancel(Payment payment) {
    cancelled.add(payment);
  }

  @Override
  public BigDecimal sumApprovedBySale(UUID saleId) {
    throw new UnsupportedOperationException("sumApprovedBySale não é usado pelos casos de uso");
  }
}
