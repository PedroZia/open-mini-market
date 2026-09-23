package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.domain.NotFoundException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link PermissionRepository} contra PostgreSQL real (Dev Services). Cada teste roda
 * em transação revertida ao final ({@code @TestTransaction}), então trocar as permissões de uma
 * role semeada por migration não vaza para os outros testes.
 */
@QuarkusTest
class PermissionRepositoryTest extends IntegrationTestBase {

  /** Mapa inicial de §4.5 para OPERADOR, o mesmo semeado por {@code V3__rbac.sql}. */
  private static final Set<String> OPERADOR_PERMISSIONS =
      Set.of(
          "product.read",
          "sale.create",
          "payment.add",
          "sale.complete",
          "cash.read",
          "cash.open",
          "cash.close",
          "customer.read",
          "customer.write",
          "stock.read");

  @Inject UserRepository userRepository;

  @Inject RoleRepository roleRepository;

  @Inject PermissionRepository permissionRepository;

  @Test
  @TestTransaction
  @DisplayName(
      "effectivePermissions de um OPERADOR é exatamente o conjunto de 10 permissões da role")
  void resolvesOperatorEffectivePermissions() {
    UserEntity operator = insertUser("operador.permissoes");
    roleRepository.assignRoles(operator.getId(), List.of("OPERADOR"));

    Set<String> effective = permissionRepository.effectivePermissions(operator.getId());

    assertThat(effective).containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS).hasSize(10);
    assertThat(List.copyOf(effective)).isSorted();
  }

  @Test
  @TestTransaction
  @DisplayName("effectivePermissions une OPERADOR e GERENTE sem repetir (22 distintas)")
  void unionsPermissionsOfMultipleRoles() {
    UserEntity user = insertUser("dois.papeis");
    roleRepository.assignRoles(user.getId(), List.of("OPERADOR", "GERENTE"));

    assertThat(permissionRepository.effectivePermissions(user.getId()))
        .hasSize(22)
        .containsAll(OPERADOR_PERMISSIONS)
        .contains("sale.discount.apply", "audit.read", "report.read");
  }

  @Test
  @TestTransaction
  @DisplayName("effectivePermissions de usuário sem roles ou inexistente é vazio")
  void returnsEmptyForUserWithoutRoles() {
    UserEntity user = insertUser("sem.permissoes");

    assertThat(permissionRepository.effectivePermissions(user.getId())).isEmpty();
    assertThat(permissionRepository.effectivePermissions(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("permissionsOf devolve as 10 permissões do OPERADOR e falha para role inexistente")
  void listsPermissionsOfRole() {
    Set<String> permissions = permissionRepository.permissionsOf("OPERADOR");

    assertThat(permissions).containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS).hasSize(10);
    assertThat(List.copyOf(permissions)).isSorted();
    assertThatThrownBy(() -> permissionRepository.permissionsOf("FANTASMA"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("FANTASMA");
  }

  @Test
  @TestTransaction
  @DisplayName(
      "replacePermissions troca a role e a mudança reflete nas permissões efetivas do usuário")
  void replacesPermissionsAndReflectsOnEffectivePermissions() {
    UserEntity operator = insertUser("operador.troca");
    roleRepository.assignRoles(operator.getId(), List.of("OPERADOR"));

    permissionRepository.replacePermissions("OPERADOR", List.of("stock.adjust", "report.read"));

    assertThat(permissionRepository.permissionsOf("OPERADOR"))
        .containsExactly("report.read", "stock.adjust");
    assertThat(permissionRepository.effectivePermissions(operator.getId()))
        .containsExactly("report.read", "stock.adjust");
  }

  @Test
  @TestTransaction
  @DisplayName("replacePermissions com lista vazia zera as permissões da role")
  void clearsPermissionsOfRole() {
    UserEntity operator = insertUser("operador.zerado");
    roleRepository.assignRoles(operator.getId(), List.of("OPERADOR"));

    permissionRepository.replacePermissions("OPERADOR", List.of());

    assertThat(permissionRepository.permissionsOf("OPERADOR")).isEmpty();
    assertThat(permissionRepository.effectivePermissions(operator.getId())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName(
      "replacePermissions com permissão ou role inexistente lança NotFoundException sem gravar")
  void rejectsUnknownPermissionOrRole() {
    assertThatThrownBy(
            () ->
                permissionRepository.replacePermissions(
                    "OPERADOR", List.of("product.read", "nao.existe")))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("nao.existe");
    assertThatThrownBy(() -> permissionRepository.replacePermissions("FANTASMA", List.of()))
        .isInstanceOf(NotFoundException.class);

    assertThat(permissionRepository.permissionsOf("OPERADOR"))
        .containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS);
  }

  private UserEntity insertUser(String username) {
    return userRepository.insert(new UserEntity(username, "hash", "Usuário " + username));
  }
}
