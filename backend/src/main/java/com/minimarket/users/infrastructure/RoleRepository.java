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
 * Adaptador JPA de {@code user_roles} (§5.3 do plano): quais papéis o usuário tem. Sem
 * {@code @Transactional} — a transação é do caso de uso (§2.2, regra 6).
 *
 * <p>Devolve códigos, nunca entidades: a fronteira com o caso de uso é só String, então JPA não
 * vaza para {@code application} nem para JSON.
 */
@ApplicationScoped
public class RoleRepository {

  @Inject EntityManager entityManager;

  /** Códigos das roles do usuário em ordem alfabética; usuário sem roles devolve lista vazia. */
  public List<String> rolesOf(UUID userId) {
    return entityManager
        .createQuery(
            "select r.code from UserEntity u join u.roles r where u.id = :userId order by r.code",
            String.class)
        .setParameter("userId", userId)
        .getResultList();
  }

  /**
   * Substitui o conjunto de roles do usuário pelos códigos informados; lista vazia remove todas.
   * Usuário ou código de role inexistente → {@link NotFoundException}, sem gravar nada.
   */
  public void assignRoles(UUID userId, Collection<String> roleCodes) {
    UserEntity user = entityManager.find(UserEntity.class, userId);
    if (user == null) {
      throw new NotFoundException("usuário %s não encontrado".formatted(userId));
    }
    Set<RoleEntity> roles = findRoles(roleCodes);
    user.getRoles().clear();
    user.getRoles().addAll(roles);
  }

  /** Carrega as roles dos códigos pedidos; qualquer código desconhecido derruba a operação. */
  private Set<RoleEntity> findRoles(Collection<String> roleCodes) {
    if (roleCodes.isEmpty()) {
      return Set.of();
    }
    Set<RoleEntity> roles =
        new LinkedHashSet<>(
            entityManager
                .createQuery("select r from RoleEntity r where r.code in :codes", RoleEntity.class)
                .setParameter("codes", roleCodes)
                .getResultList());
    for (String code : roleCodes) {
      boolean known = roles.stream().anyMatch(role -> role.getCode().equals(code));
      if (!known) {
        throw new NotFoundException("role %s não encontrada".formatted(code));
      }
    }
    return roles;
  }
}
