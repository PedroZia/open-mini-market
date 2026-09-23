package com.minimarket.users.infrastructure;

import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.users.application.RoleAdminStore;
import com.minimarket.users.application.RoleSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador JPA de {@code role_permissions} (§5.3 do plano): as permissões de uma role, o mapa
 * completo role→permissão da administração de papéis (passo 114) e as permissões efetivas de um
 * usuário (união das roles). Sem {@code @Transactional} — a transação é do caso de uso (§2.2, regra
 * 6).
 *
 * <p>Devolve códigos e projeções, nunca entidades: a fronteira com o caso de uso é só String e
 * {@link RoleSummary}, então JPA não vaza para {@code application} nem para JSON. Implementa a
 * porta {@link RoleAdminStore}.
 */
@ApplicationScoped
public class PermissionRepository implements RoleAdminStore {

  @Inject EntityManager entityManager;

  /**
   * {@inheritDoc}
   *
   * <p>Uma consulta só para o catálogo: o join fetch traz as permissões junto e as ordena na
   * projeção, sem N+1.
   */
  @Override
  public List<RoleSummary> listRoles() {
    return entityManager
        .createQuery(
            "select distinct r from RoleEntity r left join fetch r.permissions order by r.code",
            RoleEntity.class)
        .getResultList()
        .stream()
        .map(PermissionRepository::toSummary)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<RoleSummary> findRole(String roleCode) {
    return entityManager
        .createQuery(
            "select distinct r from RoleEntity r left join fetch r.permissions where r.code = :code",
            RoleEntity.class)
        .setParameter("code", roleCode)
        .getResultList()
        .stream()
        .findFirst()
        .map(PermissionRepository::toSummary);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Mesmo formato de {@code RoleRepository.findUnknownCodes}: consulta os códigos existentes no
   * catálogo e devolve os que faltam, sem lançar.
   */
  @Override
  public Set<String> findUnknownPermissionCodes(Collection<String> permissionCodes) {
    if (permissionCodes.isEmpty()) {
      return Set.of();
    }
    Set<String> known =
        new HashSet<>(
            entityManager
                .createQuery(
                    "select p.code from PermissionEntity p where p.code in :codes", String.class)
                .setParameter("codes", permissionCodes)
                .getResultList());
    Set<String> unknown = new LinkedHashSet<>();
    for (String code : permissionCodes) {
      if (!known.contains(code)) {
        unknown.add(code);
      }
    }
    return unknown;
  }

  /** {@inheritDoc} */
  @Override
  public RoleSummary replacePermissions(String roleCode, Collection<String> permissionCodes) {
    RoleEntity role = requireRole(roleCode);
    Set<PermissionEntity> permissions = findPermissions(permissionCodes);
    role.getPermissions().clear();
    role.getPermissions().addAll(permissions);
    return toSummary(role);
  }

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
    requireRole(roleCode);
    return new LinkedHashSet<>(
        entityManager
            .createQuery(
                "select p.code from RoleEntity r join r.permissions p"
                    + " where r.code = :code order by p.code",
                String.class)
            .setParameter("code", roleCode)
            .getResultList());
  }

  /** Projeção da entidade com as permissões em ordem alfabética, pronta para o DTO. */
  private static RoleSummary toSummary(RoleEntity role) {
    return new RoleSummary(
        role.getCode(),
        role.getName(),
        role.getDescription(),
        role.isSystem(),
        role.getPermissions().stream().map(PermissionEntity::getCode).sorted().toList());
  }

  private RoleEntity requireRole(String roleCode) {
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
