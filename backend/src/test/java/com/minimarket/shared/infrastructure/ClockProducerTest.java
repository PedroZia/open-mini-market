package com.minimarket.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitário puro do {@link ClockProducer}, sem Quarkus: o produtor é instanciado direto e o relógio
 * devolvido é conferido amostra a amostra.
 *
 * <p>A propriedade é a que fecha a diferença ns × µs dos testes: a JVM lê o relógio do sistema em
 * nanossegundos e o PostgreSQL guarda {@code timestamptz} em microssegundos <em>arredondando</em> o
 * que recebe, então o instante precisa sair da fonte sem fração de microssegundo — só assim o valor
 * respondido pela API é idêntico ao gravado (a prova ponta a ponta, com o instante da conclusão da
 * venda batendo exatamente com a coluna, está no {@code CompleteSaleConcurrencyResourceTest} e no
 * {@code FullSaleFlowTest}).
 */
class ClockProducerTest {

  @Test
  @DisplayName("o relógio da aplicação trunca o instante no microssegundo do banco")
  void truncatesInstantsToMicroseconds() {
    Clock clock = new ClockProducer().systemUtcClock();

    assertThat(clock.getZone())
        .as("UTC, o mesmo fuso do timestamptz do banco")
        .isEqualTo(ZoneOffset.UTC);

    for (int sample = 0; sample < 100; sample++) {
      Instant instant = clock.instant();
      assertThat(instant.getNano() % 1_000)
          .as(
              "amostra %s (%s): nanos múltiplos de 1000, sem fração que o banco arredondaria",
              sample, instant)
          .isZero();
    }
  }
}
