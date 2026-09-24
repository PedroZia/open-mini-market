package com.minimarket.shared.domain;

import java.util.List;

/**
 * Erro de validação de campo decidido pela aplicação, não pela forma: o {@code
 * ValidationExceptionMapper} cobre o Bean Validation e este cobre o que só o caso de uso sabe —
 * p.ex. o caixa informado no login (passo 607b). Sai como 400 {@code VALIDATION_ERROR} com {@code
 * errors[]}, o mesmo contrato dos erros de forma.
 */
public class FieldValidationException extends BusinessException {

  /** Item de {@code errors[]} do problem+json: o campo e a mensagem que o cliente exibe. */
  public record FieldError(String field, String message) {}

  private final List<FieldError> errors;

  public FieldValidationException(String field, String message) {
    super(ErrorCode.VALIDATION_ERROR, "Um ou mais campos são inválidos");
    this.errors = List.of(new FieldError(field, message));
  }

  /** Campos inválidos do erro, na ordem em que o caso de uso os apontou. */
  public List<FieldError> errors() {
    return errors;
  }
}
