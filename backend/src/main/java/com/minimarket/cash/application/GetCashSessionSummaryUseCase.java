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
 * Resumo do fechamento da sessão de caixa (passo 612): esperado × contado com os totais por tipo de
 * movimento, para a retaguarda conferir a gaveta. Leitura pura, sem {@code @Transactional}, como o
 * {@code GetCurrentCashSessionUseCase} (passo 608) — e com a mesma conta: o esperado sai da regra
 * única do {@link CashSessionAmounts} sobre os totais que o banco somou ({@link
 * CashSessionStore#sumByType}), com {@code OPENING} fora da soma além de {@code openingAmount}, e
 * os totais são zero-preenchidos com os quatro tipos, porque o cliente desenha todos.
 *
 * <p>A quebra por forma de pagamento (passo 909) vem da porta invertida {@link SessionSalesLookup}:
 * o caixa não depende de {@code sales} (§2.2) e o shape estável das cinco formas é garantido pelo
 * adaptador, que é quem conhece o enum.
 *
 * <p>O contado e a diferença são os da sessão: nulos enquanto ela está aberta e preenchidos pelo
 * fechamento (passo 611). Id desconhecido é 404 {@code CASH_SESSION_NOT_FOUND}, como no detalhe.
 */
@ApplicationScoped
public class GetCashSessionSummaryUseCase {

  /** Escala do dinheiro (§4.4), para os zeros que o mapa de totais completa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject CashSessionStore cashSessionStore;

  /**
   * Vendas da sessão (passo 909): a porta invertida que o caixa tem para o módulo {@code sales}.
   */
  @Inject SessionSalesLookup sessionSalesLookup;

  public CashSessionSummaryView execute(UUID sessionId) {
    CashSessionSummary session = requireSession(sessionId);
    Map<CashMovementType, BigDecimal> totals = zeroFilled(cashSessionStore.sumByType(session.id()));
    return new CashSessionSummaryView(
        session.id(),
        session.status(),
        session.openingAmount(),
        CashSessionAmounts.expectedAmount(session.openingAmount(), totals),
        session.countedAmount(),
        session.differenceAmount(),
        totals,
        sessionSalesLookup.sumApprovedPaymentsByMethod(session.id()));
  }

  private CashSessionSummary requireSession(UUID sessionId) {
    return cashSessionStore
        .findById(sessionId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CASH_SESSION_NOT_FOUND,
                    "sessão de caixa %s não encontrada".formatted(sessionId)));
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
