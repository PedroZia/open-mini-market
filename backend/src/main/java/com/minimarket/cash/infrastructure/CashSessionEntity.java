package com.minimarket.cash.infrastructure;

import com.minimarket.cash.domain.CashSessionStatus;
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
 * Mapeamento da tabela {@code cash_sessions} (§5.3 do plano). JPA explícito, sem Panache: o id
 * (UUIDv7) chega pronto do {@link CashSessionRepository}. A entidade não sai do módulo — nada de
 * JPA em JSON.
 *
 * <p>A sessão nasce {@code OPEN} com os campos de fechamento nulos; o passo 611 os preenche ao
 * fechar. O índice único parcial {@code ux_cash_session_open} garante no banco uma única sessão
 * aberta por caixa — a violação sobe como exceção de persistência para o caso de uso traduzir.
 */
@Entity
@Table(name = "cash_sessions")
public class CashSessionEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "cash_register_id")
  private UUID cashRegisterId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status")
  private CashSessionStatus status;

  @Column(name = "opened_by_user_id")
  private UUID openedByUserId;

  @Column(name = "opened_at")
  private Instant openedAt;

  @Column(name = "opening_amount")
  private BigDecimal openingAmount;

  @Column(name = "closed_by_user_id")
  private UUID closedByUserId;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "counted_amount")
  private BigDecimal countedAmount;

  @Column(name = "expected_amount")
  private BigDecimal expectedAmount;

  @Column(name = "difference_amount")
  private BigDecimal differenceAmount;

  @Column(name = "closing_notes")
  private String closingNotes;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private long version;

  /** Exigido pelo JPA. */
  protected CashSessionEntity() {}

  /** Sessão nova nasce aberta, como o check constraint da tabela exige. */
  public CashSessionEntity(
      UUID storeId,
      UUID cashRegisterId,
      UUID openedByUserId,
      Instant openedAt,
      BigDecimal openingAmount) {
    this.storeId = storeId;
    this.cashRegisterId = cashRegisterId;
    this.status = CashSessionStatus.OPEN;
    this.openedByUserId = openedByUserId;
    this.openedAt = openedAt;
    this.openingAmount = openingAmount;
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
   * Fechamento (passo 611): grava a conferência e vira {@code CLOSED}. Quem decide a transição é o
   * caso de uso, que já travou a linha e checou o status — aqui só a mutação dos campos, que o
   * flush do adaptador grava junto com {@code updated_at} e o incremento de {@code version}.
   */
  void close(
      BigDecimal countedAmount,
      BigDecimal expectedAmount,
      BigDecimal differenceAmount,
      String closingNotes,
      UUID closedByUserId,
      Instant closedAt) {
    this.status = CashSessionStatus.CLOSED;
    this.countedAmount = countedAmount;
    this.expectedAmount = expectedAmount;
    this.differenceAmount = differenceAmount;
    this.closingNotes = closingNotes;
    this.closedByUserId = closedByUserId;
    this.closedAt = closedAt;
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

  public UUID getCashRegisterId() {
    return cashRegisterId;
  }

  public CashSessionStatus getStatus() {
    return status;
  }

  public UUID getOpenedByUserId() {
    return openedByUserId;
  }

  public Instant getOpenedAt() {
    return openedAt;
  }

  public BigDecimal getOpeningAmount() {
    return openingAmount;
  }

  public UUID getClosedByUserId() {
    return closedByUserId;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public BigDecimal getCountedAmount() {
    return countedAmount;
  }

  public BigDecimal getExpectedAmount() {
    return expectedAmount;
  }

  public BigDecimal getDifferenceAmount() {
    return differenceAmount;
  }

  public String getClosingNotes() {
    return closingNotes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
