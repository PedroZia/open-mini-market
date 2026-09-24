package com.minimarket.auth.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Revoga uma sessão a partir da lista de sessões (passo 210). Uma execução = uma transação (§2.2,
 * regra 6): a sessão do token e a sessão alvo são lidas, o dono é conferido e a revogação grava com
 * o instante do {@code Clock} injetado — nada de {@code now()} espalhado pelo código.
 *
 * <p>As próprias sessões são sempre revogáveis. Sessão ativa de outro usuário só cai com a
 * permissão {@code user.session.revoke} (passo 307b); sem ela a resposta é 404 {@code NOT_FOUND}, o
 * mesmo "não existe" de um id desconhecido, para não vazar a existência da sessão alheia.
 *
 * <p>Revogar a sessão atual é permitido — o cliente cai junto — e revogar uma já revogada ou um id
 * desconhecido é sucesso idempotente, como o logout (passo 208): o token alvo já não autentica e o
 * cliente não tem o que fazer com um erro aqui.
 *
 * <p>Auditoria (passo 304): a revogação que de fato acontece gera o evento {@code SESSION_REVOKED}
 * com a sessão alvo, na mesma transação; o no-op (sessão já revogada ou id desconhecido) não
 * inventa evento. Ator, IP e correlação vêm do {@code OperationContext} do passo 302.
 */
@ApplicationScoped
public class RevokeSessionUseCase {

  /** Motivo gravado em {@code revoked_reason} da sessão derrubada pela lista de sessões. */
  public static final String SESSION_REVOKED_REASON = "SESSION_REVOKED";

  /** Ação da revogação de sessão (§7.2); mesmo verbo do motivo, namespace próprio. */
  private static final String SESSION_REVOKED_ACTION = "SESSION_REVOKED";

  /** Alvo do evento: a sessão revogada, não o usuário. */
  private static final String AUTH_SESSION_ENTITY_TYPE = "AUTH_SESSION";

  /** Mensagem única de sessão alheia ou desconhecida: não revela qual das duas é (§6.3.4). */
  static final String SESSION_NOT_FOUND_DETAIL = "sessão não encontrada";

  @Inject AuthSessionStore sessionStore;

  /** Auditoria do acesso (passo 304): cada revogação efetiva deixa rastro. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o instante da revogação é decisão do caso de uso, não do banco. */
  @Inject Clock clock;

  /**
   * Permissões efetivas de quem pede (passo 305): a revogação de sessão alheia depende de {@code
   * user.session.revoke} (passo 307b).
   */
  @Inject AuthorizationService authorizationService;

  /**
   * Revoga a sessão alvo se ela for do usuário autenticado ou se quem pede tem {@code
   * user.session.revoke}. Sessão de outro usuário sem a permissão lança 404; sessão já revogada ou
   * id desconhecido é no-op.
   */
  @Transactional
  public void execute(UUID currentSessionId, UUID targetSessionId) {
    AuthSessionSnapshot current =
        sessionStore
            .findActiveById(currentSessionId)
            .orElseThrow(RevokeSessionUseCase::invalidToken);
    Optional<AuthSessionSnapshot> target = sessionStore.findActiveById(targetSessionId);
    if (target.isEmpty()) {
      // Já revogada ou desconhecida: o token alvo já não autentica, então não há o que revogar.
      return;
    }
    if (!target.get().userId().equals(current.userId())
        && !authorizationService.has(Permission.USER_SESSION_REVOKE)) {
      throw new NotFoundException(SESSION_NOT_FOUND_DETAIL);
    }
    sessionStore.revoke(targetSessionId, SESSION_REVOKED_REASON, clock.instant());
    auditRecorder.record(
        SESSION_REVOKED_ACTION, AUTH_SESSION_ENTITY_TYPE, targetSessionId, null, null);
  }

  /** Sessão revogada e token desconhecido são o mesmo 401 genérico do passo 206. */
  private static BusinessException invalidToken() {
    return new BusinessException(
        ErrorCode.INVALID_CREDENTIALS, AuthenticateSessionUseCase.INVALID_TOKEN_DETAIL);
  }
}
