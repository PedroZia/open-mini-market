package com.minimarket.auth.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Relógio da aplicação: {@link Clock#systemUTC()} em produção, injetável nos casos de uso de sessão
 * (passo 204a) para que a expiração nunca dependa de {@code Instant.now()} espalhado pelo código —
 * os testes com clock controlado (passo 209) trocam o relógio no ponto de injeção.
 */
@ApplicationScoped
public class ClockProducer {

  @Produces
  @ApplicationScoped
  Clock systemUtcClock() {
    return Clock.systemUTC();
  }
}
