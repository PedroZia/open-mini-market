package com.minimarket.auth.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.auth.application.AuthSessionSnapshot;
import com.minimarket.auth.application.AuthSessionStore;
import com.minimarket.auth.application.NewAuthSession;
import com.minimarket.auth.domain.SessionClient;
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
 *
 * <p>Implementa a porta {@link AuthSessionStore} (passo 204a): é por ela que {@code application}
 * cria sessão sem tocar em JPA.
 */
@ApplicationScoped
public class AuthSessionRepository implements AuthSessionStore {

  @Inject EntityManager entityManager;

  /**
   * {@inheritDoc}
   *
   * <p>Traduz o record de aplicação para a entidade: o {@code client} vira o texto aceito pelo
   * check constraint e o resto chega pronto (hash do token, loja, caixa, ip, user agent,
   * instantes).
   */
  @Override
  public UUID insert(NewAuthSession session) {
    return insert(
        new AuthSessionEntity(
            session.userId(),
            session.tokenHash(),
            session.client().name(),
            session.storeId(),
            session.cashRegisterId(),
            session.ip(),
            session.userAgent(),
            session.lastSeenAt(),
            session.expiresAt()));
  }

  /** Gera o id (UUIDv7, §5.1) na aplicação e persiste; a transação é do caso de uso. */
  public UUID insert(AuthSessionEntity session) {
    session.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(session);
    return session.getId();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Traduz a entidade na projeção de aplicação: o caso de uso decide a expiração com o {@code
   * Clock}, o adaptador só garante "não revogada". Expiração não entra no filtro da consulta — a
   * sessão expirada precisa ser encontrada para responder {@code SESSION_EXPIRED}, não "token
   * inválido".
   */
  @Override
  public Optional<AuthSessionSnapshot> findActiveByTokenHash(String tokenHash) {
    List<AuthSessionEntity> found =
        entityManager
            .createQuery(
                "select s from AuthSessionEntity s where s.tokenHash = :tokenHash"
                    + " and s.revokedAt is null",
                AuthSessionEntity.class)
            .setParameter("tokenHash", tokenHash)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? Optional.empty() : Optional.of(toSnapshot(found.getFirst()));
  }

  /** Sessão não revogada pelo id; revogada ou desconhecida devolve vazio (passo 207). */
  @Override
  public Optional<AuthSessionSnapshot> findActiveById(UUID id) {
    List<AuthSessionEntity> found =
        entityManager
            .createQuery(
                "select s from AuthSessionEntity s where s.id = :id and s.revokedAt is null",
                AuthSessionEntity.class)
            .setParameter("id", id)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? Optional.empty() : Optional.of(toSnapshot(found.getFirst()));
  }

  /** Projeção de aplicação da entidade: nunca deixa JPA atravessar a porta (§2.2). */
  private static AuthSessionSnapshot toSnapshot(AuthSessionEntity entity) {
    return new AuthSessionSnapshot(
        entity.getId(),
        entity.getUserId(),
        SessionClient.valueOf(entity.getClient()),
        entity.getStoreId(),
        entity.getCashRegisterId(),
        entity.getLastSeenAt(),
        entity.getExpiresAt());
  }

  /** Sessão pelo id, revogada ou não; vazio para id desconhecido. */
  public Optional<AuthSessionEntity> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(AuthSessionEntity.class, id));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Um {@code update} condicional só na coluna de atividade — a sessão não é carregada nem passa
   * pelo {@code @Version}: {@code last_seen_at} é um sinal de melhor esforço e duas requisições do
   * mesmo token podem tocar a sessão ao mesmo tempo sem que nenhuma falhe por conflito otimista (o
   * banco serializa os updates e o instante mais novo vence). O {@code versioned} mantém o
   * comportamento da entidade — o update conta como modificação e incrementa a versão. Sessão
   * revogada não é atualizada — não há "última vez vista" para token morto — e id desconhecido é
   * no-op.
   */
  @Override
  public void touchLastSeen(UUID id, Instant lastSeenAt) {
    entityManager
        .createQuery(
            "update versioned AuthSessionEntity s set s.lastSeenAt = :lastSeenAt"
                + " where s.id = :id and s.revokedAt is null")
        .setParameter("lastSeenAt", lastSeenAt)
        .setParameter("id", id)
        .executeUpdate();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Já revogada é no-op (mantém instante e motivo originais); id desconhecido também não
   * estoura.
   */
  @Override
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
