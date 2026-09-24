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
 * Autenticação de uma requisição pelo token de sessão (passos 206/209). Uma execução = uma
 * transação (§2.2, regra 6): a sessão é lida, o ciclo de vida conferido e o RBAC efetivo relido do
 * banco — o servidor revalida as permissões a cada request em vez de confiar em cache de token
 * (§6.4).
 *
 * <p>Sessão revogada nem chega aqui: o adaptador só devolve sessão não revogada, e token
 * desconhecido ou revogado é 401 {@code INVALID_CREDENTIALS} (mesmo código do login, sem enumerar o
 * motivo). A expiração absoluta vence a checagem (401 {@code SESSION_EXPIRED}); só então a
 * inatividade além do limite do cliente é avaliada (401 {@code SESSION_IDLE_TIMEOUT}, passo 209) —
 * sessão vencida de vez não mente sobre o motivo. Cada cliente tem seu limite (§6.2): WEB 30 min,
 * TUI 8 h.
 *
 * <p>A identidade devolvida carrega também o cliente, a loja e o caixa da sessão (passo 302): são
 * atributos da identidade e é deles que o {@code OperationContext} da requisição é preenchido, sem
 * uma segunda consulta ao banco.
 *
 * <p>{@code last_seen_at} é atualizado no máximo 1x/min ({@code
 * minimarket.security.session.touch-interval-seconds}, §6.2 — sem isso seria um write por request)
 * e por um {@code update} único: dois requests do mesmo token podem decidir tocar a sessão ao mesmo
 * tempo, e o banco serializa as duas gravações sem que nenhuma falhe por conflito otimista. O uso
 * renova {@code last_seen_at}, nunca {@code expires_at}: a expiração absoluta não se estende.
 */
@ApplicationScoped
public class AuthenticateSessionUseCase {

  /** Mensagem única de token desconhecido/revogado; não revela qual dos dois é (§6.3.4). */
  public static final String INVALID_TOKEN_DETAIL = "token de sessão inválido";

  /** Mensagem da expiração absoluta: o cliente sabe que precisa logar de novo. */
  public static final String SESSION_EXPIRED_DETAIL = "sessão expirada; faça login novamente";

  /** Mensagem do idle timeout (passo 209): a sessão venceu por tempo sem uso, não por idade. */
  public static final String SESSION_IDLE_TIMEOUT_DETAIL =
      "sessão expirada por inatividade; faça login novamente";

  @Inject AuthSessionStore sessionStore;

  @Inject UserStore userStore;

  /** Relógio da aplicação: expiração absoluta, idle e decisão de tocar {@code last_seen_at}. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.security.session.touch-interval-seconds")
  long touchIntervalSeconds;

  /** Idle timeout do cliente Web (§6.2, passo 209): 30 min por configuração. */
  @ConfigProperty(name = "minimarket.security.session.idle.web")
  Duration idleWeb;

  /**
   * Idle timeout da TUI (§6.2, passo 209): 8 h por configuração — a TUI fica em operação contínua.
   */
  @ConfigProperty(name = "minimarket.security.session.idle.tui")
  Duration idleTui;

  /**
   * Resolve a identidade do token: 401 {@code INVALID_CREDENTIALS} para hash desconhecido ou
   * revogado, 401 {@code SESSION_EXPIRED} para expiração absoluta vencida, 401 {@code
   * SESSION_IDLE_TIMEOUT} para inatividade além do limite do cliente e, no sucesso, usuário, RBAC
   * efetivo, id da sessão e a origem da operação (cliente, loja e caixa da sessão). Usuário
   * inexistente também é 401 genérico — sem usuário não há identidade para montar.
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
    if (isIdleBeyondLimit(session, now)) {
      throw new BusinessException(ErrorCode.SESSION_IDLE_TIMEOUT, SESSION_IDLE_TIMEOUT_DETAIL);
    }
    touchIfStale(session, now);
    UserAuthState user =
        userStore
            .findAuthStateById(session.userId())
            .orElseThrow(
                () -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, INVALID_TOKEN_DETAIL));
    return new AuthenticatedSession(
        session.id(),
        user.id(),
        user.username(),
        user.roles(),
        user.permissions(),
        session.client(),
        session.storeId(),
        session.cashRegisterId());
  }

  /**
   * Inatividade (passo 209): só vence quando passa do limite do cliente que abriu a sessão — no
   * limite exato a sessão ainda vale. A checagem vem depois da expiração absoluta, que é
   * definitiva: sessão vencida de vez responde {@code SESSION_EXPIRED}, nunca o idle timeout.
   */
  private boolean isIdleBeyondLimit(AuthSessionSnapshot session, Instant now) {
    Duration limit =
        switch (session.client()) {
          case TUI -> idleTui;
          case WEB -> idleWeb;
        };
    return Duration.between(session.lastSeenAt(), now).compareTo(limit) > 0;
  }

  /**
   * Toca a sessão só quando o intervalo configurado passou desde o último registro. O write é um
   * {@code update} condicional (não passa pelo {@code @Version} da entidade): a atividade é um
   * sinal de melhor esforço e uma disputa entre dois requests do mesmo token não pode derrubar a
   * autenticação — o instante mais novo vence. Só {@code last_seen_at} muda: {@code expires_at}
   * fica como o login o gravou.
   */
  private void touchIfStale(AuthSessionSnapshot session, Instant now) {
    if (session.lastSeenAt() != null
        && Duration.between(session.lastSeenAt(), now).toSeconds() < touchIntervalSeconds) {
      return;
    }
    sessionStore.touchLastSeen(session.id(), now);
  }
}
