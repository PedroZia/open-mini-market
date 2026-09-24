package com.minimarket.sales.domain;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Item da venda: o snapshot do produto no instante da inclusão (BR-01) — id, código de barras,
 * nome, unidade e preço — mais a quantidade e o total da linha. Alterar o cadastro do produto
 * depois não toca aqui: o preço congelado é o que a linha usa, e quem muda a quantidade é o
 * agregado {@link Sale}. Java puro — sem JPA, Quarkus, Jackson ou HTTP.
 *
 * <p>{@code quantity} tem escala 3 (venda por peso, ex. banana/kg) e é sempre maior que zero como o
 * check de {@code sale_items.quantity}; {@code unit_price} e {@code line_total} são dinheiro na
 * escala 2. O total da linha é {@code round(quantity × unit_price, 2, HALF_UP)} (BR-02), calculado
 * no construtor e a cada mudança de quantidade — ninguém de fora escreve valor calculado.
 */
public final class SaleItem {

  private static final int MONEY_SCALE = 2;
  private static final int QUANTITY_SCALE = 3;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private final UUID productId;
  private final String barcode;
  private final String name;
  private final String unit;
  private final BigDecimal unitPrice;

  private BigDecimal quantity;
  private BigDecimal lineTotal;

  /**
   * Item novo com o snapshot do produto.
   *
   * @param productId produto vendido
   * @param barcode código de barras lido na inclusão; nulo quando o produto não tem código
   * @param name nome do produto no momento da inclusão
   * @param unit unidade do produto no momento da inclusão ({@code UN} ou {@code KG})
   * @param unitPrice preço unitário no momento da inclusão, zero ou positivo
   * @param quantity quantidade vendida, maior que zero
   * @throws BusinessException se produto, nome, unidade, preço ou quantidade forem inválidos
   */
  public SaleItem(
      UUID productId,
      String barcode,
      String name,
      String unit,
      BigDecimal unitPrice,
      BigDecimal quantity) {
    if (productId == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "produto do item é obrigatório");
    }
    if (name == null || name.isBlank()) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "nome do item é obrigatório");
    }
    if (unit == null || unit.isBlank()) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "unidade do item é obrigatória");
    }
    if (unitPrice == null || unitPrice.signum() < 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "preço unitário deve ser zero ou positivo");
    }
    this.productId = productId;
    this.barcode = barcode;
    this.name = name;
    this.unit = unit;
    this.unitPrice = unitPrice.setScale(MONEY_SCALE, ROUNDING);
    this.quantity = positiveQuantity(quantity);
    this.lineTotal = computeLineTotal();
  }

  public UUID productId() {
    return productId;
  }

  /** Código de barras do snapshot; nulo quando o produto não tinha código na inclusão. */
  public String barcode() {
    return barcode;
  }

  public String name() {
    return name;
  }

  public String unit() {
    return unit;
  }

  public BigDecimal unitPrice() {
    return unitPrice;
  }

  public BigDecimal quantity() {
    return quantity;
  }

  /**
   * {@code round(quantity × unit_price, 2, HALF_UP)} — recalculado a cada mudança de quantidade.
   */
  public BigDecimal lineTotal() {
    return lineTotal;
  }

  /** Só o agregado dono do item muda a quantidade; o total da linha acompanha. */
  void changeQuantity(BigDecimal quantity) {
    this.quantity = positiveQuantity(quantity);
    this.lineTotal = computeLineTotal();
  }

  private BigDecimal computeLineTotal() {
    return quantity.multiply(unitPrice).setScale(MONEY_SCALE, ROUNDING);
  }

  private static BigDecimal positiveQuantity(BigDecimal value) {
    BigDecimal normalized = value == null ? null : value.setScale(QUANTITY_SCALE, ROUNDING);
    if (normalized == null || normalized.signum() <= 0) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "quantidade deve ser maior que zero");
    }
    return normalized;
  }
}
