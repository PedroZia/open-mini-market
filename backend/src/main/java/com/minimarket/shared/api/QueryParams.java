package com.minimarket.shared.api;

import com.minimarket.shared.domain.FieldValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parse explícito dos filtros tipados das listagens (passo 1007): o conversor implícito do JAX-RS
 * devolveria 404 para um valor que ele não entende, e a convenção da API (§9.2) é 400 {@code
 * VALIDATION_ERROR} com {@code errors[]}, o mesmo contrato do Bean Validation e do {@link
 * FieldValidationException}. O parâmetro chega como texto e é convertido aqui, citando o nome do
 * filtro; nos filtros opcionais, ausente ou em branco é "sem filtro" — nenhum valor inválido chega
 * ao caso de uso.
 */
public final class QueryParams {

  private QueryParams() {}

  /** {@code true} ou {@code false}, sem diferenciar maiúsculas; qualquer outro texto → 400. */
  public static Boolean booleanOf(String value, String field) {
    if (isAbsent(value)) {
      return null;
    }
    String text = value.trim();
    if ("true".equalsIgnoreCase(text)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(text)) {
      return Boolean.FALSE;
    }
    throw new FieldValidationException(field, "deve ser true ou false");
  }

  /** UUID no formato canônico; qualquer outro texto → 400. */
  public static UUID uuidOf(String value, String field) {
    if (isAbsent(value)) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notUuid) {
      throw new FieldValidationException(
          field, "deve ser um UUID, ex.: 0198e2f0-9c1a-7f3e-9f4a-2b6c8d0e1f23");
    }
  }

  /** Valor da constante do enum, ex.: {@code OPEN}; qualquer outro texto → 400. */
  public static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
    if (isAbsent(value)) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim());
    } catch (IllegalArgumentException unknown) {
      throw new FieldValidationException(field, "deve ser um dos valores: " + namesOf(type));
    }
  }

  /** Instante ISO-8601 com offset (§9.1); texto sem hora ou fuso → 400. */
  public static Instant instantOf(String value, String field) {
    if (isAbsent(value)) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim()).toInstant();
    } catch (DateTimeParseException notIso8601) {
      throw new FieldValidationException(
          field, "deve ser ISO-8601 com offset, ex.: 2026-02-01T10:00:00Z");
    }
  }

  /** Inteiro da paginação; texto que não é número → 400. */
  public static int intOf(String value, String field) {
    if (isAbsent(value)) {
      throw new FieldValidationException(field, "deve ser um número inteiro");
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException notANumber) {
      throw new FieldValidationException(field, "deve ser um número inteiro");
    }
  }

  private static boolean isAbsent(String value) {
    return value == null || value.isBlank();
  }

  private static <E extends Enum<E>> String namesOf(Class<E> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
  }
}
