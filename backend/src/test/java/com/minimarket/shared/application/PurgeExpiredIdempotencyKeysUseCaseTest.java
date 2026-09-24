package com.minimarket.shared.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link PurgeExpiredIdempotencyKeysUseCase}, sem Quarkus e sem banco: o dublê é
 * da porta {@link IdempotencyKeyStore} e o relógio é fixo, para o corte ser conferido instante a
 * instante. A persistência real é coberta pelo {@code PurgeExpiredIdempotencyKeysIntegrationTest}.
 */
class PurgeExpiredIdempotencyKeysUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");
  private static final String EXPIRED_KEY = "chave-vencida";
  private static final String ON_CUTOFF_KEY = "chave-que-vence-agora";
  private static final String VALID_KEY = "chave-valida";

  private final FakeIdempotencyKeyStore store = new FakeIdempotencyKeyStore();
  private final PurgeExpiredIdempotencyKeysUseCase useCase =
      new PurgeExpiredIdempotencyKeysUseCase();

  @BeforeEach
  void setUp() {
    useCase.store = store;
    useCase.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  @DisplayName(
      "apaga as vencidas (inclusive a do instante do corte), devolve a contagem e preserva a válida")
  void removesExpiredAndKeepsValid() {
    store.put(EXPIRED_KEY, NOW.minusSeconds(1));
    store.put(ON_CUTOFF_KEY, NOW);
    store.put(VALID_KEY, NOW.plusSeconds(1));

    int removed = useCase.execute();

    assertThat(store.cutoff).as("o corte é o Clock injetado").isEqualTo(NOW);
    assertThat(removed).as("a vencida e a que venceu exatamente no corte").isEqualTo(2);
    assertThat(store.stored).as("a válida fica intocada").containsOnlyKeys(VALID_KEY);
  }

  @Test
  @DisplayName("nada vencido: devolve zero e não toca em chave nenhuma")
  void returnsZeroWhenNothingExpired() {
    store.put(VALID_KEY, NOW.plusSeconds(1));

    assertThat(useCase.execute()).isZero();
    assertThat(store.stored).containsOnlyKeys(VALID_KEY);
  }

  /**
   * Dublê da porta (sem banco): guarda a expiração de cada chave e apaga por corte inclusivo, como
   * o {@code deleteExpiredBefore} do adaptador JPQL — o instante do corte fica registrado para o
   * teste conferir de onde ele veio.
   */
  private static final class FakeIdempotencyKeyStore implements IdempotencyKeyStore {

    private final Map<String, Instant> stored = new LinkedHashMap<>();
    private Instant cutoff;

    void put(String key, Instant expiresAt) {
      stored.put(key, expiresAt);
    }

    /** O caso de uso da limpeza não lê chaves. */
    @Override
    public Optional<StoredIdempotentResponse> find(String key) {
      throw new UnsupportedOperationException("a limpeza não lê chaves");
    }

    @Override
    public void insert(NewIdempotencyRecord record, Instant expiresAt) {
      put(record.key(), expiresAt);
    }

    @Override
    public int deleteExpiredBefore(Instant instant) {
      cutoff = instant;
      List<String> expired =
          stored.entrySet().stream()
              .filter(entry -> !entry.getValue().isAfter(instant))
              .map(Map.Entry::getKey)
              .toList();
      expired.forEach(stored::remove);
      return expired.size();
    }
  }
}
