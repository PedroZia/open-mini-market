package com.minimarket.cash.infrastructure;

import com.minimarket.cash.application.CashRegisterStore;
import com.minimarket.cash.application.CashRegisterSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA da tabela {@code cash_registers}. Sem {@code @Transactional}: leitura pura — a
 * transação, quando houver, é do caso de uso (§2.2, regra 6).
 *
 * <p>Implementa a porta {@link CashRegisterStore}: é por ela que {@code application} lê os caixas
 * sem tocar em JPA.
 */
@ApplicationScoped
public class CashRegisterRepository implements CashRegisterStore {

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public List<CashRegisterSummary> listActive() {
    return entityManager
        .createQuery(
            "select r from CashRegisterEntity r where r.active = true order by r.code",
            CashRegisterEntity.class)
        .getResultList()
        .stream()
        .map(CashRegisterRepository::toSummary)
        .toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>O filtro de ativo vai na consulta, não no Java: caixa desativado não é encontrado, como se
   * não existisse.
   */
  @Override
  public Optional<CashRegisterSummary> findActiveById(UUID id) {
    List<CashRegisterEntity> found =
        entityManager
            .createQuery(
                "select r from CashRegisterEntity r where r.id = :id and r.active = true",
                CashRegisterEntity.class)
            .setParameter("id", id)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? Optional.empty() : Optional.of(toSummary(found.getFirst()));
  }

  /** Projeção do caixa para a porta: nada de entidade JPA na saída. */
  private static CashRegisterSummary toSummary(CashRegisterEntity entity) {
    return new CashRegisterSummary(entity.getId(), entity.getCode(), entity.getName());
  }
}
