package com.minimarket.inventory.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code product_stocks} (§5.3 do plano): o saldo por par (loja, produto). JPA
 * explícito, sem Panache: o id (UUIDv7) é gerado pelo {@link ProductStockRepository}. A entidade
 * não sai do módulo — nada de JPA em JSON.
 *
 * <p>A linha nasce pelo SQL nativo do {@code insertIfAbsent} (nunca pelo {@code persist}), porque
 * dois primeiros movimentos simultâneos precisam do {@code on conflict do nothing} do PostgreSQL;
 * não existe constructor público de criação. A mutação do saldo (passo 703) passa pelo {@link
 * #setQuantity}; {@code updated_at} e o {@code @Version} avançam no flush.
 */
@Entity
@Table(name = "product_stocks")
public class ProductStockEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "product_id")
  private UUID productId;

  /** Cache do último {@code balance_after} do ledger; quem muda é o passo 703 sob o lock. */
  @Column(name = "quantity")
  private BigDecimal quantity;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private long version;

  /** Exigido pelo JPA. */
  protected ProductStockEntity() {}

  @PreUpdate
  void markUpdated() {
    updatedAt = Instant.now();
  }

  /**
   * Saldo novo do passo 703; o flush da transação grava e o {@code @PreUpdate} repõe o instante.
   */
  void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
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

  public BigDecimal getQuantity() {
    return quantity;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
