package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.domain.NotFoundException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link RoleRepository} contra PostgreSQL real (Dev Services). Cada teste roda em
 * transação revertida ao final ({@code @TestTransaction}), então usar as roles semeadas por
 * migration não vaza para os outros testes.
 */
@QuarkusTest
class RoleRepositoryTest extends IntegrationTestBase {

  @Inject UserRepository userRepository;

  @Inject RoleRepository roleRepository;

  @Test
  @TestTransaction
  @DisplayName("rolesOf devolve os códigos em ordem e vazio para usuário sem roles ou inexistente")
  void listsRolesOfUser() {
    UserEntity operator = insertUser("operador.rbac");
    UserEntity withoutRoles = insertUser("sem.roles");

    roleRepository.assignRoles(operator.getId(), List.of("OPERADOR", "ADMIN"));

    assertThat(roleRepository.rolesOf(operator.getId()))
        .containsExactly("ADMIN", "OPERADOR")
        .isSorted();
    assertThat(roleRepository.rolesOf(withoutRoles.getId())).isEmpty();
    assertThat(roleRepository.rolesOf(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("assignRoles substitui o conjunto de roles do usuário")
  void replacesRolesOfUser() {
    UserEntity user = insertUser("gerente.rbac");
    roleRepository.assignRoles(user.getId(), List.of("OPERADOR"));
    assertThat(roleRepository.rolesOf(user.getId())).containsExactly("OPERADOR");

    roleRepository.assignRoles(user.getId(), List.of("GERENTE", "ADMIN"));

    assertThat(roleRepository.rolesOf(user.getId())).containsExactly("ADMIN", "GERENTE");
  }

  @Test
  @TestTransaction
  @DisplayName("assignRoles com lista vazia remove todas as roles do usuário")
  void removesAllRoles() {
    UserEntity user = insertUser("sem.papel.rbac");
    roleRepository.assignRoles(user.getId(), List.of("OPERADOR"));

    roleRepository.assignRoles(user.getId(), List.of());

    assertThat(roleRepository.rolesOf(user.getId())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("assignRoles com role ou usuário inexistente lança NotFoundException sem gravar")
  void rejectsUnknownRoleOrUser() {
    UserEntity user = insertUser("erro.rbac");

    assertThatThrownBy(
            () -> roleRepository.assignRoles(user.getId(), List.of("OPERADOR", "FANTASMA")))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("FANTASMA");
    assertThatThrownBy(() -> roleRepository.assignRoles(UUID.randomUUID(), List.of("OPERADOR")))
        .isInstanceOf(NotFoundException.class);

    assertThat(roleRepository.rolesOf(user.getId())).isEmpty();
  }

  private UserEntity insertUser(String username) {
    return userRepository.insert(new UserEntity(username, "hash", "Usuário " + username));
  }
}
