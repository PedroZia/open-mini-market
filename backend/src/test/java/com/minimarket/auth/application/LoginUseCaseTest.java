package com.minimarket.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.auth.domain.SessionClient;
import com.minimarket.auth.domain.TokenHasher;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Store;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.PasswordHasher;
import com.minimarket.users.application.UserAuthState;
import com.minimarket.users.application.UserSort;
import com.minimarket.users.application.UserStore;
import com.minimarket.users.application.UserSummary;
import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link LoginUseCase}, sem Quarkus e sem banco: as portas são dublês escritos à
 * mão e o relógio é fixo — a expiração e o {@code last_seen_at} são conferidos no instante
 * controlado.
 */
class LoginUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
  private static final Duration ABSOLUTE_EXPIRATION = Duration.ofHours(12);
  private static final int MAX_ATTEMPTS = 5;
  private static final int LOCK_MINUTES = 15;
  private static final String USERNAME = "ana.souza";
  private static final String PASSWORD = "senha-secreta";
  private static final String STORED_HASH = "$argon2id$v=19$m=19456,t=2,p=1$hash-antigo";
  private static final String NEW_HASH = "$argon2id$v=19$m=19456,t=2,p=1$hash-novo";
  private static final UUID USER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000003");

  private final FakeUserStore userStore = new FakeUserStore();
  private final FakeSessionStore sessionStore = new FakeSessionStore();
  private final FakePasswordHasher passwordHasher = new FakePasswordHasher();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();
  private final TokenHasher tokenHasher = new TokenHasher();

  private LoginUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new LoginUseCase();
    useCase.userStore = userStore;
    useCase.sessionStore = sessionStore;
    useCase.passwordHasher = passwordHasher;
    useCase.storeLookup = storeLookup;
    useCase.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    useCase.defaultStoreCode = "MATRIZ";
    useCase.absoluteExpiration = ABSOLUTE_EXPIRATION;
    useCase.maxLoginAttempts = MAX_ATTEMPTS;
    useCase.lockMinutes = LOCK_MINUTES;
    passwordHasher.passwordMatches = true;
    userStore.authState = authState("ACTIVE", null, false);
  }

  @Test
  @DisplayName("autentica, cria a sessão com expiração absoluta e last_seen_at do relógio")
  void authenticatesAndCreatesSession() throws Exception {
    InetAddress ip = InetAddress.getByName("192.168.0.10");

    LoginResult result =
        useCase.execute(
            new LoginCommand(
                USERNAME, PASSWORD, SessionClient.TUI, CASH_REGISTER_ID, ip, "tui/1.0"));

    assertThat(result.token()).hasSize(43);
    assertThat(result.expiresAt()).isEqualTo(NOW.plus(ABSOLUTE_EXPIRATION));
    assertThat(result.userId()).isEqualTo(USER_ID);
    assertThat(result.username()).isEqualTo(USERNAME);
    assertThat(result.displayName()).isEqualTo("Ana Souza");
    assertThat(result.roles()).containsExactly("OPERADOR");
    assertThat(result.permissions()).containsExactly("sale.create");
    assertThat(result.mustChangePassword()).isFalse();

    // A senha confere contra o hash real (nunca o dummy) e o token cru não é persistido: só o hash.
    assertThat(passwordHasher.verifiedHash).isEqualTo(STORED_HASH);
    assertThat(sessionStore.inserted.tokenHash())
        .isEqualTo(tokenHasher.hash(result.token()))
        .isNotEqualTo(result.token());
    assertThat(sessionStore.inserted.userId()).isEqualTo(USER_ID);
    assertThat(sessionStore.inserted.client()).isEqualTo(SessionClient.TUI);
    assertThat(sessionStore.inserted.storeId()).isEqualTo(STORE_ID);
    assertThat(sessionStore.inserted.cashRegisterId()).isEqualTo(CASH_REGISTER_ID);
    assertThat(sessionStore.inserted.ip()).isEqualTo(ip);
    assertThat(sessionStore.inserted.userAgent()).isEqualTo("tui/1.0");
    assertThat(sessionStore.inserted.lastSeenAt()).isEqualTo(NOW);
    assertThat(sessionStore.inserted.expiresAt()).isEqualTo(NOW.plus(ABSOLUTE_EXPIRATION));

    assertThat(userStore.successfulLoginId).isEqualTo(USER_ID);
    assertThat(userStore.successfulLoginAt).isEqualTo(NOW);
    assertThat(userStore.updatedHash).isNull();
  }

  @Test
  @DisplayName("sessão do Web sai com o client WEB e a mesma expiração absoluta")
  void createsWebSessionForWebClient() {
    LoginResult result =
        useCase.execute(new LoginCommand(USERNAME, PASSWORD, SessionClient.WEB, null, null, null));

    assertThat(sessionStore.inserted.client()).isEqualTo(SessionClient.WEB);
    assertThat(sessionStore.inserted.cashRegisterId()).isNull();
    assertThat(sessionStore.inserted.ip()).isNull();
    assertThat(sessionStore.inserted.userAgent()).isNull();
    assertThat(result.expiresAt()).isEqualTo(NOW.plus(ABSOLUTE_EXPIRATION));
  }

  @Test
  @DisplayName("senha errada lança INVALID_CREDENTIALS sem criar sessão nem gravar login")
  void rejectsWrongPassword() {
    passwordHasher.passwordMatches = false;

    assertInvalidCredentials();

    assertThat(sessionStore.inserted).isNull();
    assertThat(userStore.successfulLoginAt).isNull();
    assertThat(userStore.updatedHash).isNull();
  }

  @Test
  @DisplayName(
      "usuário inexistente lança INVALID_CREDENTIALS e verifica a senha contra o hash dummy")
  void rejectsUnknownUserAgainstDummyHash() {
    userStore.authState = null;

    assertInvalidCredentials();

    assertThat(passwordHasher.verifiedHash).isEqualTo(LoginUseCase.DUMMY_PASSWORD_HASH);
    assertThat(sessionStore.inserted).isNull();
    assertThat(userStore.successfulLoginAt).isNull();
  }

  @Test
  @DisplayName("usuário desativado lança INVALID_CREDENTIALS com a mesma mensagem genérica")
  void rejectsDisabledUser() {
    userStore.authState = authState("DISABLED", NOW.minusSeconds(60), false);

    assertInvalidCredentials();

    // O hash real é verificado mesmo recusando: o tempo não denuncia o motivo.
    assertThat(passwordHasher.verifiedHash).isEqualTo(STORED_HASH);
    assertThat(sessionStore.inserted).isNull();
  }

  @Test
  @DisplayName("usuário soft-deletado lança INVALID_CREDENTIALS mesmo com senha certa")
  void rejectsSoftDeletedUser() {
    userStore.authState = authState("ACTIVE", NOW.minusSeconds(60), false);

    assertInvalidCredentials();

    assertThat(sessionStore.inserted).isNull();
    assertThat(userStore.successfulLoginAt).isNull();
  }

  @Test
  @DisplayName("rehash: hash fora da política atual é regerado no sucesso, sem exigir troca")
  void rehashesWhenNeeded() {
    passwordHasher.needsRehash = true;

    useCase.execute(new LoginCommand(USERNAME, PASSWORD, SessionClient.TUI, null, null, null));

    assertThat(userStore.updatedHash).isEqualTo(NEW_HASH);
    assertThat(userStore.updatedHashId).isEqualTo(USER_ID);
    assertThat(sessionStore.inserted).isNotNull();
  }

  @Test
  @DisplayName("sem rehash necessário o hash guardado não é tocado")
  void keepsHashWhenRehashIsNotNeeded() {
    useCase.execute(new LoginCommand(USERNAME, PASSWORD, SessionClient.TUI, null, null, null));

    assertThat(userStore.updatedHash).isNull();
  }

  @Test
  @DisplayName("falha abaixo do limite só incrementa o contador, sem bloquear")
  void incrementsCounterWithoutLockingBelowLimit() {
    // Três falhas antes: a quarta ainda não bloqueia (o limite é 5).
    userStore.authState = authState("ACTIVE", null, 3, null, false);
    passwordHasher.passwordMatches = false;

    assertInvalidCredentials();

    assertThat(userStore.failedLoginId).isEqualTo(USER_ID);
    assertThat(userStore.failedLoginAttempts).isEqualTo(4);
    assertThat(userStore.failedLoginLockedUntil).isNull();
    assertThat(userStore.clearedFailuresId).isNull();
    assertThat(sessionStore.inserted).isNull();
  }

  @Test
  @DisplayName("5ª falha grava o lock de 15 min e ainda responde INVALID_CREDENTIALS")
  void locksOnFifthFailure() {
    userStore.authState = authState("ACTIVE", null, MAX_ATTEMPTS - 1, null, false);
    passwordHasher.passwordMatches = false;

    assertInvalidCredentials();

    assertThat(userStore.failedLoginId).isEqualTo(USER_ID);
    assertThat(userStore.failedLoginAttempts).isEqualTo(MAX_ATTEMPTS);
    assertThat(userStore.failedLoginLockedUntil)
        .isEqualTo(NOW.plus(Duration.ofMinutes(LOCK_MINUTES)));
    assertThat(sessionStore.inserted).isNull();
  }

  @Test
  @DisplayName(
      "conta bloqueada recusa com ACCOUNT_LOCKED mesmo com senha certa, sem conferir a senha")
  void rejectsLockedAccountEvenWithCorrectPassword() {
    userStore.authState =
        authState("ACTIVE", null, MAX_ATTEMPTS, NOW.plus(Duration.ofMinutes(1)), false);
    passwordHasher.passwordMatches = true;

    assertThatThrownBy(() -> useCase.execute(command()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED);
              assertThat(exception.getMessage()).contains("bloqueada");
            });

    // A senha nem chega a ser verificada e nada é gravado: o bloqueio vence antes de tudo.
    assertThat(passwordHasher.verifiedHash).isNull();
    assertThat(userStore.failedLoginAttempts).isNull();
    assertThat(userStore.clearedFailuresId).isNull();
    assertThat(sessionStore.inserted).isNull();
  }

  @Test
  @DisplayName("bloqueio expirado libera a senha certa e zera contador e lock antes de autenticar")
  void expiredLockAllowsLoginAndClearsState() {
    userStore.authState =
        authState("ACTIVE", null, MAX_ATTEMPTS, NOW.minus(Duration.ofMinutes(1)), false);
    passwordHasher.passwordMatches = true;

    LoginResult result = useCase.execute(command());

    assertThat(result.token()).isNotBlank();
    assertThat(userStore.clearedFailuresId).isEqualTo(USER_ID);
    assertThat(userStore.successfulLoginId).isEqualTo(USER_ID);
    assertThat(userStore.failedLoginAttempts).isNull();
    assertThat(sessionStore.inserted).isNotNull();
  }

  @Test
  @DisplayName("falha depois do bloqueio expirado recomeça o contador em 1, não em 6")
  void failureAfterExpiredLockRestartsCounter() {
    userStore.authState =
        authState("ACTIVE", null, MAX_ATTEMPTS, NOW.minus(Duration.ofMinutes(1)), false);
    passwordHasher.passwordMatches = false;

    assertInvalidCredentials();

    assertThat(userStore.clearedFailuresId).isEqualTo(USER_ID);
    assertThat(userStore.failedLoginAttempts).isEqualTo(1);
    assertThat(userStore.failedLoginLockedUntil).isNull();
  }

  @Test
  @DisplayName("sucesso grava o login bem-sucedido sem registrar falha nem lock")
  void successRecordsLoginWithoutFailures() {
    userStore.authState = authState("ACTIVE", null, 3, null, false);

    useCase.execute(command());

    // recordSuccessfulLogin (users.infrastructure) é quem zera contador e locked_until.
    assertThat(userStore.successfulLoginId).isEqualTo(USER_ID);
    assertThat(userStore.failedLoginAttempts).isNull();
    assertThat(userStore.clearedFailuresId).isNull();
  }

  @Test
  @DisplayName("senha errada de usuário desativado não incrementa o contador")
  void failedDisabledUserDoesNotTouchCounter() {
    userStore.authState = authState("DISABLED", null, 2, null, false);
    passwordHasher.passwordMatches = false;

    assertInvalidCredentials();

    assertThat(userStore.failedLoginAttempts).isNull();
  }

  @Test
  @DisplayName("resultado não tem campo de senha nem de hash e não vaza o hash guardado")
  void resultDoesNotCarryCredentials() {
    LoginResult result =
        useCase.execute(new LoginCommand(USERNAME, PASSWORD, SessionClient.TUI, null, null, null));

    assertThat(result.toString())
        .doesNotContain(PASSWORD)
        .doesNotContain(STORED_HASH)
        .doesNotContain(sessionStore.inserted.tokenHash());
  }

  @Test
  @DisplayName("hash dummy é um PHC Argon2id válido e nunca confere com senha alguma")
  void dummyHashIsAValidArgon2Phc() {
    Argon2Function function = Argon2Function.getInstanceFromHash(LoginUseCase.DUMMY_PASSWORD_HASH);

    assertThat(function.getVariant()).isEqualTo(Argon2.ID);
    assertThat(function.getMemory()).isPositive();
    assertThat(function.check(PASSWORD, LoginUseCase.DUMMY_PASSWORD_HASH)).isFalse();
  }

  private void assertInvalidCredentials() {
    assertThatThrownBy(() -> useCase.execute(command()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
              assertThat(exception.getMessage())
                  .isEqualTo("usuário ou senha inválidos")
                  .doesNotContain(USERNAME);
            });
  }

  private static UserAuthState authState(String status, Instant deletedAt, boolean mustChange) {
    return authState(status, deletedAt, 0, null, mustChange);
  }

  /** Estado de autenticação com o contador e o lock informados (passo 204b). */
  private static UserAuthState authState(
      String status,
      Instant deletedAt,
      int failedAttempts,
      Instant lockedUntil,
      boolean mustChange) {
    return new UserAuthState(
        USER_ID,
        USERNAME,
        "Ana Souza",
        STORED_HASH,
        status,
        deletedAt,
        failedAttempts,
        lockedUntil,
        mustChange,
        List.of("OPERADOR"),
        Set.of("sale.create"));
  }

  private static LoginCommand command() {
    return new LoginCommand(USERNAME, PASSWORD, SessionClient.TUI, null, null, null);
  }

  /** Dublê de {@link UserStore}: devolve o estado combinado e guarda o que o login gravou. */
  private static final class FakeUserStore implements UserStore {

    private UserAuthState authState;
    private UUID successfulLoginId;
    private Instant successfulLoginAt;
    private UUID updatedHashId;
    private String updatedHash;
    private UUID failedLoginId;
    private Integer failedLoginAttempts;
    private Instant failedLoginLockedUntil;
    private UUID clearedFailuresId;

    @Override
    public Optional<UserAuthState> findAuthStateByUsername(String username) {
      return Optional.ofNullable(authState);
    }

    @Override
    public Optional<UserAuthState> findAuthStateById(UUID id) {
      throw new UnsupportedOperationException("findAuthStateById não é usado por Login");
    }

    @Override
    public void recordSuccessfulLogin(UUID id, Instant loginAt) {
      successfulLoginId = id;
      successfulLoginAt = loginAt;
    }

    @Override
    public void recordFailedLogin(UUID id, int failedLoginAttempts, Instant lockedUntil) {
      failedLoginId = id;
      this.failedLoginAttempts = failedLoginAttempts;
      this.failedLoginLockedUntil = lockedUntil;
    }

    @Override
    public void clearLoginFailures(UUID id) {
      clearedFailuresId = id;
    }

    @Override
    public void updatePasswordHash(UUID id, String passwordHash) {
      updatedHashId = id;
      updatedHash = passwordHash;
    }

    @Override
    public boolean existsByUsername(String username) {
      throw new UnsupportedOperationException("existsByUsername não é usado por Login");
    }

    @Override
    public UUID insert(NewUser user) {
      throw new UnsupportedOperationException("insert não é usado por Login");
    }

    @Override
    public void updateDisplayName(UUID id, String displayName) {
      throw new UnsupportedOperationException("updateDisplayName não é usado por Login");
    }

    @Override
    public Optional<UserSummary> findSummaryById(UUID id) {
      throw new UnsupportedOperationException("findSummaryById não é usado por Login");
    }

    @Override
    public Optional<UserSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por Login");
    }

    @Override
    public Optional<UserSummary> enable(UUID id) {
      throw new UnsupportedOperationException("enable não é usado por Login");
    }

    @Override
    public Optional<UserSummary> resetPassword(UUID id, String passwordHash) {
      throw new UnsupportedOperationException("resetPassword não é usado por Login");
    }

    @Override
    public void requirePasswordChange(UUID id) {
      throw new UnsupportedOperationException("requirePasswordChange não é usado por Login");
    }

    @Override
    public List<UserSummary> search(
        String search, Boolean active, UserSort sort, boolean ascending, int page, int size) {
      throw new UnsupportedOperationException("search não é usado por Login");
    }

    @Override
    public long count(String search, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por Login");
    }
  }

  /** Dublê de {@link AuthSessionStore}: guarda a última sessão inserida. */
  private static final class FakeSessionStore implements AuthSessionStore {

    private final UUID generatedId = UUID.randomUUID();
    private NewAuthSession inserted;

    @Override
    public UUID insert(NewAuthSession session) {
      inserted = session;
      return generatedId;
    }

    @Override
    public Optional<AuthSessionSnapshot> findActiveByTokenHash(String tokenHash) {
      throw new UnsupportedOperationException("findActiveByTokenHash não é usado por Login");
    }

    @Override
    public Optional<AuthSessionSnapshot> findActiveById(UUID id) {
      throw new UnsupportedOperationException("findActiveById não é usado por Login");
    }

    @Override
    public List<UserSessionSummary> listActiveByUser(UUID userId) {
      throw new UnsupportedOperationException("listActiveByUser não é usado por Login");
    }

    @Override
    public void touchLastSeen(UUID id, Instant lastSeenAt) {
      throw new UnsupportedOperationException("touchLastSeen não é usado por Login");
    }

    @Override
    public void revoke(UUID id, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException("revoke não é usado por Login");
    }
  }

  /** Dublê de {@link PasswordHasher}: controla o resultado e registra o hash conferido. */
  private static final class FakePasswordHasher implements PasswordHasher {

    private boolean passwordMatches;
    private boolean needsRehash;
    private String verifiedHash;

    @Override
    public String hash(String rawPassword) {
      return NEW_HASH;
    }

    @Override
    public boolean verify(String rawPassword, String passwordHash) {
      verifiedHash = passwordHash;
      return passwordMatches;
    }

    @Override
    public boolean needsRehash(String passwordHash) {
      return needsRehash;
    }
  }

  /** Dublê de {@link StoreLookup}: devolve a loja configurada com id fixo. */
  private static final class FakeStoreLookup implements StoreLookup {

    @Override
    public Optional<Store> findByCode(String code) {
      return Optional.of(new Store(STORE_ID, code, "Loja " + code, false, BigDecimal.ZERO));
    }

    @Override
    public Optional<Store> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por Login");
    }
  }
}
