package com.minimarket.reports.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de leitura do resumo de vendas (passo 1212a, §9.3); o adaptador fica em {@code
 * reports.infrastructure} e nada de JPA atravessa esta interface (§2.2). Diferente das portas de
 * escrita, a consulta agrega tabelas de outros módulos ({@code sales} e {@code payments}) e o
 * adaptador as referencia por nome em SQL nativo, sem importar entidade nem repositório alheio —
 * mesmo desenho da consulta de estoque do passo 704.
 *
 * <p>A base de todas as consultas é a mesma: vendas {@code COMPLETED} da loja com {@code
 * completed_at} no período ({@code from} inclusivo, {@code to} exclusivo — a semântica de
 * faturamento, não a de abertura). Leitura pura, sem {@code @Transactional}: não grava nada e a
 * transação é do caso de uso quando existir uma.
 */
public interface SalesSummaryQueryStore {

  /**
   * Totais das vendas concluídas da loja no período: a quantidade de vendas e a soma dos totais.
   */
  SalesTotals totals(UUID storeId, Instant from, Instant to);

  /**
   * Vendas concluídas agrupadas pelo dia UTC do {@code completed_at} ({@code yyyy-MM-dd}), em ordem
   * crescente; dias sem venda não aparecem.
   */
  List<SalesSummaryGroup> byDay(UUID storeId, Instant from, Instant to);

  /**
   * Vendas concluídas agrupadas pelo operador que as abriu ({@code operator_user_id}); operador sem
   * venda no período não aparece.
   */
  List<SalesSummaryGroup> byOperator(UUID storeId, Instant from, Instant to);

  /**
   * Quebra das vendas concluídas por forma de pagamento: a chave é a forma ({@code CASH}, {@code
   * PIX}, {@code DEBIT}, {@code CREDIT}, {@code VOUCHER}), o total soma os pagamentos {@code
   * APPROVED} daquela forma e a contagem é de <em>vendas distintas</em> com pagamento aprovado
   * nela. As cinco formas vêm sempre, na ordem do enum, zero-preenchidas quando não há pagamento —
   * mesmo shape estável do resumo de caixa (passo 909).
   */
  List<SalesSummaryGroup> byPaymentMethod(UUID storeId, Instant from, Instant to);
}
