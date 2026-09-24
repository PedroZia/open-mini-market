package com.minimarket.catalog.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.catalog.application.CategoryStore;
import com.minimarket.catalog.application.CategorySummary;
import com.minimarket.catalog.application.NewCategory;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Adaptador JPA da tabela {@code categories}. Sem {@code @Transactional}: a transação é do caso de
 * uso (§2.2, regra 6).
 *
 * <p>Implementa a porta {@link CategoryStore}: é por ela que {@code application} grava categoria
 * sem tocar em JPA.
 */
@ApplicationScoped
public class CategoryRepository implements CategoryStore {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public UUID insert(NewCategory category) {
    CategoryEntity entity =
        new CategoryEntity(
            category.storeId(), category.name(), category.parentId(), category.sortOrder());
    entity.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(entity);
    flushTranslatingNameConflict(category.name());
    return entity.getId();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<CategorySummary> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(CategoryEntity.class, id))
        .map(CategoryRepository::toSummary);
  }

  /** {@inheritDoc} */
  @Override
  public List<CategorySummary> findAll() {
    return entityManager
        .createQuery(
            "select c from CategoryEntity c order by c.sortOrder, c.name", CategoryEntity.class)
        .getResultList()
        .stream()
        .map(CategoryRepository::toSummary)
        .toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>O flush depois da alteração traduz a violação do índice único ainda dentro da transação do
   * caso de uso, como em {@link #insert}.
   */
  @Override
  public void update(UUID id, String name, UUID parentId, int sortOrder) {
    Optional.ofNullable(entityManager.find(CategoryEntity.class, id))
        .ifPresent(
            entity -> {
              entity.updateDetails(name, parentId, sortOrder);
              flushTranslatingNameConflict(name);
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>O flush (e o avanço de {@code version}) fica com a transação do caso de uso.
   */
  @Override
  public void deactivate(UUID id) {
    Optional.ofNullable(entityManager.find(CategoryEntity.class, id))
        .ifPresent(CategoryEntity::deactivate);
  }

  /** {@inheritDoc} */
  @Override
  public boolean existsByName(String name) {
    return exists(name, null);
  }

  /** {@inheritDoc} */
  @Override
  public boolean existsByNameExceptId(String name, UUID id) {
    return exists(name, id);
  }

  /** Checagem de nome com a cláusula de exclusão só quando há id a ignorar. */
  private boolean exists(String name, UUID exceptId) {
    TypedQuery<UUID> query =
        entityManager
            .createQuery(
                "select c.id from CategoryEntity c where c.name = :name"
                    + (exceptId == null ? "" : " and c.id <> :exceptId"),
                UUID.class)
            .setParameter("name", name);
    if (exceptId != null) {
      query.setParameter("exceptId", exceptId);
    }
    return !query.setMaxResults(1).getResultList().isEmpty();
  }

  /**
   * O {@code existsByName} do caso de uso não é atômico: entre a checagem e o insert, outro request
   * pode gravar o mesmo nome. O flush antecipado faz a violação do índice único {@code
   * ux_categories_store_name} aparecer aqui e virar {@link ConflictException} com o mesmo código do
   * caminho comum — o backstop do banco nunca responde 500. A tabela só tem esse índice único além
   * da chave primária (o id é UUIDv7 gerado na aplicação), então SQLState 23505 aqui só pode ser
   * nome duplicado; qualquer outra falha de persistência sobe como está.
   */
  private void flushTranslatingNameConflict(String name) {
    try {
      entityManager.flush();
    } catch (ConstraintViolationException exception) {
      if (!UNIQUE_VIOLATION.equals(exception.getSQLState())) {
        throw exception;
      }
      throw new ConflictException(
          ErrorCode.CATEGORY_NAME_ALREADY_EXISTS, "nome %s já está em uso".formatted(name));
    }
  }

  /** Projeção da categoria para a porta: nada de entidade JPA na saída. */
  private static CategorySummary toSummary(CategoryEntity entity) {
    return new CategorySummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getName(),
        entity.getParentId(),
        entity.isActive(),
        entity.getSortOrder());
  }
}
