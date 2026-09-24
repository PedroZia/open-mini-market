package com.minimarket.cash.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Conta única do saldo esperado do caixa (§4.4, BR-10): abertura + vendas + suprimentos − sangrias.
 * Java puro — é a mesma regra que o agregado {@link CashSession} aplica em memória (passo 605) e
 * que a consulta da sessão atual aplica sobre os totais somados pelo banco (passo 608), para não
 * existirem duas implementações da mesma conta.
 *
 * <p>O mapa de totais vem com os valores <em>assinados</em>, na convenção de {@code
 * cash_movements.amount} ({@code SALE} e {@code SUPPLY} positivos, {@code WITHDRAWAL} negativo), já
 * somados por tipo. {@code OPENING} fica fora da conta de propósito: a abertura entra por {@code
 * openingAmount} e o movimento {@code OPENING} do ledger é só o registro histórico dela — somá-lo
 * aqui contaria o fundo de troco duas vezes.
 */
public final class CashSessionAmounts {

  private static final int SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** Tipos que entram na conta além da abertura; {@code OPENING} é o próprio valor de abertura. */
  private static final List<CashMovementType> CONTRIBUTING_TYPES =
      List.of(CashMovementType.SALE, CashMovementType.SUPPLY, CashMovementType.WITHDRAWAL);

  private CashSessionAmounts() {}

  /**
   * Saldo esperado: {@code openingAmount + SALE + SUPPLY + WITHDRAWAL}, com as somas já assinadas.
   * Tipo sem movimento fica fora do mapa e aqui conta como zero; o resultado sai na escala 2 com
   * arredondamento {@code HALF_UP} (§3 do plano).
   */
  public static BigDecimal expectedAmount(
      BigDecimal openingAmount, Map<CashMovementType, BigDecimal> totalsByType) {
    BigDecimal expected = openingAmount;
    for (CashMovementType type : CONTRIBUTING_TYPES) {
      BigDecimal total = totalsByType.get(type);
      if (total != null) {
        expected = expected.add(total);
      }
    }
    return expected.setScale(SCALE, ROUNDING);
  }
}
