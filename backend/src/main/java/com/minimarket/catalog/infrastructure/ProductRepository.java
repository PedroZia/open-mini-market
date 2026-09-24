package com.minimarket.catalog.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductSort;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Adaptador JPA da tabela {@code products}. Sem {@code @Transactional}: a transação é do caso de
 * uso (§2.2, regra 6).
 *
 * <p>{@link #findById} devolve também produto soft-deletado — quem decide o que fazer com {@code
 * deletedAt} é o caso de uso (o passo 412 precisa reativar o registro); {@link #findByBarcode},
 * {@link #search}, {@link #update}, {@link #updatePrice} e {@link #existsActiveBarcode} sempre
 * ignoram deletados.
 *
 * <p>Implementa a porta {@link ProductStore}: é por ela que {@code application} grava produto sem
 * tocar em JPA.
 */
@ApplicationScoped
public class ProductRepository implements ProductStore {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public UUID insert(NewProduct product) {
    ProductEntity entity =
        new ProductEntity(
            product.storeId(),
            product.name(),
            product.barcode(),
            product.description(),
            product.categoryId(),
            product.unit(),
            product.price(),
            product.minQuantity());
    entity.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(entity);
    flushTranslatingConflicts();
    return entity.getId();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ProductSummary> findById(UUID id) {
    return findEntityById(id).map(ProductRepository::toSummary);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ProductSummary> findByBarcode(String barcode) {
    List<ProductEntity> found =
        entityManager
            .createQuery(
                "select p from ProductEntity p where p.barcode = :barcode and p.deletedAt is null",
                ProductEntity.class)
            .setParameter("barcode", barcode)
            .setMaxResults(1)
            .getResultList();
    return found.stream().findFirst().map(ProductRepository::toSummary);
  }

  /** {@inheritDoc} */
  @Override
  public boolean existsActiveBarcode(String barcode) {
    return !entityManager
        .createQuery(
            "select p.id from ProductEntity p where p.barcode = :barcode and p.deletedAt is null",
            UUID.class)
        .setParameter("barcode", barcode)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /**
   * {@inheritDoc}
   *
   * <p>A alteração sai no flush da transação do caso de uso, como em {@link #insert} — o flush
   * daqui também deixa o {@code version} novo visível na projeção devolvida.
   *
   * <p>O flush antecipado é também o backstop do lock otimista: se outra requisição gravar o
   * produto entre a checagem de versão do caso de uso e este flush, o {@code update ... where
   * version = ?} com a versão velha não acha a linha e o Hibernate sinaliza o stale — traduzido
   * para {@link ConflictException} com o mesmo código do caminho comum, nunca 500.
   */
  @Override
  public Optional<ProductSummary> update(
      UUID id,
      String name,
      UUID categoryId,
      String unit,
      String description,
      BigDecimal minQuantity) {
    return findEntityById(id)
        .filter(product -> product.getDeletedAt() == null)
        .map(
            product -> {
              product.updateDetails(name, categoryId, unit, description, minQuantity);
              flushTranslatingConflicts();
              return toSummary(product);
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>O flush antecipado, como no {@link #update}, deixa o {@code version} novo visível na
   * projeção devolvida e serve de backstop do lock otimista: se outra requisição gravar o produto
   * entre a leitura do caso de uso e este flush, o {@code update ... where version = ?} com a
   * versão velha não acha a linha e o Hibernate sinaliza o stale — traduzido para {@link
   * ConflictException} com o mesmo código do caminho comum, nunca 500.
   */
  @Override
  public Optional<ProductSummary> updatePrice(UUID id, BigDecimal price) {
    return findEntityById(id)
        .filter(product -> product.getDeletedAt() == null)
        .map(
            product -> {
              product.updatePrice(price);
              flushTranslatingConflicts();
              return toSummary(product);
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>Não faz nada se o id não existir; o flush (e o avanço de {@code version}) fica com a
   * transação do caso de uso.
   */
  @Override
  public void softDelete(UUID id) {
    findEntityById(id).ifPresent(product -> product.markDeleted(Instant.now()));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Mesmo filtro de produto vivo do {@link #softDelete} — quem decide o que fazer com o {@code
   * active} é o caso de uso. O flush antecipado, como no {@link #update}, deixa o {@code version}
   * novo visível na projeção devolvida e é o backstop do lock otimista: desativar um produto
   * alterado por outra requisição entre a leitura e este flush vira {@link ConflictException} com o
   * mesmo código do caminho comum, nunca 500.
   */
  @Override
  public Optional<ProductSummary> disable(UUID id) {
    return findEntityById(id)
        .filter(product -> product.getDeletedAt() == null)
        .map(
            product -> {
              product.markDisabled(Instant.now());
              flushTranslatingConflicts();
              return toSummary(product);
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>Enxerga o soft-deletado — é o registro que a reativação precisa alcançar. O flush é
   * obrigatório aqui: é ele que confronta o barcode recuperado com o índice único parcial e traduz
   * a violação no {@code ConflictException} de {@code BARCODE_ALREADY_EXISTS} — sem o flush, o
   * conflito estouraria só no commit da transação do caso de uso, fora do alcance desta tradução, e
   * o produto continuaria desativado sem o cliente saber por quê.
   */
  @Override
  public Optional<ProductSummary> enable(UUID id) {
    return findEntityById(id)
        .map(
            product -> {
              product.markEnabled();
              flushTranslatingConflicts();
              return toSummary(product);
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>As cláusulas são fixas e montadas só com valores já resolvidos — o campo de ordenação vem do
   * enum {@link ProductSort}, nunca de string do cliente — e todo valor entra por parâmetro
   * nomeado. O {@code p.id} no fim da ordenação mantém a paginação estável quando o campo escolhido
   * empata.
   */
  @Override
  public List<ProductSummary> search(
      String search,
      UUID categoryId,
      Boolean active,
      ProductSort sort,
      boolean ascending,
      int page,
      int size) {
    boolean hasTerm = hasTerm(search);
    TypedQuery<ProductEntity> query =
        entityManager
            .createQuery(
                "select p from ProductEntity p where "
                    + liveFilters(categoryId, active, hasTerm)
                    + " order by "
                    + orderBy(sort, ascending)
                    + ", p.id",
                ProductEntity.class)
            .setFirstResult(page * size)
            .setMaxResults(size);
    applyFilters(query, search, categoryId, active, hasTerm);
    return query.getResultList().stream().map(ProductRepository::toSummary).toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Conta com as mesmas cláusulas da busca ({@link #liveFilters}): o {@code totalItems} não pode
   * divergir do que {@link #search} devolve para os mesmos filtros.
   */
  @Override
  public long count(String search, UUID categoryId, Boolean active) {
    boolean hasTerm = hasTerm(search);
    TypedQuery<Long> query =
        entityManager.createQuery(
            "select count(p) from ProductEntity p where "
                + liveFilters(categoryId, active, hasTerm),
            Long.class);
    applyFilters(query, search, categoryId, active, hasTerm);
    return query.getSingleResult();
  }

  /**
   * O {@code existsActiveBarcode} do caso de uso não é atômico: entre a checagem e a escrita, outro
   * request pode gravar o mesmo barcode. O flush antecipado (no {@code insert} e também no {@code
   * update}, que compartilha a tabela) faz a violação do índice único parcial {@code
   * ux_products_barcode} aparecer aqui e virar {@link ConflictException} com o mesmo código do
   * caminho comum — o backstop do banco nunca responde 500. {@code products} só tem esse índice
   * único além da chave primária (o id é UUIDv7 gerado na aplicação), então SQLState 23505 aqui só
   * pode ser barcode duplicado entre produtos vivos; qualquer outra falha de persistência sobe como
   * está.
   *
   * <p>O mesmo flush é o backstop do lock otimista do {@code update}: o Hibernate sinaliza a versão
   * vencida (o {@code where version = ?} não achou a linha) e o stale vira o 409 {@code
   * CONCURRENT_MODIFICATION} do caso de uso.
   */
  private void flushTranslatingConflicts() {
    try {
      entityManager.flush();
    } catch (OptimisticLockException exception) {
      throw new ConflictException(
          ErrorCode.CONCURRENT_MODIFICATION,
          "produto alterado por outra requisição; recarregue e tente de novo");
    } catch (ConstraintViolationException exception) {
      if (!UNIQUE_VIOLATION.equals(exception.getSQLState())) {
        throw exception;
      }
      throw new ConflictException(
          ErrorCode.BARCODE_ALREADY_EXISTS, "código de barras já está em uso");
    }
  }

  /** Entidade pronta para escrita; quem só lê recebe a projeção de {@link #toSummary}. */
  private Optional<ProductEntity> findEntityById(UUID id) {
    return Optional.ofNullable(entityManager.find(ProductEntity.class, id));
  }

  private static void applyFilters(
      TypedQuery<?> query, String search, UUID categoryId, Boolean active, boolean hasTerm) {
    if (categoryId != null) {
      query.setParameter("categoryId", categoryId);
    }
    if (active != null) {
      query.setParameter("active", active);
    }
    if (hasTerm) {
      query.setParameter("term", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
    }
  }

  private static boolean hasTerm(String search) {
    return search != null && !search.isBlank();
  }

  /**
   * Cláusulas comuns da busca e da contagem: produto vivo e os filtros opcionais. Fonte única para
   * a contagem não divergir da lista.
   */
  private static String liveFilters(UUID categoryId, Boolean active, boolean hasTerm) {
    return "p.deletedAt is null"
        + categoryClause(categoryId)
        + statusClause(active)
        + termClause(hasTerm);
  }

  private static String categoryClause(UUID categoryId) {
    return categoryId == null ? "" : " and p.categoryId = :categoryId";
  }

  private static String statusClause(Boolean active) {
    return active == null ? "" : " and p.active = :active";
  }

  private static String termClause(boolean hasTerm) {
    return hasTerm ? " and lower(p.name) like :term" : "";
  }

  /** Campo ordenável do enum → coluna JPQL; a whitelist mora no tipo, não na string. */
  private static String orderBy(ProductSort sort, boolean ascending) {
    String column =
        switch (sort) {
          case NAME -> "p.name";
          case PRICE -> "p.price";
          case CREATED_AT -> "p.createdAt";
        };
    return column + (ascending ? " asc" : " desc");
  }

  /** Projeção do produto para a porta: nada de entidade JPA na saída. */
  private static ProductSummary toSummary(ProductEntity entity) {
    return new ProductSummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getBarcode(),
        entity.getName(),
        entity.getDescription(),
        entity.getCategoryId(),
        entity.getUnit(),
        entity.getPrice(),
        entity.getMinQuantity(),
        entity.isActive(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getDeletedAt(),
        entity.getVersion());
  }
}
