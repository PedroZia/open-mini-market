package com.minimarket.audit.infrastructure;

import com.minimarket.audit.application.AuditEventFilter;
import com.minimarket.audit.application.AuditEventQueryStore;
import com.minimarket.audit.application.AuditEventSummary;
import com.minimarket.shared.domain.OperationSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * Adaptador JPA da consulta de auditoria (passo 1001, §7.3). Sem {@code @Transactional}: leitura
 * pura, como o {@code SaleRepository} do histórico — a transação é do caso de uso quando existe uma
 * (§2.2, regra 6).
 *
 * <p>As cláusulas são fixas e montadas só com valores já resolvidos — a direção da ordenação vem do
 * booleano do caso de uso, nunca da string do cliente — e todo valor entra por parâmetro nomeado. O
 * {@code e.id} no fim da ordenação desempata os eventos que compartilham {@code occurred_at} (todos
 * os de uma transação, pelo {@code now()} do PostgreSQL): sem ele a paginação seria instável.
 */
@ApplicationScoped
public class AuditEventQueryRepository implements AuditEventQueryStore {

  @Inject EntityManager entityManager;

  /**
   * {@inheritDoc}
   *
   * <p>O desempate por {@code id} segue a mesma direção do {@code occurred_at}: com {@code desc}, o
   * evento gravado por último no mesmo instante aparece primeiro, que é a ordem da linha do tempo.
   */
  @Override
  public List<AuditEventSummary> search(
      AuditEventFilter filter, boolean ascending, int page, int size) {
    String direction = ascending ? "asc" : "desc";
    TypedQuery<AuditEventEntity> query =
        entityManager
            .createQuery(
                "select e from AuditEventEntity e where "
                    + clauses(filter)
                    + " order by e.occurredAt "
                    + direction
                    + ", e.id "
                    + direction,
                AuditEventEntity.class)
            .setFirstResult(page * size)
            .setMaxResults(size);
    applyFilters(query, filter);
    return query.getResultList().stream().map(AuditEventQueryRepository::toSummary).toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Conta com as mesmas cláusulas da busca ({@link #clauses}): o {@code totalItems} não pode
   * divergir do que {@link #search} devolve para os mesmos filtros.
   */
  @Override
  public long count(AuditEventFilter filter) {
    TypedQuery<Long> query =
        entityManager.createQuery(
            "select count(e) from AuditEventEntity e where " + clauses(filter), Long.class);
    applyFilters(query, filter);
    return query.getSingleResult();
  }

  /**
   * Cláusulas comuns da busca e da contagem: a base fixa {@code 1 = 1} (o log inteiro é o universo
   * da consulta) e os filtros opcionais. Texto em branco é "sem filtro", o mesmo critério da busca
   * de produtos; fonte única para a contagem não divergir da lista.
   */
  private static String clauses(AuditEventFilter filter) {
    return "1 = 1"
        + textClause("e.entityType = :entityType", filter.entityType())
        + (filter.entityId() == null ? "" : " and e.entityId = :entityId")
        + (filter.actorUserId() == null ? "" : " and e.actorUserId = :actorUserId")
        + textClause("e.action = :action", filter.action())
        + (filter.cashSessionId() == null ? "" : " and e.cashSessionId = :cashSessionId")
        + (filter.from() == null ? "" : " and e.occurredAt >= :from")
        + (filter.to() == null ? "" : " and e.occurredAt < :to");
  }

  private static void applyFilters(TypedQuery<?> query, AuditEventFilter filter) {
    if (hasText(filter.entityType())) {
      query.setParameter("entityType", filter.entityType());
    }
    if (filter.entityId() != null) {
      query.setParameter("entityId", filter.entityId());
    }
    if (filter.actorUserId() != null) {
      query.setParameter("actorUserId", filter.actorUserId());
    }
    if (hasText(filter.action())) {
      query.setParameter("action", filter.action());
    }
    if (filter.cashSessionId() != null) {
      query.setParameter("cashSessionId", filter.cashSessionId());
    }
    if (filter.from() != null) {
      query.setParameter("from", filter.from());
    }
    if (filter.to() != null) {
      query.setParameter("to", filter.to());
    }
  }

  private static String textClause(String comparison, String value) {
    return hasText(value) ? " and " + comparison : "";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** Projeção do evento para a porta: nada de entidade JPA na saída. */
  private static AuditEventSummary toSummary(AuditEventEntity entity) {
    return new AuditEventSummary(
        entity.getId(),
        entity.getOccurredAt(),
        entity.getStoreId(),
        entity.getActorUserId(),
        entity.getActorUsername(),
        entity.getAuthSessionId(),
        entity.getCashSessionId(),
        entity.getCashRegisterId(),
        entity.getAction(),
        entity.getEntityType(),
        entity.getEntityId(),
        OperationSource.valueOf(entity.getSource()),
        entity.getRequestId(),
        entity.getReason(),
        entity.getDetails(),
        entity.getIp() == null ? null : entity.getIp().getHostAddress());
  }
}
