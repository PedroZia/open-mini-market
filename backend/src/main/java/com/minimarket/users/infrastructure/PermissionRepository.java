package com.minimarket.users.infrastructure;

import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador JPA de {@code role_permissions} (§5.3 do plano): as permissões de uma role e as
 * efetivas de um usuário (união das roles). Sem {@code @Transactional} — a transação é do caso de
 * uso (§2.2, regra 6).
 *
 * <p>Devolve códigos, nunca entidades: a fronteira com o caso de uso é só String, então JPA não
 * vaza para {@code application} nem para JSON.
 */
@ApplicationScoped
public class PermissionRepository {

  @Inject EntityManager entityManager;

  /** Códigos distintos das permissões do usuário em ordem alfabética; sem roles, conjunto vazio. */
  public Set<String> effectivePermissions(UUID userId) {
    return new LinkedHashSet<>(
        entityManager
            .createQuery(
                "select distinct p.code from UserEntity u"
                    + " join u.roles r join r.permissions p"
                    + " where u.id = :userId order by p.code",
                String.class)
            .setParameter("userId", userId)
            .getResultList());
  }

  /** Códigos das permissões da role em ordem alfabética; role inexistente → NotFoundException. */
  public Set<String> permissionsOf(String roleCode) {
    findRole(roleCode);
    return new LinkedHashSet<>(
        entityManager
            .createQuery(
                "select p.code from RoleEntity r join r.permissions p"
                    + " where r.code = :code order by p.code",
                String.class)
            .setParameter("code", roleCode)
            .getResultList());
  }

  /**
   * Substitui as permissões da role pelos códigos informados; lista vazia zera as permissões. Role
   * ou permissão inexistente → {@link NotFoundException}, sem gravar nada.
   */
  public void replacePermissions(String roleCode, Collection<String> permissionCodes) {
    RoleEntity role = findRole(roleCode);
    Set<PermissionEntity> permissions = findPermissions(permissionCodes);
    role.getPermissions().clear();
    role.getPermissions().addAll(permissions);
  }

  private RoleEntity findRole(String roleCode) {
    List<RoleEntity> found =
        entityManager
            .createQuery("select r from RoleEntity r where r.code = :code", RoleEntity.class)
            .setParameter("code", roleCode)
            .setMaxResults(1)
            .getResultList();
    if (found.isEmpty()) {
      throw new NotFoundException("role %s não encontrada".formatted(roleCode));
    }
    return found.getFirst();
  }

  /** Carrega as permissões dos códigos pedidos; qualquer código desconhecido derruba a operação. */
  private Set<PermissionEntity> findPermissions(Collection<String> permissionCodes) {
    if (permissionCodes.isEmpty()) {
      return Set.of();
    }
    Set<PermissionEntity> permissions =
        new LinkedHashSet<>(
            entityManager
                .createQuery(
                    "select p from PermissionEntity p where p.code in :codes",
                    PermissionEntity.class)
                .setParameter("codes", permissionCodes)
                .getResultList());
    for (String code : permissionCodes) {
      boolean known =
          permissions.stream().anyMatch(permission -> permission.getCode().equals(code));
      if (!known) {
        throw new NotFoundException("permissão %s não encontrada".formatted(code));
      }
    }
    return permissions;
  }
}
