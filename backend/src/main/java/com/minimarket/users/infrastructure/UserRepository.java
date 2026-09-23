package com.minimarket.users.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserSort;
import com.minimarket.users.application.UserStore;
import com.minimarket.users.application.UserSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Adaptador JPA da tabela {@code users}. Sem {@code @Transactional}: a transação é do caso de uso
 * (§2.2, regra 6).
 *
 * <p>{@link #findByUsername} e {@link #findById} devolvem também usuário soft-deletado — quem
 * decide o que fazer com {@code deletedAt} é o caso de uso (o login precisa ver o registro para
 * decidir); {@link #search}, {@link #findSummaryById} e {@link #existsByUsername} sempre ignoram
 * deletados.
 *
 * <p>Implementa a porta {@link UserStore}: é por ela que {@code application} grava usuário sem
 * tocar em JPA.
 */
@ApplicationScoped
public class UserRepository implements UserStore {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

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

  /**
   * {@inheritDoc}
   *
   * <p>Aplica o status informado e reaproveita {@link #insert(UserEntity)}: o id (UUIDv7) e a
   * normalização do username continuam no adaptador. Depois do insert força o flush para traduzir a
   * violação do índice único de username ainda dentro da transação do caso de uso.
   */
  @Override
  public UUID insert(NewUser user) {
    UserEntity entity = new UserEntity(user.username(), user.passwordHash(), user.displayName());
    entity.setStatus(user.status());
    insert(entity);
    flushTranslatingUsernameConflict(user.username());
    return entity.getId();
  }

  /**
   * O {@code existsByUsername} do caso de uso não é atômico: entre a checagem e o insert, outro
   * request pode gravar o mesmo username. O flush antecipado faz a violação do índice único parcial
   * aparecer aqui e virar {@link ConflictException} com o mesmo código do caminho comum — o
   * backstop do banco nunca responde 500. {@code users} só tem o índice de username além da chave
   * primária (e o id é UUIDv7 gerado na aplicação), então SQLState 23505 neste insert só pode ser
   * username duplicado; qualquer outra falha de persistência sobe como está.
   */
  private void flushTranslatingUsernameConflict(String username) {
    try {
      entityManager.flush();
    } catch (ConstraintViolationException exception) {
      if (!UNIQUE_VIOLATION.equals(exception.getSQLState())) {
        throw exception;
      }
      throw new ConflictException(
          ErrorCode.USERNAME_ALREADY_EXISTS,
          "username %s já está em uso".formatted(normalize(username)));
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reaproveita {@link #findById} e aplica o mesmo filtro de soft delete de {@link
   * #findSummaryById}: usuário apagado não é atualizado. A alteração sai no flush da transação do
   * caso de uso.
   */
  @Override
  public void updateDisplayName(UUID id, String displayName) {
    findById(id)
        .filter(user -> user.getDeletedAt() == null)
        .ifPresent(user -> user.setDisplayName(displayName));
  }

  /**
   * {@inheritDoc}
   *
   * <p>O filtro de {@code deleted_at} fica aqui, junto com o de {@link #search}: usuário
   * soft-deletado não existe para a aplicação.
   */
  @Override
  public Optional<UserSummary> findSummaryById(UUID id) {
    List<UserEntity> found =
        entityManager
            .createQuery(
                "select u from UserEntity u where u.id = :id and u.deletedAt is null",
                UserEntity.class)
            .setParameter("id", id)
            .setMaxResults(1)
            .getResultList();
    return toSummaries(found).stream().findFirst();
  }

  /** {@inheritDoc} */
  @Override
  public boolean existsByUsername(String username) {
    return !entityManager
        .createQuery(
            "select u.id from UserEntity u where u.username = :username and u.deletedAt is null",
            UUID.class)
        .setParameter("username", normalize(username))
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Só usuário vivo é desativado: o filtro de {@code deletedAt} devolve vazio para quem já está
   * desativado — o 404 é do caso de uso. A projeção sai com o estado já alterado; o flush (e o
   * avanço de {@code version}) fica com a transação do caso de uso.
   */
  @Override
  public Optional<UserSummary> disable(UUID id) {
    return findById(id)
        .filter(user -> user.getDeletedAt() == null)
        .map(
            user -> {
              user.setStatus(UserEntity.STATUS_DISABLED);
              user.markDeleted(Instant.now());
              return toSummaries(List.of(user)).getFirst();
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>Enxerga o soft-deletado — é o registro que a reativação precisa alcançar; usuário já ativo
   * recebe os mesmos valores (sem update, o JPA não vê diferença).
   */
  @Override
  public Optional<UserSummary> enable(UUID id) {
    return findById(id)
        .map(
            user -> {
              user.setStatus(UserEntity.STATUS_ACTIVE);
              user.markRestored();
              return toSummaries(List.of(user)).getFirst();
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>Mesmo filtro de usuário vivo de {@link #disable}: soft-deletado não é resetado. O flush (e o
   * avanço de {@code version}) fica com a transação do caso de uso.
   */
  @Override
  public Optional<UserSummary> resetPassword(UUID id, String passwordHash) {
    return findById(id)
        .filter(user -> user.getDeletedAt() == null)
        .map(
            user -> {
              user.resetPassword(passwordHash);
              return toSummaries(List.of(user)).getFirst();
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>Mesmo filtro de usuário vivo de {@link #updateDisplayName}; a alteração sai no flush da
   * transação do caso de uso.
   */
  @Override
  public void requirePasswordChange(UUID id) {
    findById(id)
        .filter(user -> user.getDeletedAt() == null)
        .ifPresent(UserEntity::requirePasswordChange);
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
   * {@inheritDoc}
   *
   * <p>As cláusulas são fixas e montadas só com valores já resolvidos — o campo de ordenação vem do
   * enum {@link UserSort}, nunca de string do cliente — e todo valor entra por parâmetro nomeado. O
   * {@code u.id} no fim da ordenação mantém a paginação estável quando o campo escolhido empata.
   */
  @Override
  public List<UserSummary> search(
      String search, Boolean active, UserSort sort, boolean ascending, int page, int size) {
    boolean hasTerm = hasTerm(search);
    TypedQuery<UserEntity> query =
        entityManager
            .createQuery(
                "select u from UserEntity u where u.deletedAt is null"
                    + statusClause(active)
                    + termClause(hasTerm)
                    + " order by "
                    + orderBy(sort, ascending)
                    + ", u.id",
                UserEntity.class)
            .setFirstResult(page * size)
            .setMaxResults(size);
    applyFilters(query, search, active, hasTerm);
    return toSummaries(query.getResultList());
  }

  /** {@inheritDoc} */
  @Override
  public long count(String search, Boolean active) {
    boolean hasTerm = hasTerm(search);
    TypedQuery<Long> query =
        entityManager.createQuery(
            "select count(u) from UserEntity u where u.deletedAt is null"
                + statusClause(active)
                + termClause(hasTerm),
            Long.class);
    applyFilters(query, search, active, hasTerm);
    return query.getSingleResult();
  }

  private static void applyFilters(
      TypedQuery<?> query, String search, Boolean active, boolean hasTerm) {
    if (active != null) {
      query.setParameter("status", active ? UserEntity.STATUS_ACTIVE : UserEntity.STATUS_DISABLED);
    }
    if (hasTerm) {
      query.setParameter("term", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
    }
  }

  private static boolean hasTerm(String search) {
    return search != null && !search.isBlank();
  }

  private static String statusClause(Boolean active) {
    return active == null ? "" : " and u.status = :status";
  }

  private static String termClause(boolean hasTerm) {
    return hasTerm ? " and (lower(u.username) like :term or lower(u.displayName) like :term)" : "";
  }

  /** Campo ordenável do enum → coluna JPQL; a whitelist mora no tipo, não na string. */
  private static String orderBy(UserSort sort, boolean ascending) {
    String column =
        switch (sort) {
          case USERNAME -> "u.username";
          case DISPLAY_NAME -> "u.displayName";
          case CREATED_AT -> "u.createdAt";
        };
    return column + (ascending ? " asc" : " desc");
  }

  /**
   * Projeções da página: as roles dos usuários listados saem em uma consulta só, em vez de uma por
   * usuário, e nada de entidade ou JPA atravessa a porta.
   */
  private List<UserSummary> toSummaries(List<UserEntity> users) {
    if (users.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<String>> rolesByUserId =
        loadRoles(users.stream().map(UserEntity::getId).toList());
    return users.stream()
        .map(
            user ->
                new UserSummary(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getStatus(),
                    rolesByUserId.getOrDefault(user.getId(), List.of()),
                    user.isMustChangePassword()))
        .toList();
  }

  /** Códigos de role dos ids informados, agrupados por usuário e ordenados dentro de cada um. */
  private Map<UUID, List<String>> loadRoles(List<UUID> userIds) {
    Map<UUID, List<String>> rolesByUserId = new HashMap<>();
    List<Object[]> rows =
        entityManager
            .createQuery(
                "select u.id, r.code from UserEntity u join u.roles r where u.id in :userIds"
                    + " order by r.code",
                Object[].class)
            .setParameter("userIds", userIds)
            .getResultList();
    for (Object[] row : rows) {
      rolesByUserId.computeIfAbsent((UUID) row[0], id -> new ArrayList<>()).add((String) row[1]);
    }
    return rolesByUserId;
  }

  /** Username é sempre comparado em minúsculas e sem espaços nas pontas (§5.3). */
  private static String normalize(String username) {
    return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
  }
}
