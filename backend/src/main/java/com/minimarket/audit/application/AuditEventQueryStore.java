package com.minimarket.audit.application;

import java.util.List;

/**
 * Porta de leitura do log de auditoria (passo 1001, §7.3); o adaptador JPA fica em {@code
 * audit.infrastructure} e nada de JPA atravessa esta interface (§2.2).
 *
 * <p>Separada da porta de escrita {@link AuditEventStore} de propósito: quem consulta não grava e
 * quem grava (dentro da transação do caso de uso) não consulta — as duas responsabilidades não têm
 * por que compartilhar um contrato.
 *
 * <p>Leitura pura, sem {@code @Transactional}: mesmo desenho do {@code ListSalesUseCase} — não há
 * estado para isolar entre a lista e a contagem.
 */
public interface AuditEventQueryStore {

  /**
   * Página de eventos que casam com os filtros, na ordem por {@code occurred_at} pedida em {@code
   * ascending} e com desempate por {@code id} na mesma direção — todos os eventos de uma transação
   * compartilham o {@code occurred_at} ({@code now()} do PostgreSQL) e sem o desempate a paginação
   * seria instável.
   */
  List<AuditEventSummary> search(AuditEventFilter filter, boolean ascending, int page, int size);

  /**
   * Total de eventos que casam com os filtros de {@link #search} (sem ordenação nem paginação),
   * para o {@code totalItems} e o {@code totalPages} da página.
   */
  long count(AuditEventFilter filter);
}
