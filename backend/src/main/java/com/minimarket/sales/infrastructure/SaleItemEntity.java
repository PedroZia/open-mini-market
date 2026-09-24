package com.minimarket.sales.infrastructure;

import com.minimarket.sales.domain.SaleItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code sale_items} (§5.3 do plano): o snapshot do produto no instante da
 * inclusão (BR-01) com a quantidade e o total da linha. JPA explícito, sem Panache — o id (UUIDv7)
 * é gerado pelo {@link SaleMapper} e a entidade não sai do módulo: nada de JPA em JSON.
 *
 * <p>{@code line_number} é a posição do item na lista do agregado (1..n) e é a única coisa que o
 * adaptador reposiciona sozinho ao sincronizar uma remoção. {@code discount_amount} não existe no
 * domínio do 802: a coluna fica no default 0 e o mapper não a toca.
 */
@Entity
@Table(name = "sale_items")
public class SaleItemEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "line_number")
  private int lineNumber;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "barcode_snapshot")
  private String barcode;

  @Column(name = "name_snapshot")
  private String name;

  @Column(name = "unit_snapshot")
  private String unit;

  @Column(name = "unit_price")
  private BigDecimal unitPrice;

  @Column(name = "quantity")
  private BigDecimal quantity;

  @Column(name = "discount_amount")
  private BigDecimal discountAmount;

  @Column(name = "line_total")
  private BigDecimal lineTotal;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  /** Exigido pelo JPA. */
  protected SaleItemEntity() {}

  /** Item novo com o snapshot do produto; o zero abaixo espelha o default da coluna de desconto. */
  public SaleItemEntity(
      UUID id,
      UUID saleId,
      int lineNumber,
      UUID productId,
      String barcode,
      String name,
      String unit,
      BigDecimal unitPrice,
      BigDecimal quantity,
      BigDecimal lineTotal) {
    this.id = id;
    this.saleId = saleId;
    this.lineNumber = lineNumber;
    this.productId = productId;
    this.barcode = barcode;
    this.name = name;
    this.unit = unit;
    this.unitPrice = unitPrice;
    this.quantity = quantity;
    this.lineTotal = lineTotal;
    this.discountAmount = BigDecimal.ZERO.setScale(2);
  }

  @PrePersist
  void markCreated() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void markUpdated() {
    updatedAt = Instant.now();
  }

  /**
   * Estado que o agregado manda para a linha: o snapshot inteiro, a quantidade, o total e a
   * posição. O snapshot é reescrito porque remover e incluir o mesmo produto de novo é uma inclusão
   * nova — o preço que vale é o do momento dela (BR-01), não o da linha antiga.
   */
  void syncFrom(SaleItem item, int lineNumber) {
    this.lineNumber = lineNumber;
    this.barcode = item.barcode();
    this.name = item.name();
    this.unit = item.unit();
    this.unitPrice = item.unitPrice();
    this.quantity = item.quantity();
    this.lineTotal = item.lineTotal();
  }

  /**
   * Posição transitória do reagrupamento do update: o adaptador estaciona em número negativo as
   * linhas que vão trocar de lugar antes de gravar a posição final, porque a unique {@code
   * (sale_id, line_number)} não deixa duas linhas ocuparem a mesma posição nem de passagem no mesmo
   * flush.
   */
  void moveToLine(int lineNumber) {
    this.lineNumber = lineNumber;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public int getLineNumber() {
    return lineNumber;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getBarcode() {
    return barcode;
  }

  public String getName() {
    return name;
  }

  public String getUnit() {
    return unit;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
