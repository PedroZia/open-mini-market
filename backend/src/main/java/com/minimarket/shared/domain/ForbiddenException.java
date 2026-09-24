package com.minimarket.shared.domain;

/** Identidade autenticada sem a permissão exigida pela operação (HTTP 403). */
public class ForbiddenException extends BusinessException {

  public ForbiddenException(String detail) {
    super(ErrorCode.ACCESS_DENIED, detail);
  }
}
