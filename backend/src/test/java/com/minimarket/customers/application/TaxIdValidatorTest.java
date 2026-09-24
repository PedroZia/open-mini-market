package com.minimarket.customers.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unitários puros do validador de CPF, sem subir o Quarkus. */
class TaxIdValidatorTest {

  private final TaxIdValidator validator = new TaxIdValidator();

  @Test
  @DisplayName("normaliza para só dígitos: máscara, espaços e separadores caem")
  void normalizesToDigits() {
    assertThat(validator.normalize("111.444.777-35")).isEqualTo("11144477735");
    assertThat(validator.normalize(" 111 444 777 35 ")).isEqualTo("11144477735");
    assertThat(validator.normalize("11144477735")).isEqualTo("11144477735");
  }

  @Test
  @DisplayName("sem nenhum dígito devolve nulo: é o cliente sem CPF do índice parcial")
  void normalizesBlankToNull() {
    assertThat(validator.normalize(null)).isNull();
    assertThat(validator.normalize("")).isNull();
    assertThat(validator.normalize("   ")).isNull();
    assertThat(validator.normalize("não informado")).isNull();
  }

  @Test
  @DisplayName("aceita CPF válido, com ou sem máscara")
  void acceptsValidTaxIds() {
    assertThat(validator.isValid("11144477735")).isTrue();
    assertThat(validator.isValid("111.444.777-35")).isTrue();
    assertThat(validator.isValid("52998224725")).isTrue();
    assertThat(validator.isValid("529.982.247-25")).isTrue();
    assertThat(validator.isValid("12345678909")).isTrue();
  }

  @Test
  @DisplayName("rejeita dígito verificador errado")
  void rejectsWrongCheckDigits() {
    assertThat(validator.isValid("11144477736")).as("segundo dígito").isFalse();
    assertThat(validator.isValid("11144477725")).as("primeiro dígito").isFalse();
    assertThat(validator.isValid("52998224735")).isFalse();
  }

  @Test
  @DisplayName("rejeita tamanho diferente de 11 dígitos")
  void rejectsWrongLength() {
    assertThat(validator.isValid("1114447773")).isFalse();
    assertThat(validator.isValid("111444777350")).isFalse();
    assertThat(validator.isValid("123")).isFalse();
  }

  @Test
  @DisplayName("rejeita sequência repetida: passa na conta dos verificadores, mas não é CPF")
  void rejectsRepeatedSequences() {
    assertThat(validator.isValid("00000000000")).isFalse();
    assertThat(validator.isValid("11111111111")).isFalse();
    assertThat(validator.isValid("99999999999")).isFalse();
  }

  @Test
  @DisplayName("rejeita nulo, branco e entrada sem dígitos")
  void rejectsMissingTaxId() {
    assertThat(validator.isValid(null)).isFalse();
    assertThat(validator.isValid("")).isFalse();
    assertThat(validator.isValid("   ")).isFalse();
    assertThat(validator.isValid("não informado")).isFalse();
  }
}
