package com.minimarket.sales.infrastructure;

import com.minimarket.sales.application.SaleStore;
import com.minimarket.sales.application.SaleSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA das tabelas {@code sales} e {@code sale_items}. Sem {@code @Transactional}: a
 * transação é do caso de uso (§2.2, regra 6) — o lock de {@link #lockById} vale até o fim da
 * transação que o pediu.
 *
 * <p>Implementa a porta {@link SaleStore}: é por ela que {@code application} grava e lê o agregado
 * sem tocar em JPA.
 */
@ApplicationScoped
public class SaleRepository implements SaleStore {

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public Optional<Sale> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(SaleEntity.class, id))
        .map(entity -> SaleMapper.toDomain(entity, itemsOf(id)));
  }

  /** {@inheritDoc} */
  @Override
  public void insert(Sale sale) {
    entityManager.persist(SaleMapper.toEntity(sale));
    SaleMapper.toItemEntities(sale).forEach(entityManager::persist);
  }

  /**
   * {@inheritDoc}
   *
   * <p>O cabeçalho é gravado e conferido primeiro ({@link #flushTranslatingOptimisticLock}): é ali
   * que a versão vencida vira 409, nunca no meio da sincronização de itens. Os itens vêm depois, em
   * {@link #syncItems(Sale, List)}.
   */
  @Override
  public void update(Sale sale) {
    SaleEntity entity = entityManager.find(SaleEntity.class, sale.id());
    if (entity == null) {
      throw new IllegalStateException(
          "venda %s não encontrada para atualizar".formatted(sale.id()));
    }
    List<SaleItemEntity> stored = itemsOf(sale.id());
    entity.syncFrom(sale);
    flushTranslatingOptimisticLock();
    syncItems(sale, stored);
  }

  /** {@inheritDoc} */
  @Override
  public List<SaleSummary> search(
      Instant from,
      Instant to,
      SaleStatus status,
      UUID cashSessionId,
      UUID operatorUserId,
      int page,
      int size) {
    String filters = filters(from, to, status, cashSessionId, operatorUserId);
    TypedQuery<SaleEntity> query =
        entityManager
            .createQuery(
                "select s from SaleEntity s"
                    + (filters.isEmpty() ? "" : " where " + filters)
                    + " order by s.createdAt desc, s.id desc",
                SaleEntity.class)
            .setFirstResult(page * size)
            .setMaxResults(size);
    applyFilters(query, from, to, status, cashSessionId, operatorUserId);
    return query.getResultList().stream().map(SaleMapper::toSummary).toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Conta com as mesmas cláusulas da busca ({@link #filters}): o {@code totalItems} não pode
   * divergir do que {@link #search} devolve para os mesmos filtros.
   */
  @Override
  public long count(
      Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId) {
    String filters = filters(from, to, status, cashSessionId, operatorUserId);
    TypedQuery<Long> query =
        entityManager.createQuery(
            "select count(s) from SaleEntity s" + (filters.isEmpty() ? "" : " where " + filters),
            Long.class);
    applyFilters(query, from, to, status, cashSessionId, operatorUserId);
    return query.getSingleResult();
  }

  /**
   * {@inheritDoc}
   *
   * <p>O {@code SELECT ... FOR UPDATE} sai no {@code refresh} com lock, não numa consulta: o {@code
   * find} reaproveita a cópia que a transação já tenha deixado no contexto de persistência e o
   * {@code refresh} relê a linha do banco <em>já travada</em>, repondo essa cópia com o estado
   * atual — inclusive {@code status} e {@code version}. É o mesmo caminho do {@code
   * CashSessionRepository#lockById}. Projeção na saída — a entidade fica presa no adaptador.
   */
  @Override
  public Optional<Sale> lockById(UUID id) {
    SaleEntity entity = entityManager.find(SaleEntity.class, id);
    if (entity == null) {
      return Optional.empty();
    }
    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    return Optional.of(SaleMapper.toDomain(entity, itemsOf(id)));
  }

  /** {@inheritDoc} */
  @Override
  public boolean existsOpenByCashSession(UUID cashSessionId) {
    return !entityManager
        .createQuery(
            "select s.id from SaleEntity s where s.cashSessionId = :cashSessionId"
                + " and s.status = :status",
            UUID.class)
        .setParameter("cashSessionId", cashSessionId)
        .setParameter("status", SaleStatus.OPEN)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /**
   * Sincroniza as linhas de {@code sale_items} com a lista do agregado em três tempos, porque a
   * unique {@code (sale_id, line_number)} não deixa duas linhas ocuparem a mesma posição nem de
   * passagem no mesmo flush:
   *
   * <ol>
   *   <li>apaga as linhas que saíram do agregado e dá flush — as posições delas ficam livres;
   *   <li>estaciona em posição negativa (que nenhuma linha ocupa) as que vão trocar de lugar, senão
   *       uma troca de posições colidiria com a linha que ainda não se moveu;
   *   <li>grava o estado novo de cada item e insere os que faltam, com a posição final 1..n.
   * </ol>
   */
  private void syncItems(Sale sale, List<SaleItemEntity> stored) {
    List<SaleItemEntity> kept =
        stored.stream().filter(item -> itemIn(sale, item.getProductId()) != null).toList();
    List<SaleItemEntity> removed = new ArrayList<>(stored);
    removed.removeAll(kept);
    if (!removed.isEmpty()) {
      removed.forEach(entityManager::remove);
      flushTranslatingOptimisticLock();
    }
    boolean parked = false;
    for (SaleItemEntity item : kept) {
      int position = positionIn(sale, item.getProductId());
      if (item.getLineNumber() != position) {
        item.moveToLine(-position);
        parked = true;
      }
    }
    if (parked) {
      flushTranslatingOptimisticLock();
    }
    List<SaleItem> items = sale.items();
    for (int index = 0; index < items.size(); index++) {
      SaleItem item = items.get(index);
      SaleItemEntity storedItem = storedIn(kept, item.productId());
      if (storedItem == null) {
        entityManager.persist(SaleMapper.toItemEntity(sale.id(), index + 1, item));
      } else {
        storedItem.syncFrom(item, index + 1);
      }
    }
    flushTranslatingOptimisticLock();
  }

  /** Itens da venda na ordem de {@code line_number} — a ordem de inclusão do agregado. */
  private List<SaleItemEntity> itemsOf(UUID saleId) {
    return entityManager
        .createQuery(
            "select i from SaleItemEntity i where i.saleId = :saleId order by i.lineNumber",
            SaleItemEntity.class)
        .setParameter("saleId", saleId)
        .getResultList();
  }

  /** Item do agregado com o produto informado; nulo quando o produto não está na venda. */
  private static SaleItem itemIn(Sale sale, UUID productId) {
    for (SaleItem item : sale.items()) {
      if (item.productId().equals(productId)) {
        return item;
      }
    }
    return null;
  }

  /** Posição (1..n) do item do produto na lista do agregado; o produto já está na venda. */
  private static int positionIn(Sale sale, UUID productId) {
    List<SaleItem> items = sale.items();
    for (int index = 0; index < items.size(); index++) {
      if (items.get(index).productId().equals(productId)) {
        return index + 1;
      }
    }
    throw new IllegalStateException(
        "item do produto %s não está na venda %s".formatted(productId, sale.id()));
  }

  /** Linha estacionada/gravada do produto; nulo quando o produto é novo na venda. */
  private static SaleItemEntity storedIn(List<SaleItemEntity> stored, UUID productId) {
    for (SaleItemEntity item : stored) {
      if (item.getProductId().equals(productId)) {
        return item;
      }
    }
    return null;
  }

  /**
   * Cláusulas comuns da busca e da contagem — fonte única para o {@code totalItems} não divergir da
   * lista. Todo valor entra por parâmetro nomeado; período com {@code from} inclusivo e {@code to}
   * exclusivo (§9 do plano).
   */
  private static String filters(
      Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId) {
    List<String> clauses = new ArrayList<>();
    if (from != null) {
      clauses.add("s.createdAt >= :from");
    }
    if (to != null) {
      clauses.add("s.createdAt < :to");
    }
    if (status != null) {
      clauses.add("s.status = :status");
    }
    if (cashSessionId != null) {
      clauses.add("s.cashSessionId = :cashSessionId");
    }
    if (operatorUserId != null) {
      clauses.add("s.operatorUserId = :operatorUserId");
    }
    return String.join(" and ", clauses);
  }

  private static void applyFilters(
      TypedQuery<?> query,
      Instant from,
      Instant to,
      SaleStatus status,
      UUID cashSessionId,
      UUID operatorUserId) {
    if (from != null) {
      query.setParameter("from", from);
    }
    if (to != null) {
      query.setParameter("to", to);
    }
    if (status != null) {
      query.setParameter("status", status);
    }
    if (cashSessionId != null) {
      query.setParameter("cashSessionId", cashSessionId);
    }
    if (operatorUserId != null) {
      query.setParameter("operatorUserId", operatorUserId);
    }
  }

  /**
   * Flush antecipado com a tradução do lock otimista da venda: se outra transação gravar a venda
   * entre a leitura do caso de uso e este flush, o {@code update ... where version = ?} com a
   * versão vencida não acha a linha e o stale do Hibernate vira {@link ConflictException} com o
   * mesmo 409 {@code CONCURRENT_MODIFICATION} do caminho comum, nunca 500 — o mesmo backstop do
   * {@code ProductRepository}.
   */
  private void flushTranslatingOptimisticLock() {
    try {
      entityManager.flush();
    } catch (OptimisticLockException exception) {
      throw new ConflictException(
          ErrorCode.CONCURRENT_MODIFICATION,
          "venda alterada por outra requisição; recarregue e tente de novo");
    }
  }
}
