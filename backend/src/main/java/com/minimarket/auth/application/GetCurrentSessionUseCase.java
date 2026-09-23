package com.minimarket.auth.application;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Store;
import com.minimarket.users.application.UserAuthState;
import com.minimarket.users.application.UserStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Sessão atual do token autenticado (passo 207). Leitura pura, sem {@code @Transactional}: não
 * grava nada e a sessão já foi validada pelo mecanismo bearer (passo 206) antes de chegar aqui.
 *
 * <p>Recarrega a sessão pelo id que a identidade carrega — a identidade tem usuário, roles,
 * permissões e o id da sessão, mas não a loja, o caixa, o cliente nem o ciclo de vida — e relê o
 * RBAC do banco, como manda o §6.4 (o servidor não confia em cache de token). Sessão revogada entre
 * a autenticação e a leitura responde o 401 genérico de token inválido; usuário inexistente idem.
 */
@ApplicationScoped
public class GetCurrentSessionUseCase {

  @Inject AuthSessionStore sessionStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  /** Sessão, usuário e loja da sessão atual, ou 401 quando a sessão deixou de existir. */
  public CurrentSession execute(UUID sessionId) {
    AuthSessionSnapshot session =
        sessionStore.findActiveById(sessionId).orElseThrow(GetCurrentSessionUseCase::invalidToken);
    UserAuthState user =
        userStore
            .findAuthStateById(session.userId())
            .orElseThrow(GetCurrentSessionUseCase::invalidToken);
    Store store = requireStore(session.storeId());
    return new CurrentSession(
        user.id(),
        user.username(),
        user.displayName(),
        user.roles(),
        user.permissions(),
        store.code(),
        store.name(),
        session.cashRegisterId(),
        session.client(),
        session.expiresAt(),
        session.lastSeenAt());
  }

  /** Sessão revogada e usuário inexistente são o mesmo 401 genérico do passo 206. */
  private static BusinessException invalidToken() {
    return new BusinessException(
        ErrorCode.INVALID_CREDENTIALS, AuthenticateSessionUseCase.INVALID_TOKEN_DETAIL);
  }

  /** A FK de {@code auth_sessions.store_id} garante a loja; ausência é invariante quebrada. */
  private Store requireStore(UUID storeId) {
    return storeLookup
        .findById(storeId)
        .orElseThrow(() -> new IllegalStateException("loja da sessão não existe: " + storeId));
  }
}
