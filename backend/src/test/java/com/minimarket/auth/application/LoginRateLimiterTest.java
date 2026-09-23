package com.minimarket.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.RateLimitedException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link LoginRateLimiter}, sem Quarkus e sem banco, com relógio controlado. O
 * aceite "IPs distintos não se afetam" mora aqui: no teste de API todas as requisições chegam do
 * mesmo endereço de loopback, então só o limitador recebendo o IP por parâmetro prova o isolamento.
 */
class LoginRateLimiterTest {

  private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
  private static final int MAX_ATTEMPTS = 20;
  private static final Duration WINDOW = Duration.ofMinutes(5);
  private static final InetAddress ATTACKER = InetAddress.ofLiteral("192.168.0.10");
  private static final InetAddress OTHER = InetAddress.ofLiteral("192.168.0.11");

  private final MutableClock clock = new MutableClock(NOW);
  private final LoginRateLimiter limiter = new LoginRateLimiter();

  @BeforeEach
  void setUp() {
    limiter.maxAttempts = MAX_ATTEMPTS;
    limiter.window = WINDOW;
    limiter.clock = clock;
  }

  @Test
  @DisplayName("20 falhas do mesmo IP estouram o limite; um IP distinto segue passando")
  void limitsFailuresPerAddress() {
    failTimes(ATTACKER, MAX_ATTEMPTS);

    assertThatThrownBy(() -> limiter.check(ATTACKER))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.RATE_LIMITED);
              // Janela recém-aberta: o Retry-After é o tamanho dela, em segundos inteiros.
              assertThat(exception.retryAfterSeconds()).isEqualTo(300);
            });
    // IPs distintos não se afetam: o outro endereço continua livre.
    assertThatCode(() -> limiter.check(OTHER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a 20ª falha ainda passa; o 429 fica para a tentativa seguinte")
  void limitsOnlyAfterTheCap() {
    failTimes(ATTACKER, MAX_ATTEMPTS - 1);

    // A 20ª tentativa é processada normalmente — o teto é conferido antes de contar a nova falha.
    assertThatCode(() -> limiter.check(ATTACKER)).doesNotThrowAnyException();
    limiter.recordFailure(ATTACKER);

    assertThatThrownBy(() -> limiter.check(ATTACKER)).isInstanceOf(RateLimitedException.class);
  }

  @Test
  @DisplayName("janela vencida libera o IP e o Retry-After encolhe até o fim dela")
  void releasesExpiredWindow() {
    failTimes(ATTACKER, MAX_ATTEMPTS);

    clock.set(NOW.plus(Duration.ofMinutes(4)));
    assertThatThrownBy(() -> limiter.check(ATTACKER))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(60));

    clock.set(NOW.plus(WINDOW));
    assertThatCode(() -> limiter.check(ATTACKER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("login bem-sucedido limpa a contagem do IP: NAT compartilhado não é punido")
  void successClearsAddress() {
    failTimes(ATTACKER, MAX_ATTEMPTS);
    assertThatThrownBy(() -> limiter.check(ATTACKER)).isInstanceOf(RateLimitedException.class);

    limiter.recordSuccess(ATTACKER);

    assertThatCode(() -> limiter.check(ATTACKER)).doesNotThrowAnyException();
    assertThat(limiter.trackedAddresses()).isZero();
  }

  @Test
  @DisplayName("janelas vencidas são descartadas: o mapa não cresce sem limite")
  void discardsExpiredEntries() {
    for (int host = 1; host <= 100; host++) {
      limiter.recordFailure(InetAddress.ofLiteral("10.0.0." + host));
    }
    assertThat(limiter.trackedAddresses()).isEqualTo(100);

    clock.set(NOW.plus(WINDOW));
    limiter.recordFailure(InetAddress.ofLiteral("10.0.1.1"));

    assertThat(limiter.trackedAddresses()).isEqualTo(1);
  }

  @Test
  @DisplayName("IP desconhecido não é limitado nem ocupa memória")
  void ignoresUnknownAddress() {
    assertThatCode(() -> limiter.check(null)).doesNotThrowAnyException();

    limiter.recordFailure(null);
    limiter.recordSuccess(null);

    assertThat(limiter.trackedAddresses()).isZero();
  }

  /** Registra {@code times} falhas do IP, como as recusas 401/423 do caso de uso. */
  private void failTimes(InetAddress ip, int times) {
    for (int attempt = 0; attempt < times; attempt++) {
      limiter.recordFailure(ip);
    }
  }

  /** Relógio controlado pelos testes: o limitador lê o instante, o teste o move. */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
