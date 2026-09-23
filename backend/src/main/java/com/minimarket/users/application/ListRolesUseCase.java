package com.minimarket.users.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Lista o catálogo de roles com as permissões de cada uma (§9.3 do plano, passo 114). Leitura pura,
 * sem transação própria — igual a {@link GetUserUseCase}.
 */
@ApplicationScoped
public class ListRolesUseCase {

  @Inject RoleAdminStore roleAdminStore;

  public List<RoleSummary> execute() {
    return roleAdminStore.listRoles();
  }
}
