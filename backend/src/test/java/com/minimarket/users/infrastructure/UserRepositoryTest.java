package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserSort;
import com.minimarket.users.application.UserSummary;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
}
