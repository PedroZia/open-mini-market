package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.audit.application.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dublê de {@link AuditRecorder} das operações da venda: guarda o que o caso de uso pediu para
 * gravar, sem CDI e sem banco. A subclasse sobrescreve o overload com a sessão de caixa (passo
 * 1006) — o sem sessão delega para ele —, e o caminho de verdade (contexto + INSERT) é do passo
 * 303, com teste próprio contra PostgreSQL.
 */
final class FakeAuditRecorder extends AuditRecorder {

  /** Eventos na ordem em que o caso de uso os entregou. */
  final List<Event> recorded = new ArrayList<>();

  @Override
  public void record(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details,
      UUID cashSessionId) {
    recorded.add(new Event(action, entityType, entityId, reason, details, cashSessionId));
  }

  /** Único evento do cenário; o teste falha se o caso de uso gravou zero ou dois. */
  Event only() {
    assertThat(recorded).as("eventos de auditoria do cenário").hasSize(1);
    return recorded.getFirst();
  }

  /** Evento como o caso de uso o entregou ao gravador. */
  record Event(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details,
      UUID cashSessionId) {}
}
