package com.minimarket.auth.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.domain.SessionClient;
import com.minimarket.auth.domain.TokenGenerator;
import com.minimarket.auth.domain.TokenHasher;
import com.minimarket.shared.application.CashRegisterLookup;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.FieldValidationException;
import com.minimarket.shared.domain.OperationSource;
import com.minimarket.shared.domain.Store;
import com.minimarket.users.application.PasswordHasher;
import com.minimarket.users.application.UserAuthState;
import com.minimarket.users.application.UserStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Login do PDV (passos 204a/204b): autentica, aplica a política de lock por tentativas e abre a
 * sessão. Uma execução = uma transação (§2.2, regra 6) — contador/lock, sessão criada, {@code
 * last_login_at} gravado e rehash saem juntos ou não saem.
 *
 * <p>Falha sempre com a mesma mensagem genérica (§6.3.4): senha errada e usuário inexistente são
 * indistinguíveis para quem tenta. Usuário inexistente verifica a senha contra um hash dummy, para
 * o tempo da resposta não denunciar a existência do username (§6.2). Usuário desativado ou
 * soft-deletado não incrementa contador: não há estado de autenticação válido para registrar. O
 * idle timeout por cliente ({@code minimarket.security.session.idle.*}) é validado a cada
 * requisição sobre {@code last_seen_at} (passos 206/209); aqui a sessão nasce com {@code
 * last_seen_at} do relógio injetado.
 *
 * <p>Lock (§6.3.1, passo 204b): o {@code locked_until} persistido é a verdade. Bloqueio no futuro
 * recusa antes mesmo de conferir a senha (423 {@code ACCOUNT_LOCKED}); bloqueio expirado é zerado e
 * a tentativa recomeça. A falha que atinge {@code minimarket.security.login.max-attempts} grava o
 * lock, mas ainda responde 401 — quem leva 423 é a tentativa seguinte, mesmo com a senha certa.
 *
 * <p>Atraso fixo (§6.3.2, passo 211): a recusa de credenciais espera {@code
 * minimarket.security.login.failure-delay-ms} antes do 401, encarecendo a varredura de senhas além
 * do custo do Argon2id. O 423 do bloqueio não espera: a conta já está fora do ar e o atraso só
 * puniria o cliente legítimo.
 *
 * <p>Rate limit por IP (§6.3.5, passo 212): antes de qualquer processamento o {@link
 * LoginRateLimiter} decide o 429 — teto de falhas por IP na janela em memória — e cada recusa (401
 * ou 423) conta para o IP; o login bem-sucedido limpa a contagem. É um paliativo de uma instância;
 * o limite definitivo é do proxy reverso (passo 1305 da Fase 13, citado como 1402 no passo 212).
 *
 * <p>Auditoria (§6.3.3, passo 304): cada tentativa vira um evento na mesma transação — {@code
 * LOGIN_SUCCESS} no sucesso, {@code LOGIN_FAILED} na credencial recusada (inclusive a falha que
 * atinge o limite, que ainda responde 401) e {@code LOGIN_LOCKED} na tentativa recusada por {@code
 * locked_until} no futuro. A rota é pública, então o filtro do passo 302 deixa o contexto anônimo:
 * o ator do sucesso nasce aqui, com o usuário e a sessão recém-criada, preservando o {@code
 * requestId} e o IP que o filtro já preencheu. As recusas não têm ator — o username tentado vai em
 * {@code details} — e a gravação acontece antes do throw, dentro da transação que o {@code
 * dontRollbackOn} mantém.
 *
 * <p>Caixa informado (passo 607b): {@code cashRegisterId} só é aceito se existir e estiver ativo. A
 * checagem vem depois da senha conferida e antes de qualquer gravação — quem não autenticou não
 * descobre nada sobre o caixa e um caixa inválido não cria sessão, não rehasha senha e não grava
 * login. Nulo continua aceito: sessão sem caixa, vinculada depois na abertura (passo 607).
 */
@ApplicationScoped
public class LoginUseCase {

  /** Ação do login bem-sucedido (§7.2). */
  private static final String LOGIN_SUCCESS_ACTION = "LOGIN_SUCCESS";

  /** Ação da credencial recusada (§7.2); o 401 da tentativa que atinge o limite também é esta. */
  private static final String LOGIN_FAILED_ACTION = "LOGIN_FAILED";

  /** Ação da tentativa recusada por conta bloqueada (§7.2). */
  private static final String LOGIN_LOCKED_ACTION = "LOGIN_LOCKED";

  /** Alvo dos eventos de login: o usuário; nulo quando o username tentado não existe. */
  private static final String USER_ENTITY_TYPE = "USER";

  /**
   * Hash PHC de uma senha aleatória que ninguém conhece, com os mesmos parâmetros do Argon2id do
   * projeto ({@code minimarket.security.password.*}): só serve para o usuário inexistente pagar o
   * mesmo custo de verificação de um usuário real. Nunca confere com senha alguma.
   */
  static final String DUMMY_PASSWORD_HASH =
      "$argon2id$v=19$m=19456,t=2,p=1$McLn2XMBdjsWOnVaGGInXA$Zbhx1hbgqkW1gCnSL0cszyOEptI+ZmBLNPEIW0uBY/A";

  /** Mensagem única das credenciais recusadas; não revela se o username existe (§6.3.4). */
  private static final String INVALID_CREDENTIALS_DETAIL = "usuário ou senha inválidos";

  /** Mensagem do bloqueio; o 423 deixa claro que a conta existe e está temporariamente fora. */
  private static final String ACCOUNT_LOCKED_DETAIL =
      "conta bloqueada temporariamente por tentativas de login inválidas";

  /** Status do usuário que pode autenticar; espelha o check constraint de {@code users.status}. */
  private static final String STATUS_ACTIVE = "ACTIVE";

  @Inject UserStore userStore;

  @Inject AuthSessionStore sessionStore;

  @Inject PasswordHasher passwordHasher;

  /** Rate limit por IP (passo 212): o teto é decidido antes de a senha sequer ser conferida. */
  @Inject LoginRateLimiter rateLimiter;

  /**
   * Gerador e hash do token são domínio puro, sem estado e sem CDI (passo 203): o caso de uso
   * instancia os dois.
   */
  private final TokenGenerator tokenGenerator = new TokenGenerator();

  private final TokenHasher tokenHasher = new TokenHasher();

  @Inject StoreLookup storeLookup;

  /**
   * Porta compartilhada do caixa (passo 607b): o login valida o caixa informado sem depender de
   * {@code cash.application}, que já depende de {@code auth} (ciclo fecharia).
   */
  @Inject CashRegisterLookup cashRegisterLookup;

  /** Auditoria do acesso (§6.3.3, passo 304): a tentativa vira evento na transação do login. */
  @Inject AuditRecorder auditRecorder;

  /**
   * Contexto do ator (passo 302): o filtro preenche {@code requestId} e IP em toda requisição e
   * deixa o resto vazio no login, que é público — é aqui que o ator da sessão nova nasce.
   */
  @Inject OperationContext operationContext;

  /** Relógio da aplicação: expiração, lock, {@code last_login_at} e {@code last_seen_at}. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Expiração absoluta da sessão, nunca estendida (§6.2): 12 h por configuração. */
  @ConfigProperty(name = "minimarket.security.session.absolute-expiration")
  Duration absoluteExpiration;

  /** Falhas consecutivas que disparam o lock (§6.3.1): 5 por configuração. */
  @ConfigProperty(name = "minimarket.security.login.max-attempts")
  int maxLoginAttempts;

  /** Duração do bloqueio após atingir o limite (§6.3.1): 15 min por configuração. */
  @ConfigProperty(name = "minimarket.security.login.lock-minutes")
  int lockMinutes;

  /** Atraso fixo antes da recusa de credenciais (§6.3.2): 400 ms por configuração. */
  @ConfigProperty(name = "minimarket.security.login.failure-delay-ms")
  long failureDelayMs;

  /**
   * Autentica e abre a sessão. Conta bloqueada lança 423 {@code ACCOUNT_LOCKED} antes de conferir a
   * senha; credenciais inválidas (senha errada ou usuário inexistente) lançam 401 {@code
   * INVALID_CREDENTIALS} com a mensagem genérica; o resto devolve o token em claro, a expiração
   * absoluta e o RBAC efetivo do usuário.
   *
   * <p>{@code dontRollbackOn}: a falha de credenciais é resultado esperado, mas o contador e o lock
   * que ela acabou de gravar precisam sobreviver ao 401 — sem isso o interceptor desfaria a
   * contagem e o bloqueio do §6.3.1 nunca aconteceria (coberto pelo teste de API do passo 205).
   *
   * <p>O rate limit do passo 212 vem primeiro: IP com o teto estourado recebe 429 {@code
   * RATE_LIMITED} sem que a senha seja conferida nem o contador do usuário mude.
   *
   * <p>O caixa informado (passo 607b) é validado depois da senha e antes das gravações: caixa
   * inexistente ou inativo responde 400 {@code VALIDATION_ERROR} com {@code errors[]} apontando
   * {@code cashRegisterId}, sem sessão criada nem rehash.
   */
  @Transactional(dontRollbackOn = BusinessException.class)
  public LoginResult execute(LoginCommand command) {
    rateLimiter.check(command.ip());
    Instant now = clock.instant();
    UserAuthState user = userStore.findAuthStateByUsername(command.username()).orElse(null);
    int previousAttempts = 0;
    if (user != null) {
      if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
        rateLimiter.recordFailure(command.ip());
        recordLoginFailure(LOGIN_LOCKED_ACTION, command, user);
        throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, ACCOUNT_LOCKED_DETAIL);
      }
      if (user.lockedUntil() != null) {
        // Bloqueio expirado: zera contador e lock; a tentativa recomeça como se fosse a primeira.
        userStore.clearLoginFailures(user.id());
      } else {
        previousAttempts = user.failedLoginAttempts();
      }
    }
    // A verificação acontece antes de qualquer decisão: sem usuário, contra o hash dummy.
    boolean passwordMatches =
        passwordHasher.verify(
            command.password(), user == null ? DUMMY_PASSWORD_HASH : user.passwordHash());
    if (user == null || !isActive(user)) {
      recordLoginFailure(LOGIN_FAILED_ACTION, command, user);
      rejectInvalidCredentials(command.ip());
    }
    if (!passwordMatches) {
      registerFailedAttempt(user.id(), previousAttempts, now);
      recordLoginFailure(LOGIN_FAILED_ACTION, command, user);
      rejectInvalidCredentials(command.ip());
    }
    requireActiveCashRegister(command.cashRegisterId());
    rehashIfNeeded(user, command.password());

    Store store = requireStore();
    String token = tokenGenerator.generate();
    Instant expiresAt = now.plus(absoluteExpiration);
    UUID sessionId =
        sessionStore.insert(
            new NewAuthSession(
                user.id(),
                tokenHasher.hash(token),
                command.client(),
                store.id(),
                command.cashRegisterId(),
                command.ip(),
                command.userAgent(),
                now,
                expiresAt));
    userStore.recordSuccessfulLogin(user.id(), now);
    // Sucesso limpa a contagem do IP (passo 212): NAT compartilhado não carrega falha de ninguém.
    rateLimiter.recordSuccess(command.ip());
    recordLoginSuccess(command, user, store, sessionId);

    return new LoginResult(
        token,
        expiresAt,
        user.id(),
        user.username(),
        user.displayName(),
        user.roles(),
        user.permissions(),
        user.mustChangePassword());
  }

  /**
   * Soma a falha ao contador e, quando o limite é atingido, grava o lock a partir de agora. A
   * tentativa que fecha o ciclo ainda recebe 401; o 423 fica para a próxima (§6.3.1).
   */
  private void registerFailedAttempt(UUID userId, int previousAttempts, Instant now) {
    int attempts = previousAttempts + 1;
    Instant lockedUntil =
        attempts >= maxLoginAttempts ? now.plus(Duration.ofMinutes(lockMinutes)) : null;
    userStore.recordFailedLogin(userId, attempts, lockedUntil);
  }

  /**
   * Evento da tentativa recusada (passo 304), gravado antes do throw — o {@code dontRollbackOn} do
   * {@link #execute} mantém a linha junto com o contador e o lock que a falha acabou de gravar.
   *
   * <p>Não há ator: a requisição é anônima e o username pode nem existir. O username tentado e o
   * cliente vão em {@code details} para a investigação; o IP e o {@code request_id} já vêm do
   * contexto que o filtro preencheu (passo 302). {@code user} nulo (username desconhecido) deixa o
   * evento sem entidade.
   */
  private void recordLoginFailure(String action, LoginCommand command, UserAuthState user) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("username", command.username());
    details.put("client", command.client().name());
    auditRecorder.record(action, USER_ENTITY_TYPE, user == null ? null : user.id(), null, details);
  }

  /**
   * Ator do login bem-sucedido (passo 304): a rota é pública, então o contexto chega anônimo do
   * filtro e é aqui que ele ganha usuário, sessão recém-criada, loja e caixa — preservando o {@code
   * requestId} e o IP que o filtro já tinha preenchido nesta requisição.
   */
  private void recordLoginSuccess(
      LoginCommand command, UserAuthState user, Store store, UUID sessionId) {
    String requestId = operationContext.requestId();
    InetAddress ip = operationContext.ip();
    operationContext.fill(
        user.id(),
        user.username(),
        sessionId,
        store.id(),
        command.cashRegisterId(),
        requestId,
        ip,
        sourceOf(command.client()));
    auditRecorder.record(LOGIN_SUCCESS_ACTION, USER_ENTITY_TYPE, user.id(), null, null);
  }

  /** Origem do evento (passo 302): o cliente da sessão nova vira {@code TUI}/{@code WEB}. */
  private static OperationSource sourceOf(SessionClient client) {
    return client == SessionClient.TUI ? OperationSource.TUI : OperationSource.WEB;
  }

  /**
   * Recusa genérica de credenciais (§6.3.4) precedida do atraso fixo (§6.3.2, passo 211): a falha
   * espera 400 ms antes do 401, sem mudar o resultado nem a transação — o contador e o lock que
   * {@link #registerFailedAttempt} acabou de gravar continuam comitando pelo {@code dontRollbackOn}
   * de {@link #execute}. Interrupção não vira erro: a flag é restaurada e a recusa segue. A falha
   * também conta para o rate limit do IP (passo 212), antes de pagar o atraso.
   */
  private void rejectInvalidCredentials(InetAddress ip) {
    rateLimiter.recordFailure(ip);
    try {
      Thread.sleep(failureDelayMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
  }

  /**
   * Caixa informado precisa existir e estar ativo (passo 607b); nulo segue aceito. A checagem roda
   * depois da senha conferida e antes de qualquer gravação: o 400 não vaza a existência do caixa
   * para quem não autenticou e caixa inválido não deixa sessão, rehash nem login registrado.
   */
  private void requireActiveCashRegister(UUID cashRegisterId) {
    if (cashRegisterId != null && !cashRegisterLookup.isActive(cashRegisterId)) {
      throw new FieldValidationException("cashRegisterId", "caixa não encontrado ou inativo");
    }
  }

  /**
   * Rehash no login bem-sucedido (§6.2): a senha em texto puro só existe aqui, então é agora ou
   * nunca que o hash antigo pode ser regerado com os parâmetros atuais.
   */
  private void rehashIfNeeded(UserAuthState user, String rawPassword) {
    if (passwordHasher.needsRehash(user.passwordHash())) {
      userStore.updatePasswordHash(user.id(), passwordHasher.hash(rawPassword));
    }
  }

  /** Loja atual por configuração (§5.4): sessão sem {@code store_id} não existe. */
  private Store requireStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }

  /** Só usuário vivo e {@code ACTIVE} autentica; o resto cai na mensagem genérica. */
  private static boolean isActive(UserAuthState user) {
    return STATUS_ACTIVE.equals(user.status()) && user.deletedAt() == null;
  }
}
