package com.minimarket.auth.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * Lista as sessões ativas do usuário autenticado (passo 210). Leitura pura, sem
 * {@code @Transactional}: não grava nada e a sessão já foi validada pelo mecanismo bearer (passo
 * 206) antes de chegar aqui.
 *
 * <p>O dono da lista é sempre o usuário da sessão autenticada — nunca um id vindo do cliente, que
 * não teria como listar sessão alheia (§6.2). Sessão revogada entre a autenticação e a leitura
 * responde o 401 genérico de token inválido, como o {@code /auth/me}. A ordem (último uso mais
 * recente primeiro) é da porta {@link AuthSessionStore}.
 */
@ApplicationScoped
public class ListUserSessionsUseCase {

  @Inject AuthSessionStore sessionStore;

  /** Sessões vivas do dono da sessão autenticada, da mais recente para a mais antiga. */
  public List<UserSessionSummary> execute(UUID currentSessionId) {
    AuthSessionSnapshot current =
        sessionStore
            .findActiveById(currentSessionId)
            .orElseThrow(ListUserSessionsUseCase::invalidToken);
    return sessionStore.listActiveByUser(current.userId());
  }

  /** Sessão revogada e token desconhecido são o mesmo 401 genérico do passo 206. */
  private static BusinessException invalidToken() {
    return new BusinessException(
        ErrorCode.INVALID_CREDENTIALS, AuthenticateSessionUseCase.INVALID_TOKEN_DETAIL);
  }
}
