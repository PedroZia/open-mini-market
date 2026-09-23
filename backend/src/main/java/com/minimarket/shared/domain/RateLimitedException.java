package com.minimarket.shared.domain;

/**
 * Rate limit atingido (HTTP 429): o cliente precisa esperar antes de repetir a operação. Carrega os
 * segundos até a janela liberar de novo — o mapeador os publica no header {@code Retry-After} do
 * problem+json (§9.2).
 */
public class RateLimitedException extends BusinessException {

  private final int retryAfterSeconds;

  public RateLimitedException(String detail, int retryAfterSeconds) {
    super(ErrorCode.RATE_LIMITED, detail);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** Segundos até valer a pena tentar de novo; nunca menor que 1. */
  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
