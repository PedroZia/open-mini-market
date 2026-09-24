package com.minimarket.cash.domain;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Agregado da sessão de caixa (linha “Caixa (sessão)” do §4.4 e BR-10): valor de abertura,
 * movimentos de dinheiro e status, dos quais saem o saldo esperado e a diferença do fechamento.
 * Java puro — sem JPA, Quarkus, Jackson ou HTTP; persistir é trabalho dos casos de uso (passos
 * 606+) através da porta {@code CashSessionStore}.
 *
 * <p>A abertura é o {@link #openingAmount()} e <em>não</em> entra na lista de movimentos: a linha
 * {@code OPENING} existe no ledger só para histórico e somá-la ao saldo esperado contaria o
 * dinheiro duas vezes. Os valores são sempre informados positivos — o sinal é aplicado pelo tipo,
 * na convenção de {@code cash_movements.amount} ({@code SALE} e {@code SUPPLY} positivos, {@code
 * WITHDRAWAL} negativo) —, com escala 2 e arredondamento {@code HALF_UP} (§3 do plano).
 */
public final class CashSession {

  private static final int SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private final BigDecimal openingAmount;
  private final List<Movement> movements = new ArrayList<>();

  private CashSessionStatus status = CashSessionStatus.OPEN;
  private BigDecimal countedAmount;
  private BigDecimal differenceAmount;
  private String closingNotes;

  /**
   * Sessão nova, aberta, com o dinheiro que entrou no caixa na abertura.
   *
   * @param openingAmount valor de abertura, zero ou positivo
   * @throws BusinessException se o valor for nulo ou negativo
   */
  public CashSession(BigDecimal openingAmount) {
    if (openingAmount == null || openingAmount.signum() < 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor de abertura deve ser zero ou positivo");
    }
    this.openingAmount = money(openingAmount);
  }

  public CashSessionStatus status() {
    return status;
  }

  public BigDecimal openingAmount() {
    return openingAmount;
  }

  /** Saldo esperado: abertura + vendas + suprimentos − sangrias. */
  public BigDecimal expectedAmount() {
    BigDecimal expected = openingAmount;
    for (Movement movement : movements) {
      expected = expected.add(movement.amount());
    }
    return money(expected);
  }

  /** Venda: entrada positiva; sem motivo, porque a origem é a própria venda. */
  public void recordSale(BigDecimal amount) {
    record(CashMovementType.SALE, amount, null);
  }

  /** Suprimento: entrada positiva e com motivo obrigatório (BR-10). */
  public void recordSupply(BigDecimal amount, String reason) {
    record(CashMovementType.SUPPLY, amount, reason);
  }

  /** Sangria: saída, com motivo obrigatório (BR-10), guardada com o valor negativo. */
  public void recordWithdrawal(BigDecimal amount, String reason) {
    record(CashMovementType.WITHDRAWAL, amount, reason);
  }

  /**
   * Fecha a sessão com o valor contado na conferência: grava o contado e as observações, calcula a
   * diferença contra o saldo esperado do momento e vira {@code CLOSED}.
   *
   * @throws BusinessException se a sessão já estiver fechada ou faltar o valor contado
   */
  public void close(BigDecimal countedAmount, String closingNotes) {
    if (status != CashSessionStatus.OPEN) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "sessão de caixa já está fechada");
    }
    if (countedAmount == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "valor contado é obrigatório");
    }
    this.countedAmount = money(countedAmount);
    this.differenceAmount = money(this.countedAmount.subtract(expectedAmount()));
    this.closingNotes = closingNotes;
    this.status = CashSessionStatus.CLOSED;
  }

  /** Valor contado no fechamento; nulo enquanto a sessão está aberta. */
  public BigDecimal countedAmount() {
    return countedAmount;
  }

  /** Contado − esperado; positivo sobra, negativo falta. Nulo enquanto a sessão está aberta. */
  public BigDecimal differenceAmount() {
    return differenceAmount;
  }

  /** Observações do fechamento; nulo enquanto a sessão está aberta. */
  public String closingNotes() {
    return closingNotes;
  }

  private void record(CashMovementType type, BigDecimal amount, String reason) {
    if (status != CashSessionStatus.OPEN) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "sessão de caixa fechada não aceita movimento");
    }
    if (amount == null || money(amount).signum() <= 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor do movimento deve ser maior que zero");
    }
    if (requiresReason(type) && (reason == null || reason.isBlank())) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "motivo é obrigatório em sangria e suprimento");
    }
    movements.add(new Movement(type, money(signed(type, amount))));
  }

  private static boolean requiresReason(CashMovementType type) {
    return type == CashMovementType.WITHDRAWAL || type == CashMovementType.SUPPLY;
  }

  private static BigDecimal signed(CashMovementType type, BigDecimal amount) {
    return type == CashMovementType.WITHDRAWAL ? amount.negate() : amount;
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(SCALE, ROUNDING);
  }

  /** Movimento do agregado: tipo e valor já assinado na convenção do ledger. */
  private record Movement(CashMovementType type, BigDecimal amount) {}
}
