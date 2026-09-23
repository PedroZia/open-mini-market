package com.minimarket.shared.domain;

/** Conflito de estado, concorrência ou idempotência (HTTP 409). */
public class ConflictException extends BusinessException {

  public ConflictException(String detail) {
    super(ErrorCode.CONFLICT, detail);
  }
}
