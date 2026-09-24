package com.minimarket.inventory.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.inventory.application.ProductStockStore;
import com.minimarket.inventory.application.ProductStockSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA da tabela {@code product_stocks}. Sem {@code @Transactional}: a transação é do caso
 * de uso (§2.2, regra 6) — o lock de {@link #lockByProduct} vale até o fim da transação que o
 * pediu.
 *
 * <p>Implementa a porta {@link ProductStockStore}: é por ela que {@code application} lê o saldo e o
 * altera sob lock sem tocar em JPA.
 */
@ApplicationScoped
public class ProductStockRepository implements ProductStockStore {

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public Optional<ProductStockSummary> findByProduct(UUID storeId, UUID productId) {
    return findEntityByProduct(storeId, productId).map(ProductStockRepository::toSummary);
  }

  /**
   * {@inheritDoc}
   *
   * <p>O {@code SELECT ... FOR UPDATE} sai no {@code refresh} com lock, não na consulta: o {@code
   * find} reaproveita a cópia que a própria consulta deixou no contexto de persistência e o {@code
   * refresh} relê a linha do banco <em>já travada</em>, repondo essa cópia com o estado atual —
   * inclusive {@code quantity} e {@code version} que outra transação tenha gravado entre a leitura
   * e o lock. Projeção na saída — a entidade fica presa no adaptador.
   */
  @Override
  public Optional<ProductStockSummary> lockByProduct(UUID storeId, UUID productId) {
    Optional<ProductStockEntity> found = findEntityByProduct(storeId, productId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ProductStockEntity entity = found.get();
    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    return Optional.of(toSummary(entity));
  }

  /**
   * {@inheritDoc}
   *
   * <p>SQL nativo com {@code on conflict ... do nothing} de propósito: o {@code persist} do JPA
   * passaria pela checagem de existência e, com dois primeiros movimentos simultâneos no mesmo
   * produto, o perdedor estouraria a unique {@code ux_product_stocks_store_product} — abortando a
   * transação inteira do PostgreSQL. O conflito resolvido no banco mantém a segunda chamada
   * inofensiva, sem linha duplicada e sem erro. O id (UUIDv7) é gerado aqui; {@code updated_at} e
   * {@code version} saem dos defaults da tabela.
   */
  @Override
  public void insertIfAbsent(UUID storeId, UUID productId) {
    entityManager
        .createNativeQuery(
            "insert into product_stocks (id, store_id, product_id)"
                + " values (:id, :storeId, :productId)"
                + " on conflict (store_id, product_id) do nothing")
        .setParameter("id", UuidCreator.getTimeOrderedEpoch())
        .setParameter("storeId", storeId)
        .setParameter("productId", productId)
        .executeUpdate();
  }

  /**
   * {@inheritDoc}
   *
   * <p>A entidade já está no contexto de persistência — o {@link #lockByProduct} da mesma transação
   * a carregou —, então o {@code find} não vai ao banco de novo: devolve o que está preso. O flush
   * antecipado grava o saldo e completa {@code updated_at} e {@code version} da linha.
   */
  @Override
  public void updateQuantity(UUID id, BigDecimal newQuantity) {
    ProductStockEntity entity = entityManager.find(ProductStockEntity.class, id);
    if (entity == null) {
      throw new IllegalStateException(
          "saldo de estoque %s não encontrado para atualizar".formatted(id));
    }
    entity.setQuantity(newQuantity);
    entityManager.flush();
  }

  /** Entidade pronta para escrita/lock; quem só lê recebe a projeção de {@link #toSummary}. */
  private Optional<ProductStockEntity> findEntityByProduct(UUID storeId, UUID productId) {
    return entityManager
        .createQuery(
            "select s from ProductStockEntity s where s.storeId = :storeId"
                + " and s.productId = :productId",
            ProductStockEntity.class)
        .setParameter("storeId", storeId)
        .setParameter("productId", productId)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst();
  }

  /** Projeção do saldo para a porta: nada de entidade JPA na saída. */
  private static ProductStockSummary toSummary(ProductStockEntity entity) {
    return new ProductStockSummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getProductId(),
        entity.getQuantity(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }
}
