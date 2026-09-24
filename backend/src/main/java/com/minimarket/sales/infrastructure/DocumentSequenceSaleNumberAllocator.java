package com.minimarket.sales.infrastructure;

import com.minimarket.sales.application.SaleNumberAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Adaptador SQL da série da venda em {@code document_sequences} (§5.3, passo 804). Sem
 * {@code @Transactional}: a transação é do caso de uso (§2.2, regra 6) — se ele desfizer, a
 * alocação vai junto e o número volta para a série.
 *
 * <p>Implementa a porta {@link SaleNumberAllocator}: é por ela que {@code application} tira o
 * número sem tocar em SQL.
 */
@ApplicationScoped
public class DocumentSequenceSaleNumberAllocator implements SaleNumberAllocator {

  /**
   * Alocação numa instrução só, com o {@code doc_type} da venda. O {@code insert} cria a linha na
   * primeira alocação com {@code next_value} em 2, porque o número devolvido é o valor anterior ao
   * incremento ({@code returning next_value - 1}): a primeira venda da loja é a número 1. Nas
   * seguintes, o {@code on conflict ... do update} trava a linha, soma um e devolve o número
   * reservado — é o lock implícito do §8, sem {@code select} prévio que abriria janela para duas
   * transações lerem o mesmo {@code next_value}. O {@code updated_at} é do relógio do banco ({@code
   * timestamptz} em UTC).
   */
  private static final String NEXT_NUMBER_SQL =
      "insert into document_sequences (store_id, doc_type, next_value, updated_at)"
          + " values (:storeId, 'SALE', 2, now())"
          + " on conflict (store_id, doc_type)"
          + " do update set next_value = document_sequences.next_value + 1, updated_at = now()"
          + " returning next_value - 1";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public long nextNumber(UUID storeId) {
    return ((Number)
            entityManager
                .createNativeQuery(NEXT_NUMBER_SQL)
                .setParameter("storeId", storeId)
                .getSingleResult())
        .longValue();
  }
}
