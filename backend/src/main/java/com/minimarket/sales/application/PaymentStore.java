package com.minimarket.sales.application;

import com.minimarket.sales.domain.Payment;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Porta de persistência dos pagamentos da venda (passo 903); o adaptador JPA fica em {@code
 * sales.infrastructure}. Só o domínio atravessa: entidade JPA nunca chega aqui.
 *
 * <p>O pagamento é filho da venda (§4.1 do plano), mas tem identidade própria: o id (UUIDv7) chega
 * pronto no {@link Payment}, gerado pelo caso de uso (passo 904), como o da venda — não é o
 * adaptador que o cria. Quem abre a transação é o caso de uso (§2.2, regra 6): o cancelamento vale
 * pelo flush dela, junto do {@code paid_amount} recalculado na venda.
 */
public interface PaymentStore {

  /**
   * Pagamentos da venda na ordem de criação — é a ordem em que o operador os registrou. Venda sem
   * pagamento devolve lista vazia, como id desconhecido.
   */
  List<Payment> listBySale(UUID saleId);

  /**
   * Persiste o pagamento novo, que nasce {@code APPROVED} no domínio; o id é o do próprio {@link
   * Payment} (UUIDv7 do caso de uso).
   */
  void insert(Payment payment);

  /**
   * Leva a linha do pagamento para o estado do domínio — {@code APPROVED → CANCELLED} com o
   * instante do cancelamento — e deixa o flush da transação gravar. Sem update em massa: o contexto
   * de persistência continua com a mesma linha que carregou, sem cópia stale.
   */
  void cancel(Payment payment);

  /**
   * Soma dos pagamentos {@code APPROVED} da venda (BR-05) — cancelado não conta. É o que o caso de
   * uso (passo 904) leva para o {@code paidAmount} e a conclusão confere. Venda sem pagamento (ou
   * só com cancelados) devolve zero, nunca nulo.
   */
  BigDecimal sumApprovedBySale(UUID saleId);
}
