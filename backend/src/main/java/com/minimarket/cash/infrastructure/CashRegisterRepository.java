package com.minimarket.cash.infrastructure;

import com.minimarket.cash.application.CashRegisterStore;
import com.minimarket.cash.application.CashRegisterSummary;
import com.minimarket.shared.application.CashRegisterLookup;
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
 * sem tocar em JPA. Também implementa {@link CashRegisterLookup} (passo 607b) — a porta
 * compartilhada que o login usa para validar o caixa sem importar {@code cash}.
 */
@ApplicationScoped
public class CashRegisterRepository implements CashRegisterStore, CashRegisterLookup {

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

  /**
   * {@inheritDoc}
   *
   * <p>Reaproveita a consulta de caixa ativo: caixa inexistente ou inativo é a mesma coisa para
   * quem valida o login (passo 607b) — {@code false} nos dois casos.
   */
  @Override
  public boolean isActive(UUID id) {
    return findActiveById(id).isPresent();
  }

  /** Projeção do caixa para a porta: nada de entidade JPA na saída. */
  private static CashRegisterSummary toSummary(CashRegisterEntity entity) {
    return new CashRegisterSummary(entity.getId(), entity.getCode(), entity.getName());
  }
}
