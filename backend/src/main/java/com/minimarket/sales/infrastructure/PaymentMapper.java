package com.minimarket.sales.infrastructure;

import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentStatus;

/**
 * Conversão entre o pagamento {@link Payment} e a entidade JPA de {@code payments}: nada de JPA
 * fora daqui. A rehidratação usa só os métodos públicos do domínio ({@code new Payment(...)} e
 * {@code cancel(Instant)}), como o §4.1 manda — sem construtor "restore" novo.
 */
final class PaymentMapper {

  private PaymentMapper() {}

  /**
   * Pagamento novo com o estado do domínio; o id é o do agregado (UUIDv7 do caso de uso), como na
   * venda. O troco gravado é o que o domínio calculou (BR-12).
   */
  static PaymentEntity toEntity(Payment payment) {
    PaymentEntity entity =
        new PaymentEntity(
            payment.id(),
            payment.saleId(),
            payment.method(),
            payment.amount(),
            payment.tenderedAmount(),
            payment.changeAmount(),
            payment.createdByUserId(),
            payment.createdAt());
    entity.syncFrom(payment);
    return entity;
  }

  /**
   * Pagamento a partir da linha: o cancelado é rehidratado pelo caminho público do domínio, com o
   * instante gravado. {@code CANCELLED} sem instante é estado que o domínio recusa e falha
   * explícito — a linha não reconstrói o que o agregado não aceita, como o {@code SaleMapper} faz
   * com a venda cancelada sem motivo/autor/instante.
   */
  static Payment toDomain(PaymentEntity entity) {
    Payment payment =
        new Payment(
            entity.getId(),
            entity.getSaleId(),
            entity.getMethod(),
            entity.getAmount(),
            entity.getTenderedAmount(),
            entity.getCreatedByUserId(),
            entity.getCreatedAt());
    if (entity.getStatus() == PaymentStatus.CANCELLED) {
      payment.cancel(entity.getCancelledAt());
    }
    return payment;
  }
}
