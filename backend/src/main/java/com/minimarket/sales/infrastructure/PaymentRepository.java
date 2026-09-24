package com.minimarket.sales.infrastructure;

import com.minimarket.sales.application.PaymentStore;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador JPA da tabela {@code payments}. Sem {@code @Transactional}: a transação é do caso de
 * uso (§2.2, regra 6) — o cancelamento só vira linha no flush dela, junto do {@code paid_amount}
 * recalculado na venda (passo 905).
 *
 * <p>Implementa a porta {@link PaymentStore}: é por ela que {@code application} grava e lê os
 * pagamentos sem tocar em JPA.
 */
@ApplicationScoped
public class PaymentRepository implements PaymentStore {

  /** Dinheiro em escala 2 (§3 do plano), como o domínio soma. */
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  @Inject EntityManager entityManager;

  /**
   * {@inheritDoc}
   *
   * <p>Ordena por {@code created_at} com o id como desempate: dois pagamentos na mesma transação
   * podem cair no mesmo instante e o UUIDv7 é o que mantém a ordem determinística.
   */
  @Override
  public List<Payment> listBySale(UUID saleId) {
    return entityManager
        .createQuery(
            "select p from PaymentEntity p where p.saleId = :saleId order by p.createdAt, p.id",
            PaymentEntity.class)
        .setParameter("saleId", saleId)
        .getResultList()
        .stream()
        .map(PaymentMapper::toDomain)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public void insert(Payment payment) {
    entityManager.persist(PaymentMapper.toEntity(payment));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Carrega a linha e sincroniza o estado do domínio; quem grava é o flush da transação.
   * Pagamento que não está no banco falha explícito — o caso de uso carrega o pagamento pela porta
   * antes de cancelar, então isso é inconsistência, não entrada do usuário.
   */
  @Override
  public void cancel(Payment payment) {
    PaymentEntity entity = entityManager.find(PaymentEntity.class, payment.id());
    if (entity == null) {
      throw new IllegalStateException(
          "pagamento %s não encontrado para cancelar".formatted(payment.id()));
    }
    entity.syncFrom(payment);
  }

  /** {@inheritDoc} */
  @Override
  public BigDecimal sumApprovedBySale(UUID saleId) {
    BigDecimal total =
        entityManager
            .createQuery(
                "select sum(p.amount) from PaymentEntity p where p.saleId = :saleId"
                    + " and p.status = :status",
                BigDecimal.class)
            .setParameter("saleId", saleId)
            .setParameter("status", PaymentStatus.APPROVED)
            .getSingleResult();
    return total == null ? ZERO : total;
  }
}
