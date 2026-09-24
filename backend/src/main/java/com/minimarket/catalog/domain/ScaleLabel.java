package com.minimarket.catalog.domain;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ScaleEmbeddedField;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Etiqueta de balança decodificada (BR-14, passo 1104b2): o código interno (PLU do produto pesável)
 * e o valor que a balança embutiu no EAN-13, já na unidade do campo — kg quando {@code WEIGHT} e
 * reais quando {@code PRICE}. Só decodifica o código: resolver produto, virar item e calcular total
 * é do passo 1104b3. Java puro — sem JPA, Quarkus, Jackson ou HTTP.
 *
 * <p>O formato é configuração da loja ({@link Store}, passo 1104b1): a etiqueta tem os 13 dígitos
 * do EAN-13, começa no {@code internal_barcode_prefix} e o resto é o código interno com {@code
 * internal_code_length} dígitos seguido do valor embutido — um inteiro cru escalado por {@code
 * scale_embedded_decimals} (com 3 casas, {@code 0001234} = 1,234 kg). A config <em>não</em> cobre o
 * dígito verificador: o DV do EAN-13 não é conferido; se as balanças da loja o imprimirem, isso
 * será assunto de um passo futuro. A string chega <em>já normalizada</em> (trim e sem espaços
 * internos, regra única do passo 1104b1) — o parser não mexe no texto.
 */
public record ScaleLabel(String internalCode, ScaleEmbeddedField field, BigDecimal embeddedValue) {

  /** EAN-13: a etiqueta da balança tem exatamente os 13 dígitos do código de barras. */
  private static final int LABEL_LENGTH = 13;

  /** Escala da quantidade no banco ({@code numeric(14,3)}, BR-02), como no item da venda. */
  private static final int QUANTITY_SCALE = 3;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  public ScaleLabel {
    if (internalCode == null || internalCode.isBlank()) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "código interno da etiqueta é obrigatório");
    }
    if (field == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "campo embutido da etiqueta é obrigatório");
    }
    if (embeddedValue == null || embeddedValue.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor embutido da etiqueta deve ser maior que zero");
    }
  }

  /**
   * Decodifica a etiqueta de balança para o código interno e o valor embutido, ou vazio quando o
   * código não é etiqueta da loja — sem o prefixo configurado ou fora dos 13 dígitos do EAN-13.
   * Vazio devolve o código ao caminho normal do bipe (GTIN e código interno digitado, que pode ser
   * curto e até começar com o prefixo, sem virar etiqueta).
   *
   * <p>Dentro do namespace do prefixo, etiqueta malformada é erro, não código de produto: 13
   * caracteres com o prefixo mas fora da config (sem espaço para o código interno e o valor, ou com
   * caractere não numérico no meio) e valor embutido zero ou negativo lançam {@link
   * BusinessException} com {@link ErrorCode#INVALID_INTERNAL_BARCODE} (422).
   *
   * @param barcode código já normalizado (trim, sem espaços internos) que veio do leitor
   * @param store loja atual, dona dos parâmetros da etiqueta
   */
  public static Optional<ScaleLabel> parse(String barcode, Store store) {
    if (barcode == null || barcode.length() != LABEL_LENGTH) {
      return Optional.empty();
    }
    String prefix = store.internalBarcodePrefix();
    if (!barcode.startsWith(prefix)) {
      return Optional.empty();
    }
    // Daqui para baixo está no namespace do prefixo: ou é etiqueta válida ou é código malformado.
    int dataStart = prefix.length();
    int codeEnd = dataStart + store.internalCodeLength();
    if (store.internalCodeLength() < 1 || codeEnd >= LABEL_LENGTH) {
      throw malformed(
          "configuração da etiqueta da loja não comporta código interno e valor embutido em %d dígitos"
              .formatted(LABEL_LENGTH));
    }
    String data = barcode.substring(dataStart);
    if (!isAsciiDigits(data)) {
      throw malformed(
          "etiqueta de balança %s tem caractere não numérico no código interno ou no valor"
              .formatted(barcode));
    }
    BigDecimal embeddedValue =
        new BigDecimal(barcode.substring(codeEnd)).movePointLeft(store.scaleEmbeddedDecimals());
    if (embeddedValue.signum() <= 0) {
      throw malformed(
          "etiqueta de balança %s embute valor zero: peso ou preço deve ser maior que zero"
              .formatted(barcode));
    }
    return Optional.of(
        new ScaleLabel(
            barcode.substring(dataStart, codeEnd), store.scaleEmbeddedField(), embeddedValue));
  }

  /**
   * Quantidade que a etiqueta representa, na escala 3 do banco (BR-02): no peso é o próprio valor
   * embutido (1,234 kg), no preço é {@code embeddedValue / unitPrice} com arredondamento HALF_UP
   * (R$ 19,99 a R$ 9,99/kg = 2,001 kg). O preço não entra na etiqueta de peso — só a de preço
   * depende dele.
   *
   * <p>Etiqueta de preço com preço unitário zero ou negativo seria divisão por zero: é violação de
   * negócio (422 {@link ErrorCode#BUSINESS_ERROR}), não erro de programação. Quantidade que
   * arredonda para 0,000 é recusada depois, pela regra do item da venda.
   *
   * @param unitPrice preço unitário atual do produto, usado só pela etiqueta de preço
   */
  public BigDecimal quantityFor(BigDecimal unitPrice) {
    if (field == ScaleEmbeddedField.WEIGHT) {
      return embeddedValue.setScale(QUANTITY_SCALE, ROUNDING);
    }
    if (unitPrice == null || unitPrice.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "etiqueta de preço exige preço unitário maior que zero");
    }
    return embeddedValue.divide(unitPrice, QUANTITY_SCALE, ROUNDING);
  }

  private static BusinessException malformed(String detail) {
    return new BusinessException(ErrorCode.INVALID_INTERNAL_BARCODE, detail);
  }

  private static boolean isAsciiDigits(String value) {
    return value.chars().allMatch(character -> character >= '0' && character <= '9');
  }
}
