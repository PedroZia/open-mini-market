package com.minimarket.shared.infrastructure;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adaptador JPA da porta {@link StoreLookup}: consulta a loja pelo código exato ou pelo id. */
@ApplicationScoped
public class StoreRepository implements StoreLookup {

  @Inject EntityManager entityManager;

  @Override
  public Optional<Store> findByCode(String code) {
    List<StoreEntity> found =
        entityManager
            .createQuery("select s from StoreEntity s where s.code = :code", StoreEntity.class)
            .setParameter("code", code)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? Optional.empty() : Optional.of(toDomain(found.getFirst()));
  }

  /**
   * Loja pelo id: a sessão guarda o {@code store_id} (§5.3) e o {@code /auth/me} lê a loja dela.
   */
  @Override
  public Optional<Store> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(StoreEntity.class, id))
        .map(StoreRepository::toDomain);
  }

  private static Store toDomain(StoreEntity entity) {
    return new Store(
        entity.getId(),
        entity.getCode(),
        entity.getName(),
        entity.isAllowNegativeStock(),
        entity.getMaxDiscountPercent(),
        entity.getInternalBarcodePrefix(),
        entity.getInternalCodeLength(),
        entity.getScaleEmbeddedField(),
        entity.getScaleEmbeddedDecimals());
  }
}
