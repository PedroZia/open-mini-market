package com.minimarket.cash.api;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sessão atual do caixa como a API devolve (§9.3, passo 608): só os campos do contrato, dinheiro
 * como número e os totais por tipo de movimento com os quatro tipos sempre presentes (zero quando
 * não houve movimento) — {@code storeId}, {@code version} e os campos de fechamento são detalhe do
 * banco. O {@code expectedAmount} é calculado no servidor. A projeção {@code
 * CurrentCashSessionView} de {@code application} é mapeada para cá; entidade JPA nunca vai a JSON.
 */
public record CurrentCashSessionResponse(
    UUID sessionId,
    UUID cashRegisterId,
    CashSessionStatus status,
    Instant openedAt,
    UUID openedByUserId,
    BigDecimal openingAmount,
    BigDecimal expectedAmount,
    Map<CashMovementType, BigDecimal> totalsByType) {}
