package com.minimarket.shared.domain;

/**
 * Violação de regra de negócio detectada no domínio ou na aplicação: carrega o {@link ErrorCode}
 * estável que a API devolve ao cliente.
 */
public class BusinessException extends RuntimeException {

  private final ErrorCode code;

  public BusinessException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
