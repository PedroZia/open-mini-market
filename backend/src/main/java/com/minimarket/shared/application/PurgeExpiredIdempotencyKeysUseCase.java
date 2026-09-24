package com.minimarket.shared.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Limpeza das chaves de idempotência vencidas (passo 1004): sem ela a tabela cresceria sem fim
 * (§5.3). Uma execução = uma transação (§2.2, regra 6) — o job agendado chama este caso de uso e o
 * adaptador nunca abre transação.
 *
 * <p>O corte é {@code expires_at <= Clock.instant()} — o mesmo instante com que o {@link
 * IdempotencyService} grava {@code agora + minimarket.idempotency.ttl}. A contagem removida volta
 * ao chamador e vira uma linha de log: não há auditoria aqui, porque a limpeza não move dinheiro
 * nem estoque, é higiene da própria tabela.
 */
@ApplicationScoped
public class PurgeExpiredIdempotencyKeysUseCase {

  private static final Logger LOG = Logger.getLogger(PurgeExpiredIdempotencyKeysUseCase.class);

  @Inject IdempotencyKeyStore store;

  /** Relógio da aplicação: o corte da limpeza, nunca {@code Instant.now()} espalhado no código. */
  @Inject Clock clock;

  /** Apaga as chaves vencidas e devolve quantas saíram; zero quando não havia nada vencido. */
  @Transactional
  public int execute() {
    Instant cutoff = clock.instant();
    int removed = store.deleteExpiredBefore(cutoff);
    LOG.infof("chaves de idempotência vencidas removidas: %d (corte em %s)", removed, cutoff);
    return removed;
  }
}
