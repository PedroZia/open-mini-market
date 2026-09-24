package com.minimarket.shared.application;

import java.time.Instant;
import java.util.Optional;

/**
 * Porta da tabela {@code idempotency_keys} (§5.3, passo 607a): guarda a resposta da primeira
 * chamada de uma operação idempotente e a devolve no replay (§8). Sem {@code @Transactional}: quem
 * demarca a transação é a camada de aplicação, nunca o adaptador (§2.2, regra 6).
 *
 * <p>Fica em {@code shared} porque dinheiro e estoque reutilizam o mecanismo — a primeira operação
 * idempotente da API é a abertura de caixa (passo 607), mas a chave é genérica.
 */
public interface IdempotencyKeyStore {

  /**
   * O registro da chave, ou vazio se ela nunca foi usada. Não filtra {@code expires_at}: a validade
   * existe para a limpeza (passo 1004); enquanto a linha existir, a chave vale.
   */
  Optional<StoredIdempotentResponse> find(String key);

  /**
   * Grava o registro da primeira chamada com a expiração decidida pela aplicação. Chave repetida
   * viola a PK e sobe como {@code ConflictException(IDEMPOTENCY_KEY_REUSED)} — é a corrida entre
   * duas chamadas simultâneas, que quem chamou resolve relendo.
   */
  void insert(NewIdempotencyRecord record, Instant expiresAt);

  /**
   * Apaga as chaves vencidas até o instante informado e devolve quantas linhas saíram — a limpeza
   * diária do passo 1004 usa a contagem só para o log. O corte é inclusivo ({@code expires_at <=
   * instant}): a chave que venceu exatamente no instante do corte também sai.
   */
  int deleteExpiredBefore(Instant instant);
}
