package com.minimarket.sales.infrastructure;

import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code sales} (§5.3 do plano): o cabeçalho do agregado. JPA explícito, sem
 * Panache — o id (UUIDv7) chega pronto do agregado e a entidade não sai do módulo: nada de JPA em
 * JSON.
 *
 * <p>Só as colunas que o domínio usa hoje estão mapeadas; {@code discount_authorized_by_user_id}
 * entra quando houver caso de uso para ele. O bloco de cancelamento (passo 813) está mapeado:
 * motivo, autor e instante são estado do agregado desde o {@code Sale.cancel}. {@code
 * paid_amount}/{@code change_amount} (passos 905+) nascem no default da tabela e {@link
 * #syncFrom(Sale)} não os toca — enquanto o agregado não os muda, ninguém os escreve.
 */
@Entity
@Table(name = "sales")
public class SaleEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "number")
  private long number;

  @Column(name = "cash_session_id")
  private UUID cashSessionId;

  @Column(name = "cash_register_id")
  private UUID cashRegisterId;

  /** Vínculo com o cliente (passo 811); nulo é venda anônima. */
  @Column(name = "customer_id")
  private UUID customerId;

  @Column(name = "operator_user_id")
  private UUID operatorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status")
  private SaleStatus status;

  @Column(name = "subtotal")
  private BigDecimal subtotal;

  @Enumerated(EnumType.STRING)
  @Column(name = "discount_type")
  private DiscountType discountType;

  @Column(name = "discount_value")
  private BigDecimal discountValue;

  @Column(name = "discount_amount")
  private BigDecimal discountAmount;

  @Column(name = "discount_reason")
  private String discountReason;

  @Column(name = "total")
  private BigDecimal total;

  /** Total pago (passos 905+); zero até o pagamento. */
  @Column(name = "paid_amount")
  private BigDecimal paidAmount;

  /** Troco (passos 905+); zero até o pagamento. */
  @Column(name = "change_amount")
  private BigDecimal changeAmount;

  @Column(name = "item_count")
  private int itemCount;

  @Column(name = "notes")
  private String notes;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  /** Motivo do cancelamento (passo 813); nulo enquanto a venda não foi cancelada. */
  @Column(name = "cancel_reason")
  private String cancelReason;

  /** Quem cancelou a venda (passo 813); nulo enquanto ela não foi cancelada. */
  @Column(name = "cancelled_by_user_id")
  private UUID cancelledByUserId;

  /** Instante do cancelamento (passo 813); nulo enquanto a venda não foi cancelada. */
  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Version
  @Column(name = "version")
  private long version;

  /** Exigido pelo JPA. */
  protected SaleEntity() {}

  /**
   * Venda nova nasce {@code OPEN} (§4.3); o estado mutável do agregado entra por {@link
   * #syncFrom(Sale)}. {@code created_at} é o instante do agregado, não o do insert — é ele que a
   * ordenação e o filtro da consulta usam. Os zeros abaixo espelham os defaults da tabela: o insert
   * do JPA manda todas as colunas mapeadas e sem eles o {@code not null} estouraria.
   */
  public SaleEntity(
      UUID id,
      UUID storeId,
      long number,
      UUID cashSessionId,
      UUID cashRegisterId,
      UUID operatorUserId,
      String notes,
      Instant createdAt) {
    this.id = id;
    this.storeId = storeId;
    this.number = number;
    this.cashSessionId = cashSessionId;
    this.cashRegisterId = cashRegisterId;
    this.operatorUserId = operatorUserId;
    this.notes = notes;
    this.createdAt = createdAt;
    this.status = SaleStatus.OPEN;
    this.paidAmount = zeroMoney();
    this.changeAmount = zeroMoney();
  }

  @PrePersist
  void markUpdatedAtOnInsert() {
    updatedAt = Instant.now();
  }

  @PreUpdate
  void markUpdatedAt() {
    updatedAt = Instant.now();
  }

  /**
   * Estado que o agregado manda para a linha: status, totais, desconto, cliente, contagem de itens
   * e as datas de criação/conclusão/cancelamento. As colunas de pagamento ficam como estão — o
   * domínio ainda não as governa (passos 905+).
   */
  void syncFrom(Sale sale) {
    this.status = sale.status();
    this.subtotal = sale.subtotal();
    this.discountType = sale.discountType();
    this.discountValue = sale.discountValue();
    this.discountAmount = sale.discountAmount();
    this.discountReason = sale.discountReason();
    this.customerId = sale.customerId();
    this.total = sale.total();
    this.itemCount = sale.itemCount();
    this.completedAt = sale.completedAt();
    this.cancelReason = sale.cancelReason();
    this.cancelledByUserId = sale.cancelledByUserId();
    this.cancelledAt = sale.cancelledAt();
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(2);
  }

  public UUID getId() {
    return id;
  }

  public UUID getStoreId() {
    return storeId;
  }

  public long getNumber() {
    return number;
  }

  public UUID getCashSessionId() {
    return cashSessionId;
  }

  public UUID getCashRegisterId() {
    return cashRegisterId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getOperatorUserId() {
    return operatorUserId;
  }

  public SaleStatus getStatus() {
    return status;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public DiscountType getDiscountType() {
    return discountType;
  }

  public BigDecimal getDiscountValue() {
    return discountValue;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public String getDiscountReason() {
    return discountReason;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public BigDecimal getPaidAmount() {
    return paidAmount;
  }

  public BigDecimal getChangeAmount() {
    return changeAmount;
  }

  public int getItemCount() {
    return itemCount;
  }

  public String getNotes() {
    return notes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public UUID getCancelledByUserId() {
    return cancelledByUserId;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public long getVersion() {
    return version;
  }
}
