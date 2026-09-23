package com.minimarket.auth.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA da tabela {@code auth_sessions}. Sem {@code @Transactional}: a transação é do caso
 * de uso (§2.2, regra 6) — os instantes de {@code touchLastSeen} e das revogações também vêm de
 * fora (o relógio é do caso de uso), nunca de {@code now()} do banco.
 *
 * <p>"Ativa" aqui é só "não revogada": a expiração (absoluta e idle, §6.2) é decisão do caso de uso
 * com o {@code Clock} injetado, que também decide quando a sessão deixa de ser atualizada.
 */
@ApplicationScoped
public class AuthSessionRepository {

  @Inject EntityManager entityManager;

  /** Gera o id (UUIDv7, §5.1) na aplicação e persiste; a transação é do caso de uso. */
  public UUID insert(AuthSessionEntity session) {
    session.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(session);
    return session.getId();
  }

  /**
   * Sessão não revogada com o hash informado. Expiração não entra no filtro: cabe ao caso de uso
   * decidir com o {@code Clock} se ela ainda vale.
   */
  public Optional<AuthSessionEntity> findActiveByTokenHash(String tokenHash) {
    List<AuthSessionEntity> found =
        entityManager
            .createQuery(
                "select s from AuthSessionEntity s where s.tokenHash = :tokenHash"
                    + " and s.revokedAt is null",
                AuthSessionEntity.class)
            .setParameter("tokenHash", tokenHash)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
  }

  /** Sessão pelo id, revogada ou não; vazio para id desconhecido. */
  public Optional<AuthSessionEntity> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(AuthSessionEntity.class, id));
  }

  /**
   * Grava o instante de atividade da sessão viva (idle timeout, §6.2). Sessão revogada não é
   * atualizada — não há "última vez vista" para token morto — e id desconhecido é no-op.
   */
  public void touchLastSeen(UUID id, Instant lastSeenAt) {
    findById(id).filter(AuthSessionEntity::isActive).ifPresent(s -> s.markSeen(lastSeenAt));
  }

  /**
   * Revoga a sessão com o motivo informado. Já revogada é no-op (mantém instante e motivo
   * originais); id desconhecido também não estoura.
   */
  public void revoke(UUID id, String reason, Instant revokedAt) {
    findById(id).ifPresent(s -> s.revoke(reason, revokedAt));
  }

  /**
   * Revoga todas as sessões vivas do usuário, com um motivo comum, e devolve quantas foram
   * revogadas — as já revogadas não contam. Carrega as sessões em vez de fazer update em massa para
   * o {@code @Version} de cada uma avançar; o volume por usuário é mínimo (logout de todos os
   * dispositivos, §6.2).
   */
  public int revokeAllByUser(UUID userId, String reason, Instant revokedAt) {
    List<AuthSessionEntity> active = listActiveByUser(userId);
    active.forEach(session -> session.revoke(reason, revokedAt));
    return active.size();
  }

  /** Sessões vivas do usuário, da atividade mais recente para a mais antiga. */
  public List<AuthSessionEntity> listActiveByUser(UUID userId) {
    return entityManager
        .createQuery(
            "select s from AuthSessionEntity s where s.userId = :userId and s.revokedAt is null"
                + " order by s.lastSeenAt desc, s.id",
            AuthSessionEntity.class)
        .setParameter("userId", userId)
        .getResultList();
  }
}
