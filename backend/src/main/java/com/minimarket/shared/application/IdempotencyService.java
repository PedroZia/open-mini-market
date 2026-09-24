package com.minimarket.shared.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Grava e lê os registros de idempotência (§8, passo 607a). A escrita é uma transação da camada de
 * aplicação ({@code @Transactional} aqui, nunca no repositório — regra 6): sozinha, commita ao fim;
 * dentro de um caso de uso, participa da transação dele e desfaz junto se ele desfizer.
 *
 * <p>{@code expires_at} = {@link Clock} da aplicação + {@code minimarket.idempotency.ttl} (24 h por
 * configuração): é com essa validade que a limpeza do passo 1004 apaga as linhas. O {@link #find}
 * <em>não</em> filtra a validade — o replay de uma chave ainda não limpa continua devolvendo a
 * resposta gravada.
 *
 * <p>A corrida entre duas chamadas da mesma chave não é decidida aqui: o INSERT do perdedor viola a
 * PK e sobe como {@code ConflictException(IDEMPOTENCY_KEY_REUSED)}; quem chamou relê (fora da
 * transação que morreu) e devolve o replay do vencedor — o {@code IdempotencyGuard} faz isso.
 */
@ApplicationScoped
public class IdempotencyService {

  private final IdempotencyKeyStore store;
  private final Clock clock;
  private final Duration ttl;

  /**
   * Injeção por construtor porque o teste unitário do guard monta o serviço com um dublê do store;
   * o relógio e o TTL continuam vindo por configuração.
   */
  @Inject
  public IdempotencyService(
      IdempotencyKeyStore store,
      Clock clock,
      @ConfigProperty(name = "minimarket.idempotency.ttl", defaultValue = "24h") Duration ttl) {
    this.store = store;
    this.clock = clock;
    this.ttl = ttl;
  }

  /** Leitura pura: o registro da chave, ou vazio se a chamada é a primeira. */
  public Optional<StoredIdempotentResponse> find(String key) {
    return store.find(key);
  }

  /**
   * Grava a resposta da primeira chamada, com {@code expires_at = agora + TTL}. {@code
   * ConflictException(IDEMPOTENCY_KEY_REUSED)} quando outra chamada gravou a chave primeiro.
   */
  @Transactional
  public void record(NewIdempotencyRecord record) {
    store.insert(record, clock.instant().plus(ttl));
  }
}
