package com.minimarket.shared.infrastructure;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;

/** Adaptador JPA da porta {@link StoreLookup}: consulta a loja pelo código exato. */
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

  private static Store toDomain(StoreEntity entity) {
    return new Store(
        entity.getCode(), entity.isAllowNegativeStock(), entity.getMaxDiscountPercent());
  }
}
