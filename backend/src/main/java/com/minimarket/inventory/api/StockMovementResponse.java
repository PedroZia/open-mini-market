package com.minimarket.inventory.api;

import com.minimarket.inventory.domain.StockMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Movimento do ledger no detalhe de estoque (§9.3, passo 704): o tipo, o delta assinado e o saldo
 * resultante, com o custo unitário e a referência do documento que originou o movimento quando
 * existem. O {@code createdByUserId} é o operador que aplicou o movimento.
 */
public record StockMovementResponse(
    UUID id,
    StockMovementType type,
    BigDecimal quantityDelta,
    BigDecimal balanceAfter,
    BigDecimal unitCost,
    String referenceType,
    UUID referenceId,
    String reason,
    UUID createdByUserId,
    Instant createdAt) {}
