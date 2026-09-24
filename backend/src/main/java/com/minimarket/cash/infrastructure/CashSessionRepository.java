package com.minimarket.cash.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.cash.application.CashSessionStore;
import com.minimarket.cash.application.CashSessionSummary;
import com.minimarket.cash.application.NewCashMovement;
import com.minimarket.cash.application.NewCashSession;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Adaptador JPA das tabelas {@code cash_sessions} e {@code cash_movements}. Sem
 * {@code @Transactional}: a transação é do caso de uso (§2.2, regra 6) — o lock de {@link
 * #lockById} vale até o fim da transação que o pediu.
 *
 * <p>Implementa a porta {@link CashSessionStore}: é por ela que {@code application} abre a sessão,
 * grava o movimento e fecha sem corrida sem tocar em JPA.
 */
@ApplicationScoped
public class CashSessionRepository implements CashSessionStore {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

  @Inject EntityManager entityManager;

  /**
   * Gera o id (UUIDv7, §5.1) na aplicação e persiste; a transação é do caso de uso.
   *
   * <p>O flush antecipado é o backstop da checagem de sessão aberta do caso de uso: se outro
   * operador abrir o mesmo caixa entre a checagem e este flush, a violação do índice único parcial
   * {@code ux_cash_session_open} vira o mesmo 409 do caminho comum, nunca 500 — e deixa os defaults
   * do banco ({@code created_at}, {@code updated_at} e {@code version}) visíveis na releitura que o
   * caso de uso faz em seguida.
   */
  @Override
  public UUID insert(NewCashSession session) {
    CashSessionEntity entity =
        new CashSessionEntity(
            session.storeId(),
            session.cashRegisterId(),
            session.openedByUserId(),
            session.openedAt(),
            session.openingAmount());
    entity.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(entity);
    flushTranslatingOpenSessionConflict();
    return entity.getId();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
    List<CashSessionEntity> found =
        entityManager
            .createQuery(
                "select s from CashSessionEntity s where s.cashRegisterId = :cashRegisterId"
                    + " and s.status = :status",
                CashSessionEntity.class)
            .setParameter("cashRegisterId", cashRegisterId)
            .setParameter("status", CashSessionStatus.OPEN)
            .setMaxResults(1)
            .getResultList();
    return firstSummary(found);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<CashSessionSummary> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(CashSessionEntity.class, id))
        .map(CashSessionRepository::toSummary);
  }

  /** Gera o id (UUIDv7) na aplicação e persiste o movimento; a transação é do caso de uso. */
  @Override
  public UUID insertMovement(NewCashMovement movement) {
    CashMovementEntity entity =
        new CashMovementEntity(
            movement.storeId(),
            movement.cashSessionId(),
            movement.type(),
            movement.amount(),
            movement.paymentMethod(),
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
  public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
    List<Object[]> rows =
        entityManager
            .createQuery(
                "select m.movementType, sum(m.amount) from CashMovementEntity m"
                    + " where m.cashSessionId = :cashSessionId group by m.movementType",
                Object[].class)
            .setParameter("cashSessionId", cashSessionId)
            .getResultList();
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    for (Object[] row : rows) {
      totals.put((CashMovementType) row[0], (BigDecimal) row[1]);
    }
    return totals;
  }

  /**
   * {@inheritDoc}
   *
   * <p>O {@code SELECT ... FOR UPDATE} é emitido na consulta, não no {@code find} com lock: a linha
   * é travada já na leitura e a transação só solta no commit. Projeção na saída — a entidade fica
   * presa no adaptador.
   */
  @Override
  public Optional<CashSessionSummary> lockById(UUID id) {
    List<CashSessionEntity> found =
        entityManager
            .createQuery(
                "select s from CashSessionEntity s where s.id = :id", CashSessionEntity.class)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setParameter("id", id)
            .getResultList();
    return firstSummary(found);
  }

  private static Optional<CashSessionSummary> firstSummary(List<CashSessionEntity> found) {
    return found.isEmpty() ? Optional.empty() : Optional.of(toSummary(found.getFirst()));
  }

  /**
   * O {@code findOpenByRegister} do caso de uso não é atômico: entre a checagem e a escrita, outro
   * operador pode abrir o mesmo caixa. O flush antecipado faz a violação do índice único parcial
   * {@code ux_cash_session_open} aparecer aqui e virar {@link ConflictException} com o mesmo código
   * do caminho comum — o backstop do banco nunca responde 500. {@code cash_sessions} só tem esse
   * índice único além da chave primária (o id é UUIDv7 gerado na aplicação), então SQLState 23505
   * aqui só pode ser sessão aberta duplicada; qualquer outra falha de persistência sobe como está.
   */
  private void flushTranslatingOpenSessionConflict() {
    try {
      entityManager.flush();
    } catch (ConstraintViolationException exception) {
      if (!UNIQUE_VIOLATION.equals(exception.getSQLState())) {
        throw exception;
      }
      throw new ConflictException(ErrorCode.CASH_REGISTER_ALREADY_OPEN, "caixa já está aberto");
    }
  }

  /** Projeção da sessão para a porta: nada de entidade JPA na saída. */
  private static CashSessionSummary toSummary(CashSessionEntity entity) {
    return new CashSessionSummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getCashRegisterId(),
        entity.getStatus(),
        entity.getOpenedByUserId(),
        entity.getOpenedAt(),
        entity.getOpeningAmount(),
        entity.getClosedByUserId(),
        entity.getClosedAt(),
        entity.getCountedAmount(),
        entity.getExpectedAmount(),
        entity.getDifferenceAmount(),
        entity.getClosingNotes(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }
}
