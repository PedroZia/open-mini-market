package com.minimarket.sales.domain;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Pagamento da venda (§5.3, BR-05): valor positivo na forma escolhida, aprovado na criação e
 * cancelável enquanto a venda não conclui. Java puro — sem JPA, Quarkus, Jackson ou HTTP; quem
 * grava é o caso de uso (passo 904) e a soma dos aprovados que decide a conclusão sai do {@link
 * PaymentTotals}.
 *
 * <p>Troco só existe em dinheiro (BR-05): {@code CASH} exige o valor entregue e calcula {@code
 * change_amount = tendered_amount − amount} aqui dentro, nunca no cliente (BR-12); nas demais
 * formas o valor entregue é recusado e o troco é zero. Dinheiro tem escala 2 com arredondamento
 * {@code HALF_UP} (§3 do plano). O pagamento é imutável: corrigir é cancelar — {@link
 * #cancel(Instant)} leva {@code APPROVED → CANCELLED} e guarda o instante do primeiro cancelamento.
 */
public final class Payment {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private final UUID id;
  private final UUID saleId;
  private final PaymentMethod method;
  private final BigDecimal amount;
  private final BigDecimal tenderedAmount;
  private final BigDecimal changeAmount;
  private final UUID createdByUserId;
  private final Instant createdAt;

  private PaymentStatus status = PaymentStatus.APPROVED;
  private Instant cancelledAt;

  /**
   * Pagamento novo, aprovado e com o troco já calculado pelo servidor (BR-12).
   *
   * @param id identificador do pagamento (UUIDv7 gerado na aplicação)
   * @param saleId venda que está sendo paga
   * @param method forma de pagamento
   * @param amount valor do pagamento, maior que zero
   * @param tenderedAmount valor entregue pelo cliente; obrigatório em {@code CASH} e nulo nas
   *     demais
   * @param createdByUserId operador que registrou o pagamento
   * @param createdAt instante do registro
   * @throws BusinessException se faltar identificação, o valor não for positivo, o dinheiro não
   *     cobrir o pagamento ou outra forma trouxer valor entregue
   */
  public Payment(
      UUID id,
      UUID saleId,
      PaymentMethod method,
      BigDecimal amount,
      BigDecimal tenderedAmount,
      UUID createdByUserId,
      Instant createdAt) {
    if (id == null
        || saleId == null
        || method == null
        || createdByUserId == null
        || createdAt == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR,
          "id, venda, forma de pagamento, autor e instante de criação são obrigatórios");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor do pagamento deve ser maior que zero");
    }
    this.id = id;
    this.saleId = saleId;
    this.method = method;
    this.amount = money(amount);
    this.tenderedAmount = validatedTendered(tenderedAmount);
    this.changeAmount = computedChange();
    this.createdByUserId = createdByUserId;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public UUID saleId() {
    return saleId;
  }

  public PaymentMethod method() {
    return method;
  }

  /** Valor do pagamento — é o que entra na soma dos aprovados (BR-05). */
  public BigDecimal amount() {
    return amount;
  }

  /** Valor entregue pelo cliente; nulo fora do dinheiro (só {@code CASH} tem troco). */
  public BigDecimal tenderedAmount() {
    return tenderedAmount;
  }

  /** {@code tendered_amount − amount} em dinheiro, zero nas demais formas. */
  public BigDecimal changeAmount() {
    return changeAmount;
  }

  public PaymentStatus status() {
    return status;
  }

  public UUID createdByUserId() {
    return createdByUserId;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** Instante do cancelamento; nulo enquanto o pagamento está aprovado. */
  public Instant cancelledAt() {
    return cancelledAt;
  }

  /**
   * Cancela o pagamento aprovado ({@code APPROVED → CANCELLED}) guardando o instante: o pagamento
   * não é editável, corrigir é cancelar e registrar outro. Cancelar de novo é no-op de estado — o
   * primeiro instante é o que vale e o segundo é ignorado; quem decide não gravar é o caso de uso
   * (passo 905).
   *
   * @param cancelledAt instante do cancelamento, obrigatório
   * @throws BusinessException se o instante não for informado
   */
  public void cancel(Instant cancelledAt) {
    if (cancelledAt == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "instante do cancelamento é obrigatório");
    }
    if (status == PaymentStatus.CANCELLED) {
      return;
    }
    this.cancelledAt = cancelledAt;
    this.status = PaymentStatus.CANCELLED;
  }

  /**
   * Valor entregue do dinheiro: obrigatório e maior ou igual ao pagamento em {@code CASH}; recusado
   * nas demais formas, porque troco só existe em dinheiro (BR-05).
   */
  private BigDecimal validatedTendered(BigDecimal tenderedAmount) {
    if (method != PaymentMethod.CASH) {
      if (tenderedAmount != null) {
        throw new BusinessException(
            ErrorCode.BUSINESS_ERROR, "valor entregue só é aceito em pagamento em dinheiro");
      }
      return null;
    }
    if (tenderedAmount == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "pagamento em dinheiro exige o valor entregue");
    }
    BigDecimal tendered = money(tenderedAmount);
    if (tendered.compareTo(amount) < 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor entregue não cobre o pagamento em dinheiro");
    }
    return tendered;
  }

  /** Sem valor entregue não há troco: fora do dinheiro o pagamento é sempre exato. */
  private BigDecimal computedChange() {
    return tenderedAmount == null ? zeroMoney() : tenderedAmount.subtract(amount);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, ROUNDING);
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE);
  }
}
