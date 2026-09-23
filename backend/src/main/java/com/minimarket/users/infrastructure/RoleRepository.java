package com.minimarket.users.infrastructure;

import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.users.application.RoleStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador JPA de {@code user_roles} (§5.3 do plano): quais papéis o usuário tem. Sem
 * {@code @Transactional} — a transação é do caso de uso (§2.2, regra 6).
 *
 * <p>Devolve códigos, nunca entidades: a fronteira com o caso de uso é só String, então JPA não
 * vaza para {@code application} nem para JSON. Implementa a porta {@link RoleStore}.
 */
@ApplicationScoped
public class RoleRepository implements RoleStore {

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
  @Override
  public void assignRoles(UUID userId, Collection<String> roleCodes) {
    UserEntity user = entityManager.find(UserEntity.class, userId);
    if (user == null) {
      throw new NotFoundException("usuário %s não encontrado".formatted(userId));
    }
    Set<RoleEntity> roles = findRoles(roleCodes);
    user.getRoles().clear();
    user.getRoles().addAll(roles);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Só consulta os códigos existentes no catálogo; não grava nem lança — o caso de uso é quem
   * decide transformar o resultado em 400 {@code UNKNOWN_ROLE}.
   */
  @Override
  public Set<String> findUnknownCodes(Collection<String> roleCodes) {
    if (roleCodes.isEmpty()) {
      return Set.of();
    }
    Set<String> known =
        new HashSet<>(
            entityManager
                .createQuery("select r.code from RoleEntity r where r.code in :codes", String.class)
                .setParameter("codes", roleCodes)
                .getResultList());
    Set<String> unknown = new LinkedHashSet<>();
    for (String code : roleCodes) {
      if (!known.contains(code)) {
        unknown.add(code);
      }
    }
    return unknown;
  }

  /** Carrega as roles dos códigos pedidos; qualquer código desconhecido derruba a operação. */
  private Set<RoleEntity> findRoles(Collection<String> roleCodes) {
    if (roleCodes.isEmpty()) {
      return Set.of();
    }
    Set<String> unknown = findUnknownCodes(roleCodes);
    if (!unknown.isEmpty()) {
      throw new NotFoundException("role %s não encontrada".formatted(unknown.iterator().next()));
    }
    return new LinkedHashSet<>(
        entityManager
            .createQuery("select r from RoleEntity r where r.code in :codes", RoleEntity.class)
            .setParameter("codes", roleCodes)
            .getResultList());
  }
}
