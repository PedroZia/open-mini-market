package com.minimarket.inventory.infrastructure;

import com.minimarket.inventory.domain.StockMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code stock_movements} (§5.3 do plano): o ledger append-only do estoque que
 * registra toda alteração de saldo com o delta assinado e o {@code balance_after} resultante. JPA
 * explícito, sem Panache: o id (UUIDv7) chega pronto do {@link StockMovementRepository}. A entidade
 * não sai do módulo — nada de JPA em JSON.
 *
 * <p>Append-only na aplicação: só existe insert, nada de update ou delete (o grant da V16 repete a
 * garantia no banco).
 */
@Entity
@Table(name = "stock_movements")
public class StockMovementEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "product_id")
  private UUID productId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type")
  private StockMovementType movementType;

  @Column(name = "quantity_delta")
  private BigDecimal quantityDelta;

  @Column(name = "balance_after")
  private BigDecimal balanceAfter;

  @Column(name = "unit_cost")
  private BigDecimal unitCost;

  @Column(name = "reference_type")
  private String referenceType;

  @Column(name = "reference_id")
  private UUID referenceId;

  @Column(name = "reason")
  private String reason;

  @Column(name = "created_by_user_id")
  private UUID createdByUserId;

  @Column(name = "created_at")
  private Instant createdAt;

  /** Exigido pelo JPA. */
  protected StockMovementEntity() {}

  public StockMovementEntity(
      UUID storeId,
      UUID productId,
      StockMovementType movementType,
      BigDecimal quantityDelta,
      BigDecimal balanceAfter,
      BigDecimal unitCost,
      String referenceType,
      UUID referenceId,
      String reason,
      UUID createdByUserId,
      Instant createdAt) {
    this.storeId = storeId;
    this.productId = productId;
    this.movementType = movementType;
    this.quantityDelta = quantityDelta;
    this.balanceAfter = balanceAfter;
    this.unitCost = unitCost;
    this.referenceType = referenceType;
    this.referenceId = referenceId;
    this.reason = reason;
    this.createdByUserId = createdByUserId;
    this.createdAt = createdAt;
  }

  /**
   * O instante do movimento vem do relógio do caso de uso; o preenchimento aqui espelha o {@code
   * default now()} da coluna para quem construir a entidade sem instante — o caminho da aplicação
   * passa pela porta com o valor já resolvido (passo 703).
   */
  @PrePersist
  void markCreated() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  void assignId(UUID id) {
    this.id = id;
  }

  public UUID getId() {
    return id;
  }

  public UUID getStoreId() {
    return storeId;
  }

  public UUID getProductId() {
    return productId;
  }

  public StockMovementType getMovementType() {
    return movementType;
  }

  public BigDecimal getQuantityDelta() {
    return quantityDelta;
  }

  public BigDecimal getBalanceAfter() {
    return balanceAfter;
  }

  public BigDecimal getUnitCost() {
    return unitCost;
  }

  public String getReferenceType() {
    return referenceType;
  }

  public UUID getReferenceId() {
    return referenceId;
  }

  public String getReason() {
    return reason;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
