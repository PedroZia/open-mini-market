package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sessão aberta de um caixa com o que a TUI mostra (passo 608): a projeção da sessão, os totais por
 * tipo de movimento — zero-preenchidos com os quatro tipos, porque o cliente desenha todos — e o
 * saldo esperado calculado pela regra única do {@code CashSessionAmounts}. A API mapeia para o seu
 * próprio record; entidade JPA nunca chega a {@code application}.
 */
public record CurrentCashSessionView(
    UUID sessionId,
    UUID cashRegisterId,
    CashSessionStatus status,
    Instant openedAt,
    UUID openedByUserId,
    BigDecimal openingAmount,
    BigDecimal expectedAmount,
    Map<CashMovementType, BigDecimal> totalsByType) {}
