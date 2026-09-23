package com.minimarket.auth.application;

import com.minimarket.auth.domain.TokenGenerator;
import com.minimarket.auth.domain.TokenHasher;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Store;
import com.minimarket.users.application.PasswordHasher;
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
 * Login do PDV (passo 204a): autentica e abre a sessão, sem a política de lock por tentativas
 * (passo 204b). Uma execução = uma transação (§2.2, regra 6) — sessão criada, {@code last_login_at}
 * gravado e rehash saem juntos ou não saem.
 *
 * <p>Falha sempre com a mesma mensagem genérica (§6.3.4): senha errada, usuário inexistente e
 * usuário desativado são indistinguíveis para quem tenta. Usuário inexistente verifica a senha
 * contra um hash dummy, para o tempo da resposta não denunciar a existência do username (§6.2). O
 * idle timeout por cliente ({@code minimarket.security.session.idle.*}) é validado a cada
 * requisição sobre {@code last_seen_at} (passos 206/209); aqui a sessão nasce com {@code
 * last_seen_at} do relógio injetado.
 */
@ApplicationScoped
public class LoginUseCase {

  /**
   * Hash PHC de uma senha aleatória que ninguém conhece, com os mesmos parâmetros do Argon2id do
   * projeto ({@code minimarket.security.password.*}): só serve para o usuário inexistente pagar o
   * mesmo custo de verificação de um usuário real. Nunca confere com senha alguma.
   */
  static final String DUMMY_PASSWORD_HASH =
      "$argon2id$v=19$m=19456,t=2,p=1$McLn2XMBdjsWOnVaGGInXA$Zbhx1hbgqkW1gCnSL0cszyOEptI+ZmBLNPEIW0uBY/A";

  /** Mensagem única das credenciais recusadas; não revela se o username existe (§6.3.4). */
  private static final String INVALID_CREDENTIALS_DETAIL = "usuário ou senha inválidos";

  /** Status do usuário que pode autenticar; espelha o check constraint de {@code users.status}. */
  private static final String STATUS_ACTIVE = "ACTIVE";

  @Inject UserStore userStore;

  @Inject AuthSessionStore sessionStore;

  @Inject PasswordHasher passwordHasher;

  /**
   * Gerador e hash do token são domínio puro, sem estado e sem CDI (passo 203): o caso de uso
   * instancia os dois.
   */
  private final TokenGenerator tokenGenerator = new TokenGenerator();

  private final TokenHasher tokenHasher = new TokenHasher();

  @Inject StoreLookup storeLookup;

  /** Relógio da aplicação: expiração, {@code last_login_at} e {@code last_seen_at} saem daqui. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** Expiração absoluta da sessão, nunca estendida (§6.2): 12 h por configuração. */
  @ConfigProperty(name = "minimarket.security.session.absolute-expiration")
  Duration absoluteExpiration;

  /**
   * Autentica e abre a sessão. Credenciais inválidas (senha errada, usuário inexistente ou
   * desativado/soft-deletado) lançam 401 {@code INVALID_CREDENTIALS}; o resto devolve o token em
   * claro, a expiração absoluta e o RBAC efetivo do usuário.
   */
  @Transactional
  public LoginResult execute(LoginCommand command) {
    Instant now = clock.instant();
    UserAuthState user = userStore.findAuthStateByUsername(command.username()).orElse(null);
    // A verificação acontece antes de qualquer decisão: sem usuário, contra o hash dummy.
    boolean passwordMatches =
        passwordHasher.verify(
            command.password(), user == null ? DUMMY_PASSWORD_HASH : user.passwordHash());
    if (user == null || !passwordMatches || !isActive(user)) {
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
    }
    rehashIfNeeded(user, command.password());

    Store store = requireStore();
    String token = tokenGenerator.generate();
    Instant expiresAt = now.plus(absoluteExpiration);
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
