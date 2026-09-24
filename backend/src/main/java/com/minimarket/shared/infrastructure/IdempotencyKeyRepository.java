package com.minimarket.shared.infrastructure;

import com.minimarket.shared.application.IdempotencyKeyStore;
import com.minimarket.shared.application.NewIdempotencyRecord;
import com.minimarket.shared.application.StoredIdempotentResponse;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Adaptador JPA da tabela {@code idempotency_keys} (§5.3). Sem {@code @Transactional}: a transação
 * é do {@link com.minimarket.shared.application.IdempotencyService} (regra 6).
 *
 * <p>O {@code insert} faz flush antecipado para a violação de PK da corrida aparecer aqui e virar
 * {@link ConflictException} com o código estável — mesmo padrão do {@code CashSessionRepository}.
 * Chave repetida é 409 {@code IDEMPOTENCY_KEY_REUSED} (quem chamou relê e decide entre o replay do
 * vencedor e o 409); qualquer outra falha de persistência sobe como está.
 */
@ApplicationScoped
public class IdempotencyKeyRepository implements IdempotencyKeyStore {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public Optional<StoredIdempotentResponse> find(String key) {
    return Optional.ofNullable(entityManager.find(IdempotencyKeyEntity.class, key))
        .map(IdempotencyKeyRepository::toStoredResponse);
  }

  /** {@inheritDoc} */
  @Override
  public void insert(NewIdempotencyRecord record, Instant expiresAt) {
    entityManager.persist(
        new IdempotencyKeyEntity(
            record.key(),
            record.userId(),
            record.method(),
            record.path(),
            record.requestHash(),
            record.statusCode(),
            record.responseBody(),
            expiresAt));
    flushTranslatingDuplicateKey();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Bulk delete em JPQL: apaga por {@code expires_at} sem carregar entidade nenhuma — a
   * varredura usa o índice {@code ix_idempotency_keys_expires_at} da V14. A transação é de quem
   * chama (o caso de uso da limpeza), como no resto da porta.
   */
  @Override
  public int deleteExpiredBefore(Instant instant) {
    return entityManager
        .createQuery("delete from IdempotencyKeyEntity k where k.expiresAt <= :instant")
        .setParameter("instant", instant)
        .executeUpdate();
  }

  /**
   * A leitura do guard não é atômica: entre ela e este INSERT, outra chamada da mesma chave pode
   * gravar. O flush antecipado faz a violação da PK aparecer aqui e virar o 409 do caminho comum; a
   * releitura de quem chamou (fora da transação que morreu) enxerga o vencedor já comitado.
   */
  private void flushTranslatingDuplicateKey() {
    try {
      entityManager.flush();
    } catch (ConstraintViolationException exception) {
      if (!UNIQUE_VIOLATION.equals(exception.getSQLState())) {
        throw exception;
      }
      throw new ConflictException(
          ErrorCode.IDEMPOTENCY_KEY_REUSED, "chave de idempotência já utilizada");
    }
  }

  /** Projeção para a porta: nada de entidade JPA na saída (§2.2). */
  private static StoredIdempotentResponse toStoredResponse(IdempotencyKeyEntity entity) {
    return new StoredIdempotentResponse(
        entity.getStatusCode(),
        entity.getResponseBody(),
        entity.getRequestHash(),
        entity.getMethod(),
        entity.getPath(),
        entity.getUserId());
  }
}
