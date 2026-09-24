package com.minimarket.shared.domain;

/** Identidade autenticada sem a permissão exigida pela operação (HTTP 403). */
public class ForbiddenException extends BusinessException {

  private final String requiredPermission;

  /** 403 sem uma permissão específica por trás: a auditoria registra a recusa sem citá-la. */
  public ForbiddenException(String detail) {
    this(detail, null);
  }

  /**
   * @param requiredPermission código da permissão exigida ({@link Permission#code()}), quando a
   *     recusa nasce de uma checagem de permissão; é ele que o evento {@code ACCESS_DENIED} leva
   *     para {@code details} (passo 309).
   */
  public ForbiddenException(String detail, String requiredPermission) {
    super(ErrorCode.ACCESS_DENIED, detail);
    this.requiredPermission = requiredPermission;
  }

  /** Permissão exigida pela operação recusada; nula quando a recusa não tem uma permissão única. */
  public String requiredPermission() {
    return requiredPermission;
  }
}
