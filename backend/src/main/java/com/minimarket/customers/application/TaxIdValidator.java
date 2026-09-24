package com.minimarket.customers.application;

/**
 * CPF do cliente ({@code customers.tax_id}, §5.3): normaliza para só dígitos e confere os dois
 * dígitos verificadores — o algoritmo é pequeno e estável, então não entra biblioteca nova.
 *
 * <p>Sem estado e sem CDI: quem precisa valida instanciando. Cliente sem CPF é permitido (o campo é
 * opcional); {@link #normalize(String)} devolve {@code null} para o campo em branco, e é o caso de
 * uso (502b) que decide o que fazer com ele.
 */
public final class TaxIdValidator {

  private static final int CPF_LENGTH = 11;

  /** Índices dos dois dígitos verificadores no CPF completo. */
  private static final int FIRST_CHECK_DIGIT = 9;

  private static final int SECOND_CHECK_DIGIT = 10;

  /**
   * Só os dígitos do que o cliente digitou: máscara, espaços e separadores caem. Nulo, vazio ou sem
   * nenhum dígito devolve nulo — é o mesmo "sem CPF" que o índice único parcial ignora.
   */
  public String normalize(String taxId) {
    if (taxId == null) {
      return null;
    }
    String digits = taxId.replaceAll("\\D", "");
    return digits.isEmpty() ? null : digits;
  }

  /**
   * Confere o CPF: 11 dígitos, os dois verificadores válidos e nada de sequência repetida (todos os
   * dígitos iguais passa na conta dos verificadores, mas não é CPF). Normaliza antes de conferir,
   * então máscara é aceita; nulo, vazio ou o que não virar 11 dígitos devolve {@code false}.
   */
  public boolean isValid(String taxId) {
    String digits = normalize(taxId);
    if (digits == null || digits.length() != CPF_LENGTH) {
      return false;
    }
    if (digits.chars().distinct().count() == 1) {
      return false;
    }
    return checkDigit(digits, FIRST_CHECK_DIGIT) == digitAt(digits, FIRST_CHECK_DIGIT)
        && checkDigit(digits, SECOND_CHECK_DIGIT) == digitAt(digits, SECOND_CHECK_DIGIT);
  }

  /**
   * Dígito verificador dos primeiros {@code length} dígitos: pesos de {@code length + 1} até 2,
   * resto da divisão por 11 e zero quando o resto é menor que 2.
   */
  private static int checkDigit(String digits, int length) {
    int sum = 0;
    for (int i = 0; i < length; i++) {
      sum += digitAt(digits, i) * (length + 1 - i);
    }
    int remainder = sum % 11;
    return remainder < 2 ? 0 : 11 - remainder;
  }

  private static int digitAt(String digits, int index) {
    return digits.charAt(index) - '0';
  }
}
