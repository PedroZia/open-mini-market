package com.minimarket.shared.domain;

import java.util.Locale;

/**
 * Catálogo dos códigos de erro estáveis da API (§9.2 do plano): o {@code code} é o que os clientes
 * usam em lógica, o título é para humano e o status é o HTTP correspondente.
 */
public enum ErrorCode {
  VALIDATION_ERROR(400, "Dados inválidos"),
  UNKNOWN_ROLE(400, "Papel desconhecido"),
  NOT_FOUND(404, "Recurso não encontrado"),
  USER_NOT_FOUND(404, "Usuário não encontrado"),
  METHOD_NOT_ALLOWED(405, "Método não permitido"),
  CONFLICT(409, "Conflito de estado"),
  USERNAME_ALREADY_EXISTS(409, "Username já está em uso"),
  BUSINESS_ERROR(422, "Regra de negócio violada"),
  INTERNAL_ERROR(500, "Erro interno");

  private static final String TYPE_BASE = "https://minimarket.local/problems/";

  private final int status;
  private final String title;

  ErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }

  /** URI que identifica o tipo do problema, derivada do código (RFC 9457). */
  public String type() {
    return TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
