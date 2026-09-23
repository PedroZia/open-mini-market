package com.minimarket.users.application;

import java.util.Collection;
import java.util.UUID;

/** Porta de atribuição de papéis; o adaptador JPA fica em {@code users.infrastructure}. */
public interface RoleStore {

  /**
   * Substitui os papéis do usuário pelos códigos informados. Usuário ou código inexistente derruba
   * a operação com {@code NotFoundException} — regra do adaptador, sem gravar nada.
   */
  void assignRoles(UUID userId, Collection<String> roleCodes);
}
