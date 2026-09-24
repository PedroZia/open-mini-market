package com.minimarket.shared.infrastructure;

import com.minimarket.shared.application.PurgeExpiredIdempotencyKeysUseCase;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Job diário que apaga as chaves de idempotência vencidas (passo 1004). O cron vem de {@code
 * minimarket.idempotency.cleanup-cron} (03:00 por padrão) e a regra é do {@link
 * PurgeExpiredIdempotencyKeysUseCase} — aqui só se agenda a chamada.
 *
 * <p>A extensão {@code quarkus-scheduler} executa o método num worker com o request context ativo e
 * o caso de uso abre a própria transação; nada disso é responsabilidade deste adaptador.
 */
@ApplicationScoped
public class IdempotencyKeyCleanupJob {

  @Inject PurgeExpiredIdempotencyKeysUseCase purgeExpiredIdempotencyKeysUseCase;

  @Scheduled(cron = "{minimarket.idempotency.cleanup-cron}")
  void purgeExpiredKeys() {
    purgeExpiredIdempotencyKeysUseCase.execute();
  }
}
