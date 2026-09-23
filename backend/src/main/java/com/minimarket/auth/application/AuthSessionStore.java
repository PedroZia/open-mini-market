package com.minimarket.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência da sessão (passos 204a/206); o adaptador JPA fica em {@code
 * auth.infrastructure}. Só tipos de aplicação atravessam: entidade JPA nunca chega aqui.
 */
public interface AuthSessionStore {

  /** Insere a sessão e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewAuthSession session);

  /**
   * Sessão não revogada com o hash informado (passo 206). Expiração não entra no filtro: cabe ao
   * caso de uso decidir com o {@code Clock} se ela ainda vale — o token desconhecido e o revogado
   * são indistinguíveis para quem tenta.
   */
  Optional<AuthSessionSnapshot> findActiveByTokenHash(String tokenHash);

  /**
   * Sessão não revogada pelo id (passo 207): o {@code /auth/me} recebe o id da identidade e precisa
   * do que ela não carrega (loja, caixa, cliente, expiração e último uso). Sessão revogada entre a
   * autenticação e a leitura não é encontrada — vira 401, como token desconhecido.
   */
  Optional<AuthSessionSnapshot> findActiveById(UUID id);

  /**
   * Sessões não revogadas do usuário, da atividade mais recente para a mais antiga (passo 210). A
   * expiração não entra no filtro, como em {@link #findActiveById}: "ativa" aqui é só "não
   * revogada" e o {@code expiresAt} viaja no resumo para o cliente decidir o que mostrar.
   */
  List<UserSessionSummary> listActiveByUser(UUID userId);

  /**
   * Grava o instante de atividade da sessão viva (idle timeout, §6.2) sem carregar a entidade: o
   * update é condicional e conflict-free, porque {@code last_seen_at} é um sinal de melhor esforço
   * e uma disputa entre dois requests do mesmo token não pode derrubar a autenticação. Sessão
   * revogada não é atualizada — não há "última vez vista" para token morto — e id desconhecido é
   * no-op.
   */
  void touchLastSeen(UUID id, Instant lastSeenAt);

  /**
   * Revoga a sessão com o motivo e o instante informados (passo 208). É idempotente: sessão já
   * revogada mantém instante e motivo originais e id desconhecido é no-op — quem chama não precisa
   * saber se a sessão ainda existia, porque o token já deixou de autenticar de qualquer forma.
   */
  void revoke(UUID id, String reason, Instant revokedAt);
}
