package com.minimarket.shared.domain;

/** Conflito de estado, concorrência ou idempotência (HTTP 409). */
public class ConflictException extends BusinessException {

  public ConflictException(String detail) {
    this(ErrorCode.CONFLICT, detail);
  }

  /** Conflito com código estável próprio (ex.: {@code USERNAME_ALREADY_EXISTS}). */
  public ConflictException(ErrorCode code, String detail) {
    super(code, detail);
  }
}
