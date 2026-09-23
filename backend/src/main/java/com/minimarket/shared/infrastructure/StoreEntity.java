package com.minimarket.shared.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code stores} (§5.3 do plano). Só as colunas usadas hoje — a tabela é lida,
 * nunca escrita aqui — e a entidade não sai do módulo: nada de JPA em JSON.
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
}
