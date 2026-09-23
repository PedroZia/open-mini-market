package com.minimarket.auth.application;

import com.minimarket.shared.domain.RateLimitedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Rate limit de tentativas de login por IP (§6.3.5, passo 212): janela fixa em memória, sem Redis
 * nem fila — suficiente para conter a varredura de usuários em uma instância. O IP é sempre
 * recebido por parâmetro (nunca lido daqui), o que permite testar "IPs distintos não se afetam" sem
 * depender da origem real da conexão.
 *
 * <p>O IP vem do {@code remoteAddress()} da conexão, não de {@code X-Forwarded-For}: enquanto a
 * aplicação puder ser acessada direto, o header seria spoofável e transformaria o limite em
 * enfeite. O limite definitivo é do proxy reverso (passo 1305 da Fase 13, citado como 1402 no texto
 * do passo 212) e este contador deve ser removido quando ele entrar — em memória ele não vale para
 * múltiplas instâncias.
 *
 * <p>Falha de credenciais (401) e conta bloqueada (423) contam para o IP; a decisão do 429 acontece
 * antes de processar a tentativa, então o teto estourado barra até quem traz a senha correta até a
 * janela vencer. O login bem-sucedido limpa a contagem do IP: sem isso um NAT compartilhado (a loja
 * inteira) acabaria punido pelas falhas de um único operador.
 */
@ApplicationScoped
public class LoginRateLimiter {

  /** Mensagem do 429; o quando tentar de novo vai no header {@code Retry-After}. */
  static final String RATE_LIMITED_DETAIL =
      "muitas tentativas de login; tente novamente mais tarde";

  /** Falhas por IP que disparam o 429 (§6.3.5): 20 por configuração. */
  @ConfigProperty(name = "minimarket.security.login.rate-limit.max-attempts")
  int maxAttempts;

  /** Tamanho da janela fixa por IP (§6.3.5): 5 min por configuração. */
  @ConfigProperty(name = "minimarket.security.login.rate-limit.window")
  Duration window;

  /** Relógio da aplicação: o mesmo dos casos de uso de sessão, injetável no teste. */
  @Inject Clock clock;

  /**
   * Janelas por IP. O mapa é limpo a cada falha registrada, então o tamanho fica limitado ao número
   * de IPs que falharam dentro da janela corrente — não vaza memória (§6.3.5).
   */
  private final ConcurrentMap<InetAddress, Window> windows = new ConcurrentHashMap<>();

  /**
   * Recusa com 429 quando o IP já estourou o teto da janela corrente. IP desconhecido (o Vert.x
   * pode não informar o endereço remoto) não é limitado: sem origem não há o que contar.
   */
  public void check(InetAddress ip) {
    if (ip == null) {
      return;
    }
    Window current = windows.get(ip);
    if (current == null) {
      return;
    }
    Instant now = clock.instant();
    if (!isExpired(current, now) && current.failures() >= maxAttempts) {
      throw new RateLimitedException(RATE_LIMITED_DETAIL, retryAfterSeconds(current, now));
    }
  }

  /** Soma a falha do IP à janela corrente, abrindo uma nova quando a anterior venceu. */
  public void recordFailure(InetAddress ip) {
    if (ip == null) {
      return;
    }
    Instant now = clock.instant();
    windows.compute(
        ip,
        (key, current) ->
            current == null || isExpired(current, now)
                ? new Window(now, 1)
                : new Window(current.startedAt(), current.failures() + 1));
    windows.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
  }

  /** Login bem-sucedido zera a contagem do IP: NAT compartilhado não herda falha de ninguém. */
  public void recordSuccess(InetAddress ip) {
    if (ip != null) {
      windows.remove(ip);
    }
  }

  /** Quantidade de IPs com janela viva; visível para o teste conferir o descarte das vencidas. */
  int trackedAddresses() {
    return windows.size();
  }

  /** Janela vencida libera o IP: o início mais o tamanho já ficaram no passado. */
  private boolean isExpired(Window window, Instant now) {
    return !now.isBefore(window.startedAt().plus(this.window));
  }

  /** Segundos inteiros até o fim da janela, arredondados para cima e limitados a ela. */
  private int retryAfterSeconds(Window window, Instant now) {
    long remainingMillis =
        this.window.toMillis() - Duration.between(window.startedAt(), now).toMillis();
    long seconds = Math.max(1, Math.min(this.window.toSeconds(), (remainingMillis + 999) / 1000));
    return (int) seconds;
  }

  /** Janela fixa de um IP: quando começou e quantas falhas acumulou nela. */
  private record Window(Instant startedAt, int failures) {}
}
