package com.minimarket.audit.application;

import com.minimarket.shared.application.OperationContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;

/**
 * Grava evento de negócio na mesma transação do caso de uso (passo 303, §7.1/§7.2).
 *
 * <p>Quem chama não repassa ator, sessão, caixa, loja, correlação nem IP à mão: esses campos vêm do
 * {@link OperationContext} da requisição, preenchido pelo filtro a partir da identidade autenticada
 * (passo 302). Fora de um request HTTP — tarefa interna, inicializador — ninguém preenche o
 * contexto: o evento nasce como operação de sistema, com ator nulo e origem {@code SYSTEM}.
 *
 * <p>Sem {@code @Transactional} e sem {@code try/catch}: o recorder roda dentro da transação de
 * quem o chamou (§2.2, regra 6) e uma falha ao gravar precisa derrubar a operação inteira (§7.1).
 */
@ApplicationScoped
public class AuditRecorder {

  /**
   * Contexto da requisição. {@code Instance} porque o bean é {@code @RequestScoped}: fora de um
   * request não existe instância preenchida — só o bean registrado.
   */
  @Inject Instance<OperationContext> operationContext;

  /** Porta de gravação: o adaptador JPA fica em {@code audit.infrastructure}. */
  @Inject AuditEventStore eventStore;

  /**
   * Registra o evento com o contexto do ator. {@code entityType}/{@code entityId} apontam o alvo
   * (nulos quando a ação não tem entidade, ex.: {@code LOGIN_FAILED}) e {@code details} leva só o
   * antes/depois relevante — nulo vira {@code {}}, o mesmo default da coluna (§5.3).
   */
  public void record(
      String action, String entityType, UUID entityId, String reason, Map<String, Object> details) {
    OperationContext actor = actorContext();
    Map<String, Object> detailsOrEmpty = details == null ? Map.of() : details;
    eventStore.insert(
        new NewAuditEvent(
            actor.storeId(),
            actor.userId(),
            actor.username(),
            actor.authSessionId(),
            actor.cashRegisterId(),
            action,
            entityType,
            entityId,
            actor.source(),
            actor.requestId(),
            reason,
            detailsOrEmpty,
            actor.ip()));
  }

  /**
   * Contexto do ator da requisição; sem request context ativo (tarefa de sistema) devolve um
   * contexto vazio, cujo default já é origem {@code SYSTEM} e ator nulo. Bean ausente não vira
   * evento de sistema: seria configuração quebrada, e auditoria que perde o ator em silêncio é pior
   * do que erro na cara.
   */
  private OperationContext actorContext() {
    return Arc.container().requestContext().isActive()
        ? operationContext.get()
        : new OperationContext();
  }
}
