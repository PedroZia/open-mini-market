package com.minimarket.auth.application;

import com.minimarket.audit.application.AuditRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.UUID;

/**
 * Encerra a sessão atual (passo 208). Uma execução = uma transação (§2.2, regra 6): a sessão do
 * token apresentado é revogada com o motivo {@code LOGOUT} e o instante do {@code Clock} injetado —
 * nada de {@code now()} espalhado pelo código.
 *
 * <p>Só a sessão atual é revogada; derrubar as outras sessões do usuário é dos passos 213/214. A
 * revogação é idempotente: se a sessão já não estiver ativa (corrida entre a autenticação e este
 * caso de uso), o logout é sucesso — o cliente não tem o que fazer com um erro aqui, e o token já
 * deixou de autenticar.
 *
 * <p>Auditoria (passo 304): o evento {@code LOGOUT} aponta a sessão encerrada e sai na mesma
 * transação da revogação; ator, IP e correlação vêm do {@code OperationContext} que o filtro
 * preencheu (passo 302).
 */
@ApplicationScoped
public class LogoutUseCase {

  /** Motivo gravado em {@code revoked_reason} da sessão encerrada pelo próprio dono. */
  public static final String LOGOUT_REASON = "LOGOUT";

  /** Ação do encerramento de sessão (§7.2); mesmo verbo do motivo, namespace próprio. */
  private static final String LOGOUT_ACTION = "LOGOUT";

  /** Alvo do evento: a sessão revogada, não o usuário. */
  private static final String AUTH_SESSION_ENTITY_TYPE = "AUTH_SESSION";

  @Inject AuthSessionStore sessionStore;

  /** Auditoria do acesso (passo 304): o logout fica registrado como o login. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o instante da revogação é decisão do caso de uso, não do banco. */
  @Inject Clock clock;

  /** Revoga a sessão da identidade autenticada; sessão já revogada é no-op. */
  @Transactional
  public void execute(UUID sessionId) {
    sessionStore.revoke(sessionId, LOGOUT_REASON, clock.instant());
    auditRecorder.record(LOGOUT_ACTION, AUTH_SESSION_ENTITY_TYPE, sessionId, null, null);
  }
}
