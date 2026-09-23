package com.minimarket.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.auth.domain.SessionClient;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserAuthState;
import com.minimarket.users.application.UserSort;
import com.minimarket.users.application.UserStore;
import com.minimarket.users.application.UserSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link AuthenticateSessionUseCase} (passo 209), sem Quarkus e sem banco: as
 * portas são dublês escritos à mão e o relógio é mutável, para a sessão envelhecer sem esperar —
 * expiração absoluta, janela de inatividade por cliente e renovação de {@code last_seen_at} são
 * conferidas no instante controlado.
 */
class AuthenticateSessionUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
  private static final Duration ABSOLUTE_EXPIRATION = Duration.ofHours(12);
  private static final Duration IDLE_WEB = Duration.ofMinutes(30);
  private static final Duration IDLE_TUI = Duration.ofHours(8);
  private static final long TOUCH_INTERVAL_SECONDS = 60;
  private static final String USERNAME = "ana.souza";
  private static final UUID SESSION_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID USER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");

  private final MutableClock clock = new MutableClock(NOW);
  private final FakeSessionStore sessionStore = new FakeSessionStore();
  private final FakeUserStore userStore = new FakeUserStore();

  private AuthenticateSessionUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new AuthenticateSessionUseCase();
    useCase.sessionStore = sessionStore;
    useCase.userStore = userStore;
    useCase.clock = clock;
    useCase.touchIntervalSeconds = TOUCH_INTERVAL_SECONDS;
    useCase.idleWeb = IDLE_WEB;
    useCase.idleTui = IDLE_TUI;
  }

  @Test
  @DisplayName("sessão WEB inativa por 29 min autentica e renova o last_seen_at do relógio")
  void authenticatesWebSessionWithinIdleWindow() {
    sessionStore.withSession(session(SessionClient.WEB, NOW.minus(Duration.ofMinutes(29))));

    AuthenticatedSession result = useCase.execute("hash-do-token");

    assertThat(result.sessionId()).isEqualTo(SESSION_ID);
    assertThat(result.username()).isEqualTo(USERNAME);
    assertThat(result.roles()).containsExactly("OPERADOR");
    assertThat(result.permissions()).containsExactly("sale.create");
    assertThat(sessionStore.touches).containsExactly(NOW);
    assertThat(sessionStore.session.lastSeenAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName(
      "sessão WEB inativa por 31 min recusa com 401 SESSION_IDLE_TIMEOUT sem tocar a sessão")
  void rejectsWebSessionIdleBeyondLimit() {
    sessionStore.withSession(session(SessionClient.WEB, NOW.minus(Duration.ofMinutes(31))));

    assertThatThrownBy(() -> useCase.execute("hash-do-token"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.SESSION_IDLE_TIMEOUT);
              assertThat(exception.getMessage()).contains("inatividade");
            });

    // O idle vence antes do RBAC: sessão vencida não é tocada nem consulta o usuário de novo.
    assertThat(sessionStore.touches).isEmpty();
    assertThat(sessionStore.session.lastSeenAt()).isEqualTo(NOW.minus(Duration.ofMinutes(31)));
    assertThat(userStore.reads).isZero();
  }

  @Test
  @DisplayName("sessão TUI inativa por 7 h autentica: o limite da TUI é 8 h")
  void authenticatesTuiSessionWithinIdleWindow() {
    sessionStore.withSession(session(SessionClient.TUI, NOW.minus(Duration.ofHours(7))));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);
  }

  @Test
  @DisplayName("sessão TUI inativa por 9 h recusa com 401 SESSION_IDLE_TIMEOUT")
  void rejectsTuiSessionIdleBeyondLimit() {
    sessionStore.withSession(session(SessionClient.TUI, NOW.minus(Duration.ofHours(9))));

    assertThatThrownBy(() -> useCase.execute("hash-do-token"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.SESSION_IDLE_TIMEOUT));
  }

  @Test
  @DisplayName("o limite de inatividade é o do cliente da sessão: 2 h derrubam a WEB e não a TUI")
  void appliesTheIdleLimitOfTheSessionClient() {
    Instant twoHoursAgo = NOW.minus(Duration.ofHours(2));
    sessionStore.withSession(session(SessionClient.WEB, twoHoursAgo));

    assertThatThrownBy(() -> useCase.execute("hash-do-token"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.SESSION_IDLE_TIMEOUT));

    sessionStore.withSession(session(SessionClient.TUI, twoHoursAgo));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);
  }

  @Test
  @DisplayName("no limite exato a sessão ainda vale: só a inatividade além do limite recusa")
  void treatsExactlyTheLimitAsStillActive() {
    sessionStore.withSession(session(SessionClient.WEB, NOW.minus(IDLE_WEB)));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);
  }

  @Test
  @DisplayName("expiração absoluta vence o idle: sessão vencida responde SESSION_EXPIRED, não idle")
  void rejectsExpiredSessionBeforeIdleCheck() {
    AuthSessionSnapshot expired =
        session(SessionClient.WEB, NOW.minus(Duration.ofHours(9)), NOW.minusSeconds(1));
    sessionStore.withSession(expired);

    assertThatThrownBy(() -> useCase.execute("hash-do-token"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
              assertThat(exception.getMessage()).doesNotContain("inatividade");
            });
    assertThat(sessionStore.touches).isEmpty();
  }

  @Test
  @DisplayName("requisição dentro do intervalo não toca a sessão")
  void doesNotTouchWithinTheInterval() {
    Instant seenTwentySecondsAgo = NOW.minusSeconds(20);
    sessionStore.withSession(session(SessionClient.WEB, seenTwentySecondsAgo));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);
    assertThat(sessionStore.touches).isEmpty();
    assertThat(sessionStore.session.lastSeenAt()).isEqualTo(seenTwentySecondsAgo);
  }

  @Test
  @DisplayName("uso anterior ao limite renova o marco: a sessão segue viva 20 min depois")
  void renewsIdleWindowOnUse() {
    sessionStore.withSession(session(SessionClient.WEB, NOW.minus(Duration.ofMinutes(25))));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);

    // 20 min depois do uso: sem a renovação a sessão estaria parada há 45 min e já teria vencido.
    clock.set(NOW.plus(Duration.ofMinutes(20)));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);
    assertThat(sessionStore.session.lastSeenAt()).isEqualTo(clock.instant());
  }

  @Test
  @DisplayName("uso renova last_seen_at e nunca estende expires_at")
  void neverExtendsAbsoluteExpiration() {
    // Sessão perto da expiração absoluta (2 min) e vista há 2 min: ainda vale, sem renovar a idade.
    Instant expiresAt = NOW.plus(Duration.ofMinutes(2));
    sessionStore.withSession(
        session(SessionClient.WEB, NOW.minus(Duration.ofMinutes(2)), expiresAt));

    assertThat(useCase.execute("hash-do-token").username()).isEqualTo(USERNAME);

    assertThat(sessionStore.touches).containsExactly(NOW);
    assertThat(sessionStore.session.lastSeenAt()).isEqualTo(NOW);
    assertThat(sessionStore.session.expiresAt()).isEqualTo(expiresAt);
  }

  /** Sessão que foi vista pela última vez em {@code lastSeenAt} e expira 12 h depois disso. */
  private static AuthSessionSnapshot session(SessionClient client, Instant lastSeenAt) {
    return session(client, lastSeenAt, lastSeenAt.plus(ABSOLUTE_EXPIRATION));
  }

  /** Sessão com o instante de expiração informado (o touch nunca o altera). */
  private static AuthSessionSnapshot session(
      SessionClient client, Instant lastSeenAt, Instant expiresAt) {
    return new AuthSessionSnapshot(
        SESSION_ID, USER_ID, client, STORE_ID, null, lastSeenAt, expiresAt);
  }

  /** Relógio controlado pelos testes: o caso de uso lê o instante, o teste o move. */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    /** Move o relógio: o teste faz a sessão envelhecer sem esperar. */
    private void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  /**
   * Dublê de {@link AuthSessionStore}: a sessão devolvida é substituída a cada toque, como o {@code
   * update} faria no banco — e o teste vê {@code last_seen_at} e {@code expires_at} depois.
   */
  private static final class FakeSessionStore implements AuthSessionStore {

    private final List<Instant> touches = new ArrayList<>();
    private AuthSessionSnapshot session;

    private void withSession(AuthSessionSnapshot session) {
      this.session = session;
    }

    @Override
    public UUID insert(NewAuthSession session) {
      throw new UnsupportedOperationException("insert não é usado por AuthenticateSession");
    }

    @Override
    public Optional<AuthSessionSnapshot> findActiveByTokenHash(String tokenHash) {
      return Optional.ofNullable(session);
    }

    @Override
    public Optional<AuthSessionSnapshot> findActiveById(UUID id) {
      throw new UnsupportedOperationException("findActiveById não é usado por AuthenticateSession");
    }

    @Override
    public List<UserSessionSummary> listActiveByUser(UUID userId) {
      throw new UnsupportedOperationException(
          "listActiveByUser não é usado por AuthenticateSession");
    }

    @Override
    public void touchLastSeen(UUID id, Instant lastSeenAt) {
      touches.add(lastSeenAt);
      session =
          new AuthSessionSnapshot(
              session.id(),
              session.userId(),
              session.client(),
              session.storeId(),
              session.cashRegisterId(),
              lastSeenAt,
              session.expiresAt());
    }

    @Override
    public void revoke(UUID id, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException("revoke não é usado por AuthenticateSession");
    }

    @Override
    public int revokeAllByUser(UUID userId, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException(
          "revokeAllByUser não é usado por AuthenticateSession");
    }

    @Override
    public int revokeAllByUserExcept(
        UUID userId, UUID sessionId, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException(
          "revokeAllByUserExcept não é usado por AuthenticateSession");
    }
  }

  /** Dublê de {@link UserStore}: só o RBAC efetivo da autenticação por token é usado aqui. */
  private static final class FakeUserStore implements UserStore {

    private int reads;

    @Override
    public Optional<UserAuthState> findAuthStateById(UUID id) {
      reads++;
      return Optional.of(
          new UserAuthState(
              USER_ID,
              USERNAME,
              "Ana Souza",
              "$argon2id$v=19$m=19456,t=2,p=1$hash-guardado",
              "ACTIVE",
              null,
              0,
              null,
              false,
              List.of("OPERADOR"),
              Set.of("sale.create")));
    }

    @Override
    public boolean existsByUsername(String username) {
      throw new UnsupportedOperationException(
          "existsByUsername não é usado por AuthenticateSession");
    }

    @Override
    public UUID insert(NewUser user) {
      throw new UnsupportedOperationException("insert não é usado por AuthenticateSession");
    }

    @Override
    public void updateDisplayName(UUID id, String displayName) {
      throw new UnsupportedOperationException(
          "updateDisplayName não é usado por AuthenticateSession");
    }

    @Override
    public Optional<UserSummary> findSummaryById(UUID id) {
      throw new UnsupportedOperationException(
          "findSummaryById não é usado por AuthenticateSession");
    }

    @Override
    public Optional<UserSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por AuthenticateSession");
    }

    @Override
    public Optional<UserSummary> enable(UUID id) {
      throw new UnsupportedOperationException("enable não é usado por AuthenticateSession");
    }

    @Override
    public Optional<UserSummary> resetPassword(UUID id, String passwordHash) {
      throw new UnsupportedOperationException("resetPassword não é usado por AuthenticateSession");
    }

    @Override
    public void requirePasswordChange(UUID id) {
      throw new UnsupportedOperationException(
          "requirePasswordChange não é usado por AuthenticateSession");
    }

    @Override
    public List<UserSummary> search(
        String search, Boolean active, UserSort sort, boolean ascending, int page, int size) {
      throw new UnsupportedOperationException("search não é usado por AuthenticateSession");
    }

    @Override
    public long count(String search, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por AuthenticateSession");
    }

    @Override
    public Optional<UserAuthState> findAuthStateByUsername(String username) {
      throw new UnsupportedOperationException(
          "findAuthStateByUsername não é usado por AuthenticateSession");
    }

    @Override
    public void recordSuccessfulLogin(UUID id, Instant loginAt) {
      throw new UnsupportedOperationException(
          "recordSuccessfulLogin não é usado por AuthenticateSession");
    }

    @Override
    public void recordFailedLogin(UUID id, int failedLoginAttempts, Instant lockedUntil) {
      throw new UnsupportedOperationException(
          "recordFailedLogin não é usado por AuthenticateSession");
    }

    @Override
    public void clearLoginFailures(UUID id) {
      throw new UnsupportedOperationException(
          "clearLoginFailures não é usado por AuthenticateSession");
    }

    @Override
    public void updatePasswordHash(UUID id, String passwordHash) {
      throw new UnsupportedOperationException(
          "updatePasswordHash não é usado por AuthenticateSession");
    }

    @Override
    public void changeOwnPassword(UUID id, String passwordHash) {
      throw new UnsupportedOperationException(
          "changeOwnPassword não é usado por AuthenticateSession");
    }
  }
}
