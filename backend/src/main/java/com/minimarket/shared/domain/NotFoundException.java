package com.minimarket.shared.domain;

/** Recurso inexistente (HTTP 404). */
public class NotFoundException extends BusinessException {

  public NotFoundException(String detail) {
    super(ErrorCode.NOT_FOUND, detail);
  }

  /**
   * Variante com código específico (ex.: {@code USER_NOT_FOUND}) para o cliente reagir à ausência.
   */
  public NotFoundException(ErrorCode code, String detail) {
    super(code, detail);
  }
}
