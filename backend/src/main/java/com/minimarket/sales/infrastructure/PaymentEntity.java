package com.minimarket.sales.infrastructure;

import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code payments} (§5.3 do plano): o pagamento da venda. JPA explícito, sem
 * Panache — o id (UUIDv7) chega pronto no agregado, gerado pelo caso de uso (passo 904), e a
 * entidade não sai do módulo: nada de JPA em JSON.
 *
 * <p>Só as colunas que o domínio usa hoje estão mapeadas; {@code external_ref}, {@code
 * authorization_code} e {@code cancel_reason} entram quando houver caso de uso para eles — o {@code
 * Payment.cancel(Instant)} do 902 não guarda motivo. A tabela não tem {@code updated_at} nem {@code
 * version}: o pagamento não é editável, corrigir é cancelar, e quem serializa dois cancelamentos
 * concorrentes é o lock otimista da venda (passos 905+) na mesma transação.
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "sale_id")
  private UUID saleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "method")
  private PaymentMethod method;

  @Column(name = "amount")
  private BigDecimal amount;

  /** Valor entregue pelo cliente, só no dinheiro (BR-05); nulo nas demais formas. */
  @Column(name = "tendered_amount")
  private BigDecimal tenderedAmount;

  /** Troco do dinheiro ({@code tendered_amount − amount}) já calculado pelo domínio (BR-12). */
  @Column(name = "change_amount")
  private BigDecimal changeAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status")
  private PaymentStatus status;

  @Column(name = "created_by_user_id")
  private UUID createdByUserId;

  @Column(name = "created_at")
  private Instant createdAt;

  /** Instante do cancelamento; nulo enquanto o pagamento está aprovado. */
  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  /** Exigido pelo JPA. */
  protected PaymentEntity() {}

  /**
   * Pagamento novo: o imutável entra aqui e o mutável (status e cancelamento) por {@link
   * #syncFrom(Payment)}. Nasce {@code APPROVED}, como o default da coluna e o domínio.
   */
  public PaymentEntity(
      UUID id,
      UUID saleId,
      PaymentMethod method,
      BigDecimal amount,
      BigDecimal tenderedAmount,
      BigDecimal changeAmount,
      UUID createdByUserId,
      Instant createdAt) {
    this.id = id;
    this.saleId = saleId;
    this.method = method;
    this.amount = amount;
    this.tenderedAmount = tenderedAmount;
    this.changeAmount = changeAmount;
    this.createdByUserId = createdByUserId;
    this.createdAt = createdAt;
    this.status = PaymentStatus.APPROVED;
  }

  /**
   * Estado que o agregado manda para a linha: status e instante do cancelamento — o resto do
   * pagamento é imutável. É o caminho do cancelamento ({@code APPROVED → CANCELLED}) e da gravação
   * de um pagamento já cancelado; quem chama é o adaptador, com o domínio como fonte.
   */
  void syncFrom(Payment payment) {
    this.status = payment.status();
    this.cancelledAt = payment.cancelledAt();
  }

  public UUID getId() {
    return id;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public PaymentMethod getMethod() {
    return method;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public BigDecimal getTenderedAmount() {
    return tenderedAmount;
  }

  public BigDecimal getChangeAmount() {
    return changeAmount;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }
}
