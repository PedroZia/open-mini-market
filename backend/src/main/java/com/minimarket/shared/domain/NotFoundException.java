package com.minimarket.shared.domain;

/** Recurso inexistente (HTTP 404). */
public class NotFoundException extends BusinessException {

  public NotFoundException(String detail) {
    super(ErrorCode.NOT_FOUND, detail);
  }
}
