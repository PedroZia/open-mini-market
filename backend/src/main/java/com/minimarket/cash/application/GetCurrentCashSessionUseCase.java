package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionAmounts;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta a sessão atual do caixa (passo 608) — é o que a TUI mostra com o caixa aberto: os totais
 * por tipo de movimento e o saldo esperado. Leitura pura, sem {@code @Transactional}: não grava
 * nada e as consultas são idas só ao banco, como no {@code ListCashRegistersUseCase}.
 *
 * <p>Caixa sem sessão aberta é 404 {@code CASH_SESSION_NOT_OPEN}, com caixa inexistente caindo no
 * mesmo caso: {@link CashSessionStore#findOpenByRegister} devolve vazio para os dois, sem revelar
 * quais caixas existem.
 *
 * <p>O saldo esperado sai da regra única do {@link CashSessionAmounts} sobre os totais que o banco
 * somou ({@link CashSessionStore#sumByType}) — OPENING não entra na soma além de {@code
 * openingAmount}. Os totais são zero-preenchidos aqui para o cliente nunca precisar tratar tipo
 * ausente como zero.
 */
@ApplicationScoped
public class GetCurrentCashSessionUseCase {

  /** Escala do dinheiro (§4.4), para os zeros que o mapa de totais completa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject CashSessionStore cashSessionStore;

  public CurrentCashSessionView execute(UUID cashRegisterId) {
    CashSessionSummary session =
        cashSessionStore
            .findOpenByRegister(cashRegisterId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.CASH_SESSION_NOT_OPEN,
                        "caixa %s não tem sessão aberta".formatted(cashRegisterId)));
    Map<CashMovementType, BigDecimal> totals = zeroFilled(cashSessionStore.sumByType(session.id()));
    return new CurrentCashSessionView(
        session.id(),
        session.cashRegisterId(),
        session.status(),
        session.openedAt(),
        session.openedByUserId(),
        session.openingAmount(),
        CashSessionAmounts.expectedAmount(session.openingAmount(), totals),
        totals);
  }

  /** Os quatro tipos sempre presentes: o banco só devolve tipo que tem movimento. */
  private static Map<CashMovementType, BigDecimal> zeroFilled(
      Map<CashMovementType, BigDecimal> totals) {
    Map<CashMovementType, BigDecimal> filled = new EnumMap<>(CashMovementType.class);
    for (CashMovementType type : CashMovementType.values()) {
      filled.put(type, totals.getOrDefault(type, BigDecimal.ZERO).setScale(SCALE, ROUNDING));
    }
    return filled;
  }
}
