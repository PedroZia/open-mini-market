package com.minimarket.audit.application;

/**
 * Porta de gravação do log de auditoria (§7.3); o adaptador JPA fica em {@code
 * audit.infrastructure} e nada de JPA atravessa esta interface (§2.2).
 *
 * <p>Não abre transação própria: é chamada de dentro da transação do caso de uso — é isso que faz a
 * operação e o evento comitarem juntos ou nenhum dos dois (§7.1, §2.2 regra 6).
 */
public interface AuditEventStore {

  /**
   * Insere o evento. Sem {@code try/catch} nenhum no caminho: falha ao gravar sobe e derruba a
   * transação — operação de dinheiro/estoque sem rastro não pode ser considerada bem-sucedida.
   */
  void insert(NewAuditEvent event);
}
