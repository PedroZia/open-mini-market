package com.minimarket.shared.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.time.Duration;

/**
 * Relógio da aplicação (passo 1010): o único {@link Clock} do sistema, injetado por tipo em todos
 * os módulos — login/sessão (passo 204a), caixa, vendas, estoque e idempotência. Mora em {@code
 * shared/infrastructure} porque é infraestrutura comum (§2.2), não da autenticação; ninguém importa
 * esta classe, o ponto de injeção é o tipo {@code Clock}, e os testes com relógio controlado (passo
 * 209) trocam o relógio no ponto de injeção.
 *
 * <p>O instante sai truncado no microssegundo, a precisão do {@code timestamptz} do PostgreSQL: a
 * JVM lê o relógio do sistema em nanossegundos ({@code VM.getNanoTimeAdjustment}), o banco
 * <em>arredonda</em> o que recebe para microssegundos, e o valor relido ficava a até 1 µs do que a
 * operação devolveu — era o que obrigava a tolerância de 1 µs nas comparações de teste. {@link
 * Clock#tick} devolve um relógio que trunca o instante do relógio base na duração pedida, então o
 * valor gravado é exatamente o valor respondido, e o teste compara com igualdade.
 */
@ApplicationScoped
public class ClockProducer {

  @Produces
  @ApplicationScoped
  Clock systemUtcClock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }
}
