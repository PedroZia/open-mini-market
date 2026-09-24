package com.minimarket.shared.infrastructure;

import com.minimarket.shared.domain.ScaleEmbeddedField;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code stores} (§5.3 do plano). Só as colunas usadas hoje — a tabela é lida,
 * nunca escrita aqui — e a entidade não sai do módulo: nada de JPA em JSON.
 *
 * <p>As colunas da etiqueta de balança (passo 1104b1) são lidas como o resto: o enum de domínio
 * fica com o {@code @Enumerated} da coluna de texto, como nas outras tabelas.
 */
@Entity
@Table(name = "stores")
public class StoreEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "code")
  private String code;

  @Column(name = "name")
  private String name;

  @Column(name = "allow_negative_stock")
  private boolean allowNegativeStock;

  @Column(name = "max_discount_percent", precision = 5, scale = 2)
  private BigDecimal maxDiscountPercent;

  @Column(name = "internal_barcode_prefix")
  private String internalBarcodePrefix;

  @Column(name = "internal_code_length")
  private int internalCodeLength;

  @Enumerated(EnumType.STRING)
  @Column(name = "scale_embedded_field")
  private ScaleEmbeddedField scaleEmbeddedField;

  @Column(name = "scale_embedded_decimals")
  private int scaleEmbeddedDecimals;

  /** Exigido pelo JPA. */
  protected StoreEntity() {}

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public boolean isAllowNegativeStock() {
    return allowNegativeStock;
  }

  public BigDecimal getMaxDiscountPercent() {
    return maxDiscountPercent;
  }

  public String getInternalBarcodePrefix() {
    return internalBarcodePrefix;
  }

  public int getInternalCodeLength() {
    return internalCodeLength;
  }

  public ScaleEmbeddedField getScaleEmbeddedField() {
    return scaleEmbeddedField;
  }

  public int getScaleEmbeddedDecimals() {
    return scaleEmbeddedDecimals;
  }
}
