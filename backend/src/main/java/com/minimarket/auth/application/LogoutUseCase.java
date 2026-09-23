package com.minimarket.auth.application;

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
 */
@ApplicationScoped
public class LogoutUseCase {

  /** Motivo gravado em {@code revoked_reason} da sessão encerrada pelo próprio dono. */
  public static final String LOGOUT_REASON = "LOGOUT";

  @Inject AuthSessionStore sessionStore;

  /** Relógio da aplicação: o instante da revogação é decisão do caso de uso, não do banco. */
  @Inject Clock clock;

  /** Revoga a sessão da identidade autenticada; sessão já revogada é no-op. */
  @Transactional
  public void execute(UUID sessionId) {
    sessionStore.revoke(sessionId, LOGOUT_REASON, clock.instant());
  }
}
