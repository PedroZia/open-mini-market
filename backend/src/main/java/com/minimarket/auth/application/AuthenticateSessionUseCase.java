package com.minimarket.auth.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.UserAuthState;
import com.minimarket.users.application.UserStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Autenticação de uma requisição pelo token de sessão (passo 206). Uma execução = uma transação
 * (§2.2, regra 6): a sessão é lida, a expiração absoluta conferida e o RBAC efetivo relido do banco
 * — o servidor revalida as permissões a cada request em vez de confiar em cache de token (§6.4).
 *
 * <p>Sessão revogada nem chega aqui: o adaptador só devolve sessão não revogada, e token
 * desconhecido ou revogado é 401 {@code INVALID_CREDENTIALS} (mesmo código do login, sem enumerar o
 * motivo). Expiração absoluta vencida é 401 {@code SESSION_EXPIRED}; o idle timeout é do passo 209,
 * dono do código {@code SESSION_IDLE_TIMEOUT}.
 *
 * <p>{@code last_seen_at} é atualizado no máximo 1x/min ({@code
 * minimarket.security.session.touch-interval-seconds}, §6.2 — sem isso seria um write por request)
 * e por um {@code update} único: dois requests do mesmo token podem decidir tocar a sessão ao mesmo
 * tempo, e o banco serializa as duas gravações sem que nenhuma falhe por conflito otimista.
 */
@ApplicationScoped
public class AuthenticateSessionUseCase {

  /** Mensagem única de token desconhecido/revogado; não revela qual dos dois é (§6.3.4). */
  public static final String INVALID_TOKEN_DETAIL = "token de sessão inválido";

  /** Mensagem da expiração absoluta: o cliente sabe que precisa logar de novo. */
  public static final String SESSION_EXPIRED_DETAIL = "sessão expirada; faça login novamente";

  @Inject AuthSessionStore sessionStore;

  @Inject UserStore userStore;

  /** Relógio da aplicação: expiração absoluta e decisão de tocar {@code last_seen_at}. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.security.session.touch-interval-seconds")
  long touchIntervalSeconds;

  /**
   * Resolve a identidade do token: 401 {@code INVALID_CREDENTIALS} para hash desconhecido ou
   * revogado, 401 {@code SESSION_EXPIRED} para expiração absoluta vencida e, no sucesso, usuário,
   * RBAC efetivo e id da sessão. Usuário inexistente também é 401 genérico — sem usuário não há
   * identidade para montar.
   */
  @Transactional
  public AuthenticatedSession execute(String tokenHash) {
    Instant now = clock.instant();
    AuthSessionSnapshot session =
        sessionStore
            .findActiveByTokenHash(tokenHash)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, INVALID_TOKEN_DETAIL));
    if (!session.expiresAt().isAfter(now)) {
      throw new BusinessException(ErrorCode.SESSION_EXPIRED, SESSION_EXPIRED_DETAIL);
    }
    touchIfStale(session, now);
    UserAuthState user =
        userStore
            .findAuthStateById(session.userId())
            .orElseThrow(
                () -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, INVALID_TOKEN_DETAIL));
    return new AuthenticatedSession(
        session.id(), user.username(), user.roles(), user.permissions());
  }

  /**
   * Toca a sessão só quando o intervalo configurado passou desde o último registro. O write é um
   * {@code update} condicional (não passa pelo {@code @Version} da entidade): a atividade é um
   * sinal de melhor esforço e uma disputa entre dois requests do mesmo token não pode derrubar a
   * autenticação — o instante mais novo vence.
   */
  private void touchIfStale(AuthSessionSnapshot session, Instant now) {
    if (session.lastSeenAt() != null
        && Duration.between(session.lastSeenAt(), now).toSeconds() < touchIntervalSeconds) {
      return;
    }
    sessionStore.touchLastSeen(session.id(), now);
  }
}
