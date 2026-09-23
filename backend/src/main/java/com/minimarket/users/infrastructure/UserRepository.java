package com.minimarket.users.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA da tabela {@code users}. Sem {@code @Transactional}: a transação é do caso de uso
 * (§2.2, regra 6).
 *
 * <p>{@link #findByUsername} e {@link #findById} devolvem também usuário soft-deletado — quem
 * decide o que fazer com {@code deletedAt} é o caso de uso (o login precisa ver o registro para
 * decidir); {@link #search} sempre ignora deletados.
 */
@ApplicationScoped
public class UserRepository {

  @Inject EntityManager entityManager;

  /** Busca pelo username normalizado, incluindo usuário soft-deletado. */
  public Optional<UserEntity> findByUsername(String username) {
    List<UserEntity> found =
        entityManager
            .createQuery(
                "select u from UserEntity u where u.username = :username", UserEntity.class)
            .setParameter("username", normalize(username))
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
  }

  /** Busca pela chave, incluindo usuário soft-deletado. */
  public Optional<UserEntity> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(UserEntity.class, id));
  }

  /** Gera o id (UUIDv7, §5.1) na aplicação e persiste; a transação é do caso de uso. */
  public UserEntity insert(UserEntity user) {
    user.assignId(UuidCreator.getTimeOrderedEpoch());
    user.assignUsername(normalize(user.getUsername()));
    entityManager.persist(user);
    return user;
  }

  /** Grava o estado atual do usuário; a transação é do caso de uso. */
  public UserEntity update(UserEntity user) {
    user.assignUsername(normalize(user.getUsername()));
    return entityManager.merge(user);
  }

  /**
   * Soft delete: grava {@code deleted_at} e nada mais (status é regra do caso de uso de
   * desativar/reativar). Não faz nada se o id não existir.
   */
  public void softDelete(UUID id) {
    findById(id).ifPresent(user -> user.markDeleted(Instant.now()));
  }

  /**
   * Lista usuários vivos ordenados por username, com filtro textual em username/display_name e
   * filtro de status ({@code active} nulo = todos). {@code search} em branco = sem filtro textual.
   */
  public List<UserEntity> search(String search, Boolean active, int page, int size) {
    boolean hasTerm = search != null && !search.isBlank();
    StringBuilder jpql = new StringBuilder("select u from UserEntity u where u.deletedAt is null");
    if (active != null) {
      jpql.append(" and u.status = :status");
    }
    if (hasTerm) {
      jpql.append(" and (lower(u.username) like :term or lower(u.displayName) like :term)");
    }
    jpql.append(" order by u.username");

    var query = entityManager.createQuery(jpql.toString(), UserEntity.class);
    if (active != null) {
      query.setParameter("status", active ? UserEntity.STATUS_ACTIVE : UserEntity.STATUS_DISABLED);
    }
    if (hasTerm) {
      query.setParameter("term", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
    }
    return query.setFirstResult(page * size).setMaxResults(size).getResultList();
  }

  /** Username é sempre comparado em minúsculas e sem espaços nas pontas (§5.3). */
  private static String normalize(String username) {
    return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
  }
}
