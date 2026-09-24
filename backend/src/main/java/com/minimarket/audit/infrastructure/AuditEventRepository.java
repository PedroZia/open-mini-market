package com.minimarket.audit.infrastructure;

import com.minimarket.audit.application.AuditEventStore;
import com.minimarket.audit.application.NewAuditEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Adaptador JPA da tabela {@code audit_events} (§5.3). Sem {@code @Transactional}: a transação é do
 * caso de uso (§2.2, regra 6) — é ela que faz o evento comitar ou desfazer junto com a operação
 * (§7.1). Nenhum {@code try/catch}: exceção de gravação sobe e derruba a transação de propósito.
 */
@ApplicationScoped
public class AuditEventRepository implements AuditEventStore {

  @Inject EntityManager entityManager;

  /**
   * Traduz o record de aplicação para a entidade e persiste; id e {@code occurred_at} ficam com o
   * banco.
   */
  @Override
  public void insert(NewAuditEvent event) {
    entityManager.persist(
        new AuditEventEntity(
            event.storeId(),
            event.actorUserId(),
            event.actorUsername(),
            event.authSessionId(),
            event.cashSessionId(),
            event.cashRegisterId(),
            event.action(),
            event.entityType(),
            event.entityId(),
            event.source().name(),
            event.requestId(),
            event.reason(),
            event.details(),
            event.ip()));
  }
}
