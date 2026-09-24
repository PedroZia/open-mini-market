package com.minimarket.sales.infrastructure;

import com.minimarket.cash.application.SessionSalesLookup;
import com.minimarket.sales.application.SaleStore;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentStatus;
import com.minimarket.sales.domain.SaleStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador JPA da porta invertida {@link SessionSalesLookup} (passo 909): é por aqui que o caixa
 * enxerga as vendas da sessão sem que o módulo {@code cash} dependa de {@code sales} (§2.2). Sem
 * {@code @Transactional}: as duas consultas são leitura e a transação é do caso de uso que as pede.
 *
 * <p>A existência de venda aberta delega ao {@link SaleStore} — a consulta é da mesma tabela que o
 * adaptador da venda já resolve (passo 803), sem duplicar JPQL. A soma por forma é o cruzamento de
 * {@code payments} com {@code sales}: só pagamento {@code APPROVED} conta (cancelado não entra no
 * que a gaveta recebeu) e venda {@code CANCELLED} fica fora — o pagamento dela é estorno, Fase 13.
 * Venda {@code OPEN} entra: é o que já foi recebido e ainda não concluído, e é o cliente que decide
 * usar a informação no caixa aberto.
 *
 * <p>O mapa sai completo: as cinco formas do {@link PaymentMethod}, na ordem do enum,
 * zero-preenchidas quando não há pagamento naquela forma — o resumo devolve sempre o mesmo shape e
 * o cliente desenha todas. Enquanto dinheiro, tudo na escala 2 com {@code HALF_UP} (§4.4).
 */
@ApplicationScoped
public class SessionSalesLookupRepository implements SessionSalesLookup {

  /** Escala do dinheiro (§4.4), para os zeros que o mapa completa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** Zero da forma sem pagamento, já na escala do dinheiro. */
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

  /** Porta da venda: a checagem de venda aberta é a consulta que o adaptador dela já tem. */
  @Inject SaleStore saleStore;

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public boolean existsOpenByCashSession(UUID cashSessionId) {
    return saleStore.existsOpenByCashSession(cashSessionId);
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, BigDecimal> sumApprovedPaymentsByMethod(UUID cashSessionId) {
    List<Object[]> rows =
        entityManager
            .createQuery(
                "select p.method, sum(p.amount) from PaymentEntity p, SaleEntity s"
                    + " where p.saleId = s.id and s.cashSessionId = :cashSessionId"
                    + " and s.status <> :cancelled and p.status = :approved"
                    + " group by p.method",
                Object[].class)
            .setParameter("cashSessionId", cashSessionId)
            .setParameter("cancelled", SaleStatus.CANCELLED)
            .setParameter("approved", PaymentStatus.APPROVED)
            .getResultList();
    Map<PaymentMethod, BigDecimal> totals = new EnumMap<>(PaymentMethod.class);
    for (Object[] row : rows) {
      totals.put((PaymentMethod) row[0], (BigDecimal) row[1]);
    }
    Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
    for (PaymentMethod method : PaymentMethod.values()) {
      byMethod.put(method.name(), totals.getOrDefault(method, ZERO).setScale(SCALE, ROUNDING));
    }
    return byMethod;
  }
}
