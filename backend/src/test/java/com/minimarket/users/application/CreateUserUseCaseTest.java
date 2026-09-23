package com.minimarket.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link CreateUserUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão.
 */
class CreateUserUseCaseTest {

  private static final String PASSWORD = "senha-secreta";
  private static final String HASH = "$argon2id$v=19$m=19456,t=2,p=1$hash-fake";

  private final FakeUserStore userStore = new FakeUserStore();
  private final FakeRoleStore roleStore = new FakeRoleStore();
  private final FakePasswordHasher passwordHasher = new FakePasswordHasher();

  private CreateUserUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new CreateUserUseCase();
    useCase.userStore = userStore;
    useCase.roleStore = roleStore;
    useCase.passwordHasher = passwordHasher;
  }

  @Test
  @DisplayName("cria usuário ACTIVE com username normalizado, hash da senha e roles atribuídas")
  void createsUser() {
    CreateUserResult result =
        useCase.execute(
            new CreateUserCommand(
                "  Maria.Silva ",
                "Maria Silva",
                PASSWORD,
                List.of(" ADMIN ", "OPERADOR", "ADMIN")));

    assertThat(result.username()).isEqualTo("maria.silva");
    assertThat(result.displayName()).isEqualTo("Maria Silva");
    assertThat(result.roles()).containsExactly("ADMIN", "OPERADOR");
    assertThat(result.id()).isEqualTo(userStore.generatedId);

    assertThat(userStore.inserted.username()).isEqualTo("maria.silva");
    assertThat(userStore.inserted.displayName()).isEqualTo("Maria Silva");
    assertThat(userStore.inserted.passwordHash()).isEqualTo(HASH);
    assertThat(userStore.inserted.status()).isEqualTo("ACTIVE");

    assertThat(passwordHasher.hashedInput).isEqualTo(PASSWORD);
    assertThat(roleStore.userId).isEqualTo(userStore.generatedId);
    assertThat(roleStore.roleCodes).containsExactly("ADMIN", "OPERADOR");
  }

  @Test
  @DisplayName("username duplicado lança ConflictException sem inserir nem hashear")
  void rejectsDuplicateUsername() {
    userStore.existing.add("maria.silva");

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new CreateUserCommand("Maria.Silva", "Maria Silva", PASSWORD, List.of())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("maria.silva");

    assertThat(userStore.inserted).isNull();
    assertThat(passwordHasher.hashedInput).isNull();
    assertThat(roleStore.userId).isNull();
  }

  @Test
  @DisplayName("senha com menos de 8 caracteres, nula ou vazia é erro de validação")
  void rejectsWeakPassword() {
    assertThatThrownBy(() -> useCase.execute(commandWithPassword("1234567")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    assertThatThrownBy(() -> useCase.execute(commandWithPassword(null)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> useCase.execute(commandWithPassword("")))
        .isInstanceOf(BusinessException.class);

    assertThat(userStore.inserted).isNull();
    assertThat(passwordHasher.hashedInput).isNull();
  }

  @Test
  @DisplayName("resultado não tem campo de senha nem de hash")
  void resultDoesNotCarryCredentials() {
    CreateUserResult result = useCase.execute(commandWithPassword(PASSWORD));

    assertThat(CreateUserResult.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactlyInAnyOrder("id", "username", "displayName", "roles");
    assertThat(result.toString()).doesNotContain(PASSWORD).doesNotContain(HASH);
  }

  private static CreateUserCommand commandWithPassword(String password) {
    return new CreateUserCommand("ana.souza", "Ana Souza", password, List.of());
  }

  /** Dublê de {@link UserStore}: simula usernames existentes e guarda a última inserção. */
  private static final class FakeUserStore implements UserStore {

    private final Set<String> existing = new HashSet<>();
    private final UUID generatedId = UUID.randomUUID();
    private NewUser inserted;

    @Override
    public boolean existsByUsername(String username) {
      return existing.contains(username);
    }

    @Override
    public Optional<UserAuthState> findAuthStateById(UUID id) {
      throw new UnsupportedOperationException("findAuthStateById não é usado por CreateUser");
    }

    @Override
    public UUID insert(NewUser user) {
      inserted = user;
      return generatedId;
    }

    @Override
    public void updateDisplayName(UUID id, String displayName) {
      throw new UnsupportedOperationException("updateDisplayName não é usado por CreateUser");
    }

    @Override
    public Optional<UserSummary> findSummaryById(UUID id) {
      throw new UnsupportedOperationException("findSummaryById não é usado por CreateUser");
    }

    @Override
    public Optional<UserSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por CreateUser");
    }

    @Override
    public Optional<UserSummary> enable(UUID id) {
      throw new UnsupportedOperationException("enable não é usado por CreateUser");
    }

    @Override
    public Optional<UserSummary> resetPassword(UUID id, String passwordHash) {
      throw new UnsupportedOperationException("resetPassword não é usado por CreateUser");
    }

    @Override
    public void requirePasswordChange(UUID id) {
      throw new UnsupportedOperationException("requirePasswordChange não é usado por CreateUser");
    }

    @Override
    public List<UserSummary> search(
        String search, Boolean active, UserSort sort, boolean ascending, int page, int size) {
      throw new UnsupportedOperationException("search não é usado por CreateUser");
    }

    @Override
    public long count(String search, Boolean active) {
      throw new UnsupportedOperationException("count não é usado por CreateUser");
    }

    @Override
    public Optional<UserAuthState> findAuthStateByUsername(String username) {
      throw new UnsupportedOperationException("findAuthStateByUsername não é usado por CreateUser");
    }

    @Override
    public void recordSuccessfulLogin(UUID id, Instant loginAt) {
      throw new UnsupportedOperationException("recordSuccessfulLogin não é usado por CreateUser");
    }

    @Override
    public void recordFailedLogin(UUID id, int failedLoginAttempts, Instant lockedUntil) {
      throw new UnsupportedOperationException("recordFailedLogin não é usado por CreateUser");
    }

    @Override
    public void clearLoginFailures(UUID id) {
      throw new UnsupportedOperationException("clearLoginFailures não é usado por CreateUser");
    }

    @Override
    public void updatePasswordHash(UUID id, String passwordHash) {
      throw new UnsupportedOperationException("updatePasswordHash não é usado por CreateUser");
    }
  }

  /** Dublê de {@link RoleStore}: guarda o usuário e os papéis da última atribuição. */
  private static final class FakeRoleStore implements RoleStore {

    private UUID userId;
    private List<String> roleCodes;

    @Override
    public void assignRoles(UUID userId, Collection<String> roleCodes) {
      this.userId = userId;
      this.roleCodes = List.copyOf(roleCodes);
    }

    @Override
    public Set<String> findUnknownCodes(Collection<String> roleCodes) {
      throw new UnsupportedOperationException("findUnknownCodes não é usado por CreateUser");
    }

    @Override
    public long countActiveUsersWithRole(String roleCode) {
      throw new UnsupportedOperationException(
          "countActiveUsersWithRole não é usado por CreateUser");
    }
  }

  /** Dublê de {@link PasswordHasher}: devolve hash fixo e registra a senha crua recebida. */
  private static final class FakePasswordHasher implements PasswordHasher {

    private String hashedInput;

    @Override
    public String hash(String rawPassword) {
      hashedInput = rawPassword;
      return HASH;
    }

    @Override
    public boolean verify(String rawPassword, String passwordHash) {
      throw new UnsupportedOperationException("verify não é usado por CreateUser");
    }

    @Override
    public boolean needsRehash(String passwordHash) {
      throw new UnsupportedOperationException("needsRehash não é usado por CreateUser");
    }
  }
}
