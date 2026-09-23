package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.RoleStore;
import com.minimarket.users.application.UserSort;
import com.minimarket.users.application.UserSummary;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link UserRepository} contra PostgreSQL real (Dev Services). Cada teste roda em
 * transação revertida ao final ({@code @TestTransaction}).
 */
@QuarkusTest
class UserRepositoryTest extends IntegrationTestBase {

  @Inject UserRepository userRepository;

  @Inject RoleStore roleStore;

  @Inject EntityManager entityManager;

  @Test
  @TestTransaction
  @DisplayName("insere com id UUIDv7, normaliza o username e busca pelo username")
  void insertsAndFindsByUsername() {
    UserEntity user = new UserEntity("  Ana.Silva ", "hash", "Ana Silva");

    userRepository.insert(user);
    entityManager.flush();
    entityManager.clear();

    assertThat(user.getId()).isNotNull();
    assertThat(user.getStatus()).isEqualTo(UserEntity.STATUS_ACTIVE);
    assertThat(user.getCreatedAt()).isNotNull();
    assertThat(user.getUpdatedAt()).isNotNull();
    assertThat(user.getDeletedAt()).isNull();

    assertThat(userRepository.findByUsername("ANA.SILVA"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getId()).isEqualTo(user.getId());
              assertThat(found.getUsername()).isEqualTo("ana.silva");
              assertThat(found.getDisplayName()).isEqualTo("Ana Silva");
              assertThat(found.getPasswordHash()).isEqualTo("hash");
            });
  }

  @Test
  @TestTransaction
  @DisplayName("findById devolve o usuário existente e vazio para id desconhecido")
  void findsById() {
    UserEntity user = userRepository.insert(new UserEntity("bruno", "hash", "Bruno Lima"));
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.findById(user.getId()))
        .hasValueSatisfying(found -> assertThat(found.getUsername()).isEqualTo("bruno"));
    assertThat(userRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("insert(NewUser) traduz a violação do índice único de username em ConflictException")
  void translatesUsernameUniqueViolation() {
    userRepository.insert(new UserEntity("ana.silva", "hash", "Ana Silva"));
    entityManager.flush();

    assertThatThrownBy(
            () ->
                userRepository.insert(new NewUser("ana.silva", "Ana Duplicada", "hash", "ACTIVE")))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS));
  }

  @Test
  @TestTransaction
  @DisplayName("update grava a alteração e avança updated_at")
  void updates() {
    UserEntity user = userRepository.insert(new UserEntity("ana", "hash", "Ana"));
    entityManager.flush();
    entityManager.clear();

    UserEntity loaded = userRepository.findById(user.getId()).orElseThrow();
    Instant before = loaded.getUpdatedAt();
    loaded.setDisplayName("Ana Maria");

    userRepository.update(loaded);
    entityManager.flush();
    entityManager.clear();

    UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
    assertThat(reloaded.getDisplayName()).isEqualTo("Ana Maria");
    assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(before);
  }

  @Test
  @TestTransaction
  @DisplayName("updateDisplayName grava só o nome do usuário vivo e avança updated_at")
  void updatesDisplayName() {
    UserEntity user = userRepository.insert(new UserEntity("ana", "hash", "Ana"));
    entityManager.flush();
    entityManager.clear();
    Instant before = userRepository.findById(user.getId()).orElseThrow().getUpdatedAt();

    userRepository.updateDisplayName(user.getId(), "Ana Maria");
    entityManager.flush();
    entityManager.clear();

    UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
    assertThat(reloaded.getDisplayName()).isEqualTo("Ana Maria");
    assertThat(reloaded.getUsername()).isEqualTo("ana");
    assertThat(reloaded.getPasswordHash()).isEqualTo("hash");
    assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(before);
  }

  @Test
  @TestTransaction
  @DisplayName("updateDisplayName não grava nada em usuário soft-deletado nem em id desconhecido")
  void ignoresDeletedUserOnUpdate() {
    UserEntity deleted = userRepository.insert(new UserEntity("carla", "hash", "Carla"));
    entityManager.flush();
    userRepository.softDelete(deleted.getId());
    entityManager.flush();
    entityManager.clear();

    userRepository.updateDisplayName(deleted.getId(), "Carla Nova");
    userRepository.updateDisplayName(UUID.randomUUID(), "Ninguém");
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.findById(deleted.getId()))
        .hasValueSatisfying(found -> assertThat(found.getDisplayName()).isEqualTo("Carla"));
  }

  @Test
  @TestTransaction
  @DisplayName("soft delete grava deleted_at sem mudar status e some da busca")
  void softDeletes() {
    UserEntity user = userRepository.insert(new UserEntity("carla", "hash", "Carla Dias"));
    entityManager.flush();
    entityManager.clear();

    userRepository.softDelete(user.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.findByUsername("carla"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getDeletedAt()).isNotNull();
              assertThat(found.getStatus()).isEqualTo(UserEntity.STATUS_ACTIVE);
            });
    assertThat(userRepository.search(null, null, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::id)
        .doesNotContain(user.getId());
  }

  @Test
  @TestTransaction
  @DisplayName(
      "disable grava DISABLED + deleted_at e tira o usuário da busca, do detalhe e da checagem de username")
  void disablesUser() {
    UserEntity user = userRepository.insert(new UserEntity("desativado", "hash", "Desativado"));
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.disable(user.getId()))
        .hasValueSatisfying(
            summary -> {
              assertThat(summary.status()).isEqualTo(UserEntity.STATUS_DISABLED);
              assertThat(summary.roles()).isEmpty();
            });
    entityManager.flush();
    entityManager.clear();

    // O registro continua no banco (histórico preservado), mas com o acesso cortado: é o que o
    // login vai recusar quando existir (passo 204+).
    UserEntity raw = userRepository.findById(user.getId()).orElseThrow();
    assertThat(raw.getStatus()).isEqualTo(UserEntity.STATUS_DISABLED);
    assertThat(raw.getDeletedAt()).isNotNull();
    // As consultas que a aplicação usa hoje já não encontram o desativado.
    assertThat(userRepository.findSummaryById(user.getId())).isEmpty();
    assertThat(userRepository.existsByUsername("desativado")).isFalse();
    assertThat(userRepository.search("desativado", null, UserSort.USERNAME, true, 0, 10)).isEmpty();
    // Desativar de novo devolve vazio — o caso de uso traduz em 404.
    assertThat(userRepository.disable(user.getId())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("enable reativa o usuário: ACTIVE, deleted_at nulo e de volta à busca")
  void enablesUser() {
    UserEntity user = userRepository.insert(new UserEntity("reativado", "hash", "Reativado"));
    entityManager.flush();
    entityManager.clear();
    userRepository.disable(user.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.enable(user.getId()))
        .hasValueSatisfying(
            summary -> assertThat(summary.status()).isEqualTo(UserEntity.STATUS_ACTIVE));
    entityManager.flush();
    entityManager.clear();

    UserEntity raw = userRepository.findById(user.getId()).orElseThrow();
    assertThat(raw.getStatus()).isEqualTo(UserEntity.STATUS_ACTIVE);
    assertThat(raw.getDeletedAt()).isNull();
    assertThat(userRepository.findSummaryById(user.getId())).isPresent();
    assertThat(userRepository.existsByUsername("reativado")).isTrue();
    assertThat(userRepository.search("reativado", null, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::id)
        .containsExactly(user.getId());
  }

  @Test
  @TestTransaction
  @DisplayName(
      "enable em usuário já ativo é no-op; id desconhecido devolve vazio nos dois sentidos")
  void keepsActiveUserOnEnableAndIgnoresUnknownId() {
    UserEntity user = userRepository.insert(new UserEntity("ativo", "hash", "Ativo"));
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.enable(user.getId()))
        .hasValueSatisfying(
            summary -> assertThat(summary.status()).isEqualTo(UserEntity.STATUS_ACTIVE));
    assertThat(userRepository.enable(UUID.randomUUID())).isEmpty();
    assertThat(userRepository.disable(UUID.randomUUID())).isEmpty();
    entityManager.flush();
    entityManager.clear();

    UserEntity raw = userRepository.findById(user.getId()).orElseThrow();
    assertThat(raw.getStatus()).isEqualTo(UserEntity.STATUS_ACTIVE);
    assertThat(raw.getDeletedAt()).isNull();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "search filtra por texto (sem diferenciar maiúsculas) e por status, ordenado por username")
  void searches() {
    userRepository.insert(new UserEntity("ana", "hash", "Ana Souza"));
    userRepository.insert(new UserEntity("bruno", "hash", "Bruno Lima"));
    UserEntity disabled = userRepository.insert(new UserEntity("carla", "hash", "Carla Dias"));
    disabled.setStatus(UserEntity.STATUS_DISABLED);
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.search(null, null, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::username)
        .containsExactly("ana", "bruno", "carla");
    assertThat(userRepository.search("LIMA", null, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::username)
        .containsExactly("bruno");
    assertThat(userRepository.search("souza", null, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::username)
        .containsExactly("ana");
    assertThat(userRepository.search(null, true, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::username)
        .containsExactly("ana", "bruno");
    assertThat(userRepository.search(null, false, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::username)
        .containsExactly("carla");
    assertThat(userRepository.search("   ", null, UserSort.USERNAME, true, 0, 10))
        .extracting(UserSummary::username)
        .containsExactly("ana", "bruno", "carla");

    assertThat(userRepository.count(null, null)).isEqualTo(3);
    assertThat(userRepository.count("LIMA", null)).isEqualTo(1);
    assertThat(userRepository.count(null, true)).isEqualTo(2);
  }

  @Test
  @TestTransaction
  @DisplayName("search pagina por offset/limit")
  void searchesWithPagination() {
    for (int i = 1; i <= 5; i++) {
      userRepository.insert(new UserEntity("user" + i, "hash", "Usuario " + i));
    }
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.search(null, null, UserSort.USERNAME, true, 0, 2))
        .extracting(UserSummary::username)
        .containsExactly("user1", "user2");
    assertThat(userRepository.search(null, null, UserSort.USERNAME, true, 1, 2))
        .extracting(UserSummary::username)
        .containsExactly("user3", "user4");
    assertThat(userRepository.search(null, null, UserSort.USERNAME, true, 2, 2))
        .extracting(UserSummary::username)
        .containsExactly("user5");
  }

  @Test
  @TestTransaction
  @DisplayName(
      "findAuthStateByUsername traz hash, status, roles e permissões efetivas, e enxerga o soft-deletado")
  void findsAuthStateByUsername() {
    UUID userId = userRepository.insert(new NewUser("ana.auth", "Ana Auth", "hash-auth", "ACTIVE"));
    roleStore.assignRoles(userId, List.of("OPERADOR"));
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.findAuthStateByUsername("ANA.AUTH"))
        .hasValueSatisfying(
            state -> {
              assertThat(state.id()).isEqualTo(userId);
              assertThat(state.username()).isEqualTo("ana.auth");
              assertThat(state.displayName()).isEqualTo("Ana Auth");
              assertThat(state.passwordHash()).isEqualTo("hash-auth");
              assertThat(state.status()).isEqualTo(UserEntity.STATUS_ACTIVE);
              assertThat(state.deletedAt()).isNull();
              assertThat(state.failedLoginAttempts()).isZero();
              assertThat(state.lockedUntil()).isNull();
              assertThat(state.mustChangePassword()).isFalse();
              assertThat(state.roles()).containsExactly("OPERADOR");
              assertThat(state.permissions()).contains("sale.create").doesNotContain("user.write");
            });
    assertThat(userRepository.findAuthStateByUsername("nao.existe")).isEmpty();

    // O login precisa enxergar o soft-deletado para recusar com a mensagem genérica (passo 204a).
    userRepository.softDelete(userId);
    entityManager.flush();
    entityManager.clear();
    assertThat(userRepository.findAuthStateByUsername("ana.auth"))
        .hasValueSatisfying(state -> assertThat(state.deletedAt()).isNotNull());
  }

  @Test
  @TestTransaction
  @DisplayName("recordSuccessfulLogin grava last_login_at e zera contador e lock")
  void recordsSuccessfulLogin() {
    UUID userId = userRepository.insert(new NewUser("ana.login", "Ana Login", "hash", "ACTIVE"));
    entityManager.flush();
    entityManager.clear();
    entityManager
        .createQuery(
            "update UserEntity u set u.failedLoginAttempts = 3, u.lockedUntil = :until"
                + " where u.id = :id")
        .setParameter("until", Instant.now().plus(Duration.ofMinutes(5)))
        .setParameter("id", userId)
        .executeUpdate();
    entityManager.flush();
    entityManager.clear();
    Instant loginAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    userRepository.recordSuccessfulLogin(userId, loginAt);
    entityManager.flush();
    entityManager.clear();

    UserEntity reloaded = userRepository.findById(userId).orElseThrow();
    assertThat(reloaded.getLastLoginAt()).isEqualTo(loginAt);
    assertThat(reloaded.getFailedLoginAttempts()).isZero();
    assertThat(reloaded.getLockedUntil()).isNull();
    // Id desconhecido é no-op, como nas outras escritas por id.
    userRepository.recordSuccessfulLogin(UUID.randomUUID(), loginAt);
  }

  @Test
  @TestTransaction
  @DisplayName("recordFailedLogin grava contador e lock e clearLoginFailures zera os dois")
  void recordsFailedLoginAndClearsFailures() {
    UUID userId = userRepository.insert(new NewUser("ana.lock", "Ana Lock", "hash", "ACTIVE"));
    entityManager.flush();
    entityManager.clear();
    Instant lockedUntil = Instant.now().plus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.MICROS);

    userRepository.recordFailedLogin(userId, 5, lockedUntil);
    entityManager.flush();
    entityManager.clear();

    UserEntity locked = userRepository.findById(userId).orElseThrow();
    assertThat(locked.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(locked.getLockedUntil()).isEqualTo(lockedUntil);
    assertThat(userRepository.findAuthStateByUsername("ana.lock"))
        .hasValueSatisfying(
            state -> {
              assertThat(state.failedLoginAttempts()).isEqualTo(5);
              assertThat(state.lockedUntil()).isEqualTo(lockedUntil);
            });

    userRepository.clearLoginFailures(userId);
    entityManager.flush();
    entityManager.clear();

    UserEntity cleared = userRepository.findById(userId).orElseThrow();
    assertThat(cleared.getFailedLoginAttempts()).isZero();
    assertThat(cleared.getLockedUntil()).isNull();
    // Id desconhecido é no-op, como nas outras escritas por id.
    userRepository.recordFailedLogin(UUID.randomUUID(), 1, null);
    userRepository.clearLoginFailures(UUID.randomUUID());
  }

  @Test
  @TestTransaction
  @DisplayName("updatePasswordHash troca só o hash, sem exigir troca de senha")
  void updatesPasswordHash() {
    UUID userId =
        userRepository.insert(new NewUser("ana.rehash", "Ana Rehash", "hash-antigo", "ACTIVE"));
    entityManager.flush();
    entityManager.clear();

    userRepository.updatePasswordHash(userId, "hash-novo");
    entityManager.flush();
    entityManager.clear();

    UserEntity reloaded = userRepository.findById(userId).orElseThrow();
    assertThat(reloaded.getPasswordHash()).isEqualTo("hash-novo");
    assertThat(reloaded.isMustChangePassword()).isFalse();
  }
}
