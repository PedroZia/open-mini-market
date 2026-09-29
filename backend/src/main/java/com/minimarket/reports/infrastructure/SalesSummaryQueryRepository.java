package com.minimarket.reports.infrastructure;

import com.minimarket.reports.application.SalesSummaryGroup;
import com.minimarket.reports.application.SalesSummaryQueryStore;
import com.minimarket.reports.application.SalesTotals;
import com.minimarket.sales.domain.PaymentMethod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador da porta de leitura do resumo de vendas (passo 1212a) em SQL nativo <em>somente
 * leitura</em>: as tabelas {@code sales} e {@code payments} são de outro módulo e entram por nome,
 * sem importar entidade nem repositório alheio (§2.2) — mesmo desenho da consulta de estoque do
 * passo 704. A projeção sai em {@link SalesSummaryGroup}/{@link SalesTotals}: entidade JPA nunca
 * chega à {@code application}, e nenhuma escrita passa por aqui.
 *
 * <p>A base é sempre a mesma — vendas {@code COMPLETED} da loja com {@code completed_at} em {@code
 * [from, to)} — montada com parâmetros nomeados; o instante é ligado como {@link OffsetDateTime} em
 * UTC para o JDBC carregar o fuso na comparação com {@code timestamptz} (um {@code Instant} sem
 * fuso dependeria do timezone da sessão). O dia é agrupado por {@code completed_at at time zone
 * 'UTC'} explícito: o relatório é de faturamento e a chave não pode variar com o fuso do servidor.
 *
 * <p>O mapa por forma sai completo: as cinco formas do {@link PaymentMethod}, na ordem do enum,
 * zero-preenchidas quando não há pagamento aprovado naquela forma — o shape estável do passo 909.
 */
@ApplicationScoped
public class SalesSummaryQueryRepository implements SalesSummaryQueryStore {

  /** Escala do dinheiro (§4.4), para os zeros que o mapa de formas completa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** Zero da forma sem pagamento, já na escala do dinheiro. */
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

  /** Base do faturamento: venda concluída da loja com {@code completed_at} no período. */
  private static final String COMPLETED_SALES =
      " from sales s where s.store_id = :storeId and s.status = 'COMPLETED'"
          + " and s.completed_at >= :from and s.completed_at < :to";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public SalesTotals totals(UUID storeId, Instant from, Instant to) {
    Query query =
        entityManager.createNativeQuery(
            "select count(*), coalesce(sum(s.total), 0)" + COMPLETED_SALES);
    bindPeriod(query, storeId, from, to);
    Object[] row = (Object[]) query.getSingleResult();
    return new SalesTotals(((Number) row[0]).longValue(), (BigDecimal) row[1]);
  }

  /** {@inheritDoc} */
  @Override
  public List<SalesSummaryGroup> byDay(UUID storeId, Instant from, Instant to) {
    Query query =
        entityManager.createNativeQuery(
            "select to_char(s.completed_at at time zone 'UTC', 'YYYY-MM-DD') as day, count(*),"
                + " coalesce(sum(s.total), 0)"
                + COMPLETED_SALES
                + " group by day order by day asc");
    bindPeriod(query, storeId, from, to);
    return groupsOf(query.getResultList());
  }

  /** {@inheritDoc} */
  @Override
  public List<SalesSummaryGroup> byOperator(UUID storeId, Instant from, Instant to) {
    Query query =
        entityManager.createNativeQuery(
            "select s.operator_user_id, count(*), coalesce(sum(s.total), 0)"
                + COMPLETED_SALES
                + " group by s.operator_user_id order by s.operator_user_id asc");
    bindPeriod(query, storeId, from, to);
    return groupsOf(query.getResultList());
  }

  /** {@inheritDoc} */
  @Override
  public List<SalesSummaryGroup> byPaymentMethod(UUID storeId, Instant from, Instant to) {
    Query query =
        entityManager.createNativeQuery(
            "select p.method, count(distinct s.id), coalesce(sum(p.amount), 0)"
                + " from sales s join payments p on p.sale_id = s.id"
                + " where s.store_id = :storeId and s.status = 'COMPLETED'"
                + " and s.completed_at >= :from and s.completed_at < :to"
                + " and p.status = 'APPROVED'"
                + " group by p.method");
    bindPeriod(query, storeId, from, to);
    return paymentMethodGroups(query.getResultList());
  }

  /** Parâmetros da base: a loja e o período, o instante já com o fuso UTC explícito. */
  private static void bindPeriod(Query query, UUID storeId, Instant from, Instant to) {
    query.setParameter("storeId", storeId);
    query.setParameter("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
    query.setParameter("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
  }

  /** Projeção das linhas (chave, quantidade, total) para a porta: nada de entidade JPA na saída. */
  @SuppressWarnings("unchecked")
  private static List<SalesSummaryGroup> groupsOf(List<?> rows) {
    return ((List<Object[]>) rows)
        .stream()
            .map(
                row ->
                    new SalesSummaryGroup(
                        String.valueOf(row[0]), ((Number) row[1]).longValue(), (BigDecimal) row[2]))
            .toList();
  }

  /**
   * Quebra por forma com o shape estável: o que o banco devolveu (só formas com pagamento aprovado)
   * é completado com as cinco formas do enum, na ordem dele.
   */
  private static List<SalesSummaryGroup> paymentMethodGroups(List<?> rows) {
    Map<PaymentMethod, SalesSummaryGroup> totalsByMethod = new EnumMap<>(PaymentMethod.class);
    ((List<Object[]>) rows)
        .forEach(
            row -> {
              PaymentMethod method = PaymentMethod.valueOf((String) row[0]);
              totalsByMethod.put(
                  method,
                  new SalesSummaryGroup(
                      method.name(), ((Number) row[1]).longValue(), (BigDecimal) row[2]));
            });
    List<SalesSummaryGroup> groups = new ArrayList<>();
    for (PaymentMethod method : PaymentMethod.values()) {
      groups.add(
          totalsByMethod.getOrDefault(method, new SalesSummaryGroup(method.name(), 0, ZERO)));
    }
    return groups;
  }
}
