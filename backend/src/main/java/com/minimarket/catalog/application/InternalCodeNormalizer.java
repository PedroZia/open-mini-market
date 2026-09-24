package com.minimarket.catalog.application;

import com.minimarket.shared.domain.FieldValidationException;

/**
 * Normalização do código interno — o PLU que a balança imprime na etiqueta (passos 1104d e 1104b3,
 * BR-14). A limpeza é a mesma do {@link BarcodeNormalizer} (trim, sem espaços internos, nulo quando
 * em branco), mas o código interno tem duas regras próprias: só dígitos e o tamanho configurado
 * pela loja ({@code internal_code_length}).
 *
 * <p>O cadastro completa com zeros à esquerda até esse tamanho e é isso que mantém o invariante da
 * etiqueta: o parser extrai exatamente {@code internal_code_length} dígitos do EAN-13, então o
 * código digitado curto (42) e o que a balança imprime (00042) precisam resolver o mesmo produto —
 * o cadastro grava a forma canônica e o bipe completa o que o operador digitar.
 */
public final class InternalCodeNormalizer {

  private InternalCodeNormalizer() {}

  /**
   * Forma canônica do código interno para o cadastro, ou nulo quando a entrada é nula ou em branco
   * (produto sem código interno é permitido, como no barcode). Não numérico ou mais dígitos que o
   * configurado → 400 {@code VALIDATION_ERROR} com o campo {@code internalCode} em {@code errors[]}
   * — a mesma forma da bean validation, decidida pelo caso de uso.
   *
   * @param internalCode código como o cliente o mandou, cru
   * @param internalCodeLength tamanho que a etiqueta da loja imprime ({@code internal_code_length})
   */
  public static String normalize(String internalCode, int internalCodeLength) {
    String digits = BarcodeNormalizer.normalize(internalCode);
    if (digits == null) {
      return null;
    }
    if (!isAsciiDigits(digits)) {
      throw new FieldValidationException("internalCode", "deve conter apenas dígitos");
    }
    if (digits.length() > internalCodeLength) {
      throw new FieldValidationException(
          "internalCode", "não pode ter mais de %d dígitos".formatted(internalCodeLength));
    }
    return pad(digits, internalCodeLength);
  }

  /**
   * Código que o bipe procura no {@code findByInternalCode}: o digitado curto é completado com
   * zeros como o cadastro o gravou (42 → 00042). Qualquer outro código volta como veio — o bipe não
   * valida forma, só não encontra (404) —, e código do tamanho configurado ou maior já é a forma
   * canônica procurada.
   */
  public static String forLookup(String typedCode, int internalCodeLength) {
    if (typedCode == null
        || !isAsciiDigits(typedCode)
        || typedCode.length() >= internalCodeLength) {
      return typedCode;
    }
    return pad(typedCode, internalCodeLength);
  }

  private static String pad(String digits, int internalCodeLength) {
    return "0".repeat(internalCodeLength - digits.length()) + digits;
  }

  /** Dígitos ASCII: {@code Character.isDigit} aceitaria dígitos de outros alfabetos. */
  private static boolean isAsciiDigits(String value) {
    return value.chars().allMatch(character -> character >= '0' && character <= '9');
  }
}
