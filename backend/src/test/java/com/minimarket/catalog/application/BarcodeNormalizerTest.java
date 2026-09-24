package com.minimarket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitário da regra única de normalização do código (passo 1104b1): os três pontos que leem o
 * barcode — cadastro (405), bipe (409) e item da venda (808) — dependem dela para o mesmo código
 * digitar, bipar e cadastrar igual.
 */
class BarcodeNormalizerTest {

  @Test
  @DisplayName("tira as bordas e todos os espaços internos que o leitor às vezes insere")
  void stripsSurroundingAndInnerWhitespace() {
    assertThat(BarcodeNormalizer.normalize(" 7891000000017 ")).isEqualTo("7891000000017");
    assertThat(BarcodeNormalizer.normalize("7891 0000 00017")).isEqualTo("7891000000017");
    assertThat(BarcodeNormalizer.normalize("2\t00042\n00005")).isEqualTo("20004200005");
  }

  @Test
  @DisplayName("nulo, vazio e só espaços viram nulo: código em branco é o mesmo que ausente")
  void blankBecomesNull() {
    assertThat(BarcodeNormalizer.normalize(null)).isNull();
    assertThat(BarcodeNormalizer.normalize("")).isNull();
    assertThat(BarcodeNormalizer.normalize("   ")).isNull();
    assertThat(BarcodeNormalizer.normalize(" \t \n ")).isNull();
  }

  @Test
  @DisplayName("código sem espaço sai intacto, sem inventar zero à esquerda ou caixa alta")
  void keepsTheCodeAsIs() {
    assertThat(BarcodeNormalizer.normalize("00042")).isEqualTo("00042");
    assertThat(BarcodeNormalizer.normalize("abc-123")).isEqualTo("abc-123");
  }
}
