package com.minimarket.cash.infrastructure;

import com.minimarket.cash.domain.CashMovementType;
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
 * Mapeamento da tabela {@code cash_movements} (§5.3 do plano): o ledger do dinheiro que entra e sai
 * da sessão de caixa. JPA explícito, sem Panache: o id (UUIDv7) chega pronto do {@link
 * CashSessionRepository}. A entidade não sai do módulo — nada de JPA em JSON.
 *
 * <p>O ledger é append-only na aplicação: só existe insert, nada de update ou delete. {@code
 * amount} é assinado, na convenção da tabela — sangria negativa, o resto positivo.
 */
@Entity
@Table(name = "cash_movements")
public class CashMovementEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "cash_session_id")
  private UUID cashSessionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type")
  private CashMovementType movementType;

  @Column(name = "amount")
  private BigDecimal amount;

  @Column(name = "payment_method")
  private String paymentMethod;

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
  protected CashMovementEntity() {}

  public CashMovementEntity(
      UUID storeId,
      UUID cashSessionId,
      CashMovementType movementType,
      BigDecimal amount,
      String paymentMethod,
      String referenceType,
      UUID referenceId,
      String reason,
      UUID createdByUserId,
      Instant createdAt) {
    this.storeId = storeId;
    this.cashSessionId = cashSessionId;
    this.movementType = movementType;
    this.amount = amount;
    this.paymentMethod = paymentMethod;
    this.referenceType = referenceType;
    this.referenceId = referenceId;
    this.reason = reason;
    this.createdByUserId = createdByUserId;
    this.createdAt = createdAt;
  }

  /**
   * O instante do movimento vem do relógio do caso de uso; o preenchimento aqui espelha o {@code
   * default now()} da coluna para quem construir a entidade sem instante — o caminho da aplicação
   * passa pela porta com o valor já resolvido (passo 606).
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

  public UUID getCashSessionId() {
    return cashSessionId;
  }

  public CashMovementType getMovementType() {
    return movementType;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getPaymentMethod() {
    return paymentMethod;
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
