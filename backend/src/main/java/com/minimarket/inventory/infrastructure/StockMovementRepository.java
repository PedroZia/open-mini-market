package com.minimarket.inventory.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.inventory.application.NewStockMovement;
import com.minimarket.inventory.application.StockMovementStore;
import com.minimarket.inventory.application.StockMovementSummary;
import com.minimarket.inventory.domain.StockMovementType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador JPA da tabela {@code stock_movements}. Sem {@code @Transactional}: a transação é do
 * caso de uso (§2.2, regra 6).
 *
 * <p>Implementa a porta {@link StockMovementStore}: é por ela que {@code application} grava o
 * movimento do ledger e lê o histórico sem tocar em JPA. O ledger é append-only — só insert e
 * leitura.
 */
@ApplicationScoped
public class StockMovementRepository implements StockMovementStore {

  @Inject EntityManager entityManager;

  /** Gera o id (UUIDv7) na aplicação e persiste o movimento; a transação é do caso de uso. */
  @Override
  public UUID insert(NewStockMovement movement) {
    StockMovementEntity entity =
        new StockMovementEntity(
            movement.storeId(),
            movement.productId(),
            movement.type(),
            movement.quantityDelta(),
            movement.balanceAfter(),
            movement.unitCost(),
            movement.referenceType(),
            movement.referenceId(),
            movement.reason(),
            movement.createdByUserId(),
            movement.createdAt());
    entity.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(entity);
    return entity.getId();
  }

  /** {@inheritDoc} */
  @Override
  public List<StockMovementSummary> listByProduct(UUID productId, int limit) {
    return entityManager
        .createQuery(
            "select m from StockMovementEntity m where m.productId = :productId"
                + " order by m.createdAt desc, m.id desc",
            StockMovementEntity.class)
        .setParameter("productId", productId)
        .setMaxResults(limit)
        .getResultList()
        .stream()
        .map(StockMovementRepository::toSummary)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public Map<StockMovementType, BigDecimal> sumByType(UUID productId) {
    List<Object[]> rows =
        entityManager
            .createQuery(
                "select m.movementType, sum(m.quantityDelta) from StockMovementEntity m"
                    + " where m.productId = :productId group by m.movementType",
                Object[].class)
            .setParameter("productId", productId)
            .getResultList();
    Map<StockMovementType, BigDecimal> totals = new EnumMap<>(StockMovementType.class);
    for (Object[] row : rows) {
      totals.put((StockMovementType) row[0], (BigDecimal) row[1]);
    }
    return totals;
  }

  /** Projeção do movimento para a porta: nada de entidade JPA na saída. */
  private static StockMovementSummary toSummary(StockMovementEntity entity) {
    return new StockMovementSummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getProductId(),
        entity.getMovementType(),
        entity.getQuantityDelta(),
        entity.getBalanceAfter(),
        entity.getUnitCost(),
        entity.getReferenceType(),
        entity.getReferenceId(),
        entity.getReason(),
        entity.getCreatedByUserId(),
        entity.getCreatedAt());
  }
}
