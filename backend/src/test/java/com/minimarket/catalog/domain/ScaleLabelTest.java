package com.minimarket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ScaleEmbeddedField;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros da {@link ScaleLabel} (passo 1104b2): a leitura da etiqueta de balança nas
 * configurações da loja (BR-14), sem Quarkus, banco ou HTTP. As etiquetas dos testes são as de 13
 * dígitos que a balança imprime — prefixo + código interno + valor embutido — e tudo que está fora
 * do namespace do prefixo continua sendo código de barras comum.
 */
class ScaleLabelTest {

  /** Configuração padrão da MATRIZ (V21): prefixo "2", código de 5 dígitos, peso com 3 casas. */
  private static final Store WEIGHT_STORE = store("2", 5, ScaleEmbeddedField.WEIGHT, 3);

  /** Etiqueta de 1,234 kg: "2" + "00042" + "0001234". */
  private static final String WEIGHT_LABEL = "2000420001234";

  private static final Store PRICE_STORE = store("2", 5, ScaleEmbeddedField.PRICE, 2);

  @Test
  @DisplayName("etiqueta de peso devolve código interno e valor embutido em kg, na escala da loja")
  void parsesWeightLabel() {
    ScaleLabel label = parsed(WEIGHT_LABEL, WEIGHT_STORE);

    assertThat(label.internalCode()).isEqualTo("00042");
    assertThat(label.field()).isEqualTo(ScaleEmbeddedField.WEIGHT);
    assertThat(label.embeddedValue()).isEqualTo(new BigDecimal("1.234"));
    assertThat(label.quantityFor(new BigDecimal("9.99")))
        .as("etiqueta de peso não depende do preço do produto")
        .isEqualTo(new BigDecimal("1.234"));
  }

  @Test
  @DisplayName("valor embutido escala pelas casas decimais configuradas: 3, 2 e 0 casas")
  void scalesEmbeddedValueByConfiguredDecimals() {
    assertThat(parsed(WEIGHT_LABEL, WEIGHT_STORE).embeddedValue())
        .isEqualTo(new BigDecimal("1.234"));

    assertThat(parsed("2000420000500", store("2", 5, ScaleEmbeddedField.WEIGHT, 2)).embeddedValue())
        .isEqualTo(new BigDecimal("5.00"));

    assertThat(parsed("2000420000012", store("2", 5, ScaleEmbeddedField.WEIGHT, 0)).embeddedValue())
        .isEqualTo(new BigDecimal("12"));
  }

  @Test
  @DisplayName(
      "quantidade do peso fica na escala 3 do banco, inclusive com 0 e 6 casas na etiqueta")
  void weightQuantityUsesQuantityScale() {
    assertThat(
            parsed("2000420000012", store("2", 5, ScaleEmbeddedField.WEIGHT, 0)).quantityFor(null))
        .isEqualTo(new BigDecimal("12.000"));

    assertThat(
            parsed("2000420001234", store("2", 5, ScaleEmbeddedField.WEIGHT, 6)).quantityFor(null))
        .as("0,001234 kg arredonda para 1 grama")
        .isEqualTo(new BigDecimal("0.001"));

    assertThat(
            parsed("2000420000500", store("2", 5, ScaleEmbeddedField.WEIGHT, 6)).quantityFor(null))
        .as("0,0005 kg empata e sobe (HALF_UP), não vai para o par")
        .isEqualTo(new BigDecimal("0.001"));
  }

  @Test
  @DisplayName("etiqueta de preço divide o total embutido pelo preço do produto na escala 3")
  void parsesPriceLabelAndDividesByUnitPrice() {
    ScaleLabel label = parsed("2000420001999", PRICE_STORE);

    assertThat(label.internalCode()).isEqualTo("00042");
    assertThat(label.field()).isEqualTo(ScaleEmbeddedField.PRICE);
    assertThat(label.embeddedValue()).isEqualTo(new BigDecimal("19.99"));
    assertThat(label.quantityFor(new BigDecimal("9.99")))
        .as("R$ 19,99 a R$ 9,99/kg = 2,001001... kg")
        .isEqualTo(new BigDecimal("2.001"));
  }

  @Test
  @DisplayName("divisão exata da etiqueta de preço também sai com 3 casas")
  void priceDivisionKeepsQuantityScale() {
    assertThat(parsed("2000420001000", PRICE_STORE).quantityFor(new BigDecimal("4.00")))
        .isEqualTo(new BigDecimal("2.500"));
  }

  @Test
  @DisplayName("empate na terceira casa da etiqueta de preço arredonda HALF_UP, não HALF_EVEN")
  void priceQuantityRoundsHalfUp() {
    ScaleLabel label = new ScaleLabel("00042", ScaleEmbeddedField.PRICE, new BigDecimal("0.0125"));

    assertThat(label.quantityFor(BigDecimal.ONE))
        .as("0,0125 arredonda para 0,013 (HALF_EVEN daria 0,012)")
        .isEqualTo(new BigDecimal("0.013"));
  }

  @Test
  @DisplayName("etiqueta de preço com preço unitário zero ou negativo é 422, sem divisão por zero")
  void priceLabelRequiresPositiveUnitPrice() {
    ScaleLabel label = parsed("2000420001999", PRICE_STORE);

    for (BigDecimal unitPrice : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-9.99"))) {
      assertThatThrownBy(() -> label.quantityFor(unitPrice))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.BUSINESS_ERROR);
                assertThat(exception).hasMessageContaining("preço unitário maior que zero");
              });
    }
  }

  @Test
  @DisplayName(
      "valor embutido zero ou negativo é etiqueta malformada: 422 INVALID_INTERNAL_BARCODE")
  void rejectsZeroEmbeddedValue() {
    assertMalformed(() -> ScaleLabel.parse("2000420000000", WEIGHT_STORE), "valor zero");
    assertMalformed(() -> ScaleLabel.parse("2000420000000", PRICE_STORE), "valor zero");
  }

  @Test
  @DisplayName("não-dígito dentro do namespace do prefixo é etiqueta malformada")
  void rejectsNonDigitInPrefixNamespace() {
    assertMalformed(() -> ScaleLabel.parse("2000A20001234", WEIGHT_STORE), "não numérico");
    assertMalformed(() -> ScaleLabel.parse("200042000123X", WEIGHT_STORE), "não numérico");
  }

  @Test
  @DisplayName("código fora dos 13 dígitos do EAN-13 não é etiqueta — vira código comum")
  void codeOutsideEan13IsNotALabel() {
    assertThat(ScaleLabel.parse("200042000123", WEIGHT_STORE)).as("12 dígitos").isEmpty();
    assertThat(ScaleLabel.parse("20004200012345", WEIGHT_STORE)).as("14 dígitos").isEmpty();
    assertThat(ScaleLabel.parse("2000420", WEIGHT_STORE)).as("7 dígitos").isEmpty();
    assertThat(ScaleLabel.parse("", WEIGHT_STORE)).isEmpty();
    assertThat(ScaleLabel.parse(null, WEIGHT_STORE)).isEmpty();
  }

  @Test
  @DisplayName("código de outro prefixo não é etiqueta desta loja")
  void codeOfAnotherPrefixIsNotALabel() {
    assertThat(ScaleLabel.parse("7891000000017", WEIGHT_STORE))
        .as("GTIN 789... é código de produto")
        .isEmpty();
    assertThat(ScaleLabel.parse("9000420001234", WEIGHT_STORE)).isEmpty();

    assertThat(parsed("9000420001234", store("9", 5, ScaleEmbeddedField.WEIGHT, 3)).internalCode())
        .isEqualTo("00042");
  }

  @Test
  @DisplayName("código interno digitado curto que começa com o prefixo não vira etiqueta")
  void shortInternalCodeStartingWithPrefixIsNotALabel() {
    assertThat(ScaleLabel.parse("20042", WEIGHT_STORE))
        .as("código interno de 5 dígitos, o mesmo tamanho que a loja configura")
        .isEmpty();
    assertThat(ScaleLabel.parse("200042", WEIGHT_STORE)).isEmpty();
    assertThat(ScaleLabel.parse("2", WEIGHT_STORE)).isEmpty();
  }

  @Test
  @DisplayName("prefixo com mais de um dígito é respeitado no corte do código interno")
  void respectsLongerPrefix() {
    ScaleLabel label = parsed("2200042001234", store("22", 5, ScaleEmbeddedField.WEIGHT, 3));

    assertThat(label.internalCode()).isEqualTo("00042");
    assertThat(label.embeddedValue()).isEqualTo(new BigDecimal("1.234"));
  }

  @Test
  @DisplayName("código interno na fronteira do tamanho: ainda cabe o valor embutido")
  void internalCodeLengthFrontierStillParses() {
    ScaleLabel label = parsed("2000000000425", store("2", 11, ScaleEmbeddedField.WEIGHT, 3));

    assertThat(label.internalCode()).isEqualTo("00000000042");
    assertThat(label.embeddedValue()).isEqualTo(new BigDecimal("0.005"));
    assertThat(label.quantityFor(null)).isEqualTo(new BigDecimal("0.005"));
  }

  @Test
  @DisplayName("configuração sem espaço para código interno e valor é etiqueta malformada")
  void rejectsConfigThatDoesNotFitEan13() {
    assertMalformed(
        () -> ScaleLabel.parse("2000000000042", store("2", 12, ScaleEmbeddedField.WEIGHT, 3)),
        "não comporta");
    assertMalformed(
        () -> ScaleLabel.parse(WEIGHT_LABEL, store("2", 13, ScaleEmbeddedField.WEIGHT, 3)),
        "não comporta");
    assertMalformed(
        () -> ScaleLabel.parse(WEIGHT_LABEL, store("2", 0, ScaleEmbeddedField.WEIGHT, 3)),
        "não comporta");
  }

  @Test
  @DisplayName("valor embutido sem dígitos depois do prefixo é malformado, não código comum")
  void prefixWithoutDataIsMalformed() {
    assertMalformed(
        () ->
            ScaleLabel.parse(
                "2222222222222", store("2222222222222", 5, ScaleEmbeddedField.WEIGHT, 3)),
        "não comporta");
  }

  @Test
  @DisplayName("o próprio valor deixa claro que código, campo e valor são obrigatórios")
  void rejectsInvalidValueObjectState() {
    assertThatThrownBy(() -> new ScaleLabel(" ", ScaleEmbeddedField.WEIGHT, BigDecimal.ONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.BUSINESS_ERROR);
              assertThat(exception).hasMessageContaining("código interno");
            });
    assertThatThrownBy(() -> new ScaleLabel("00042", null, BigDecimal.ONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.BUSINESS_ERROR);
              assertThat(exception).hasMessageContaining("campo embutido");
            });
    for (BigDecimal embeddedValue :
        Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.234"))) {
      assertThatThrownBy(() -> new ScaleLabel("00042", ScaleEmbeddedField.WEIGHT, embeddedValue))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.BUSINESS_ERROR);
                assertThat(exception).hasMessageContaining("valor embutido");
              });
    }
  }

  private static ScaleLabel parsed(String barcode, Store store) {
    return ScaleLabel.parse(barcode, store)
        .orElseThrow(() -> new AssertionError("código não virou etiqueta: " + barcode));
  }

  /** Etiqueta malformada: 422 com o código estável que a API devolve (passo 1104b3). */
  private static void assertMalformed(ThrowingCallable call, String messageFragment) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_INTERNAL_BARCODE);
              assertThat(exception.code().status()).isEqualTo(422);
              assertThat(exception).hasMessageContaining(messageFragment);
            });
  }

  private static Store store(
      String prefix, int internalCodeLength, ScaleEmbeddedField field, int decimals) {
    return new Store(
        UUID.fromString("0198f000-0000-7000-8000-000000000001"),
        "MATRIZ",
        "Minimercado Matriz",
        false,
        new BigDecimal("100.00"),
        prefix,
        internalCodeLength,
        field,
        decimals);
  }
}
