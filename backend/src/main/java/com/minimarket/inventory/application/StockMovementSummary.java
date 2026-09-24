package com.minimarket.inventory.application;

import com.minimarket.inventory.domain.StockMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projeção do movimento do ledger de estoque para a porta {@link StockMovementStore}: sem entidade
 * JPA atravessando para {@code application}. {@code quantityDelta} é assinado e {@code
 * balanceAfter} é o saldo resultante do movimento; {@code unitCost}, {@code referenceType}, {@code
 * referenceId} e {@code reason} são nulos quando o movimento não os tem.
 */
public record StockMovementSummary(
    UUID id,
    UUID storeId,
    UUID productId,
    StockMovementType type,
    BigDecimal quantityDelta,
    BigDecimal balanceAfter,
    BigDecimal unitCost,
    String referenceType,
    UUID referenceId,
    String reason,
    UUID createdByUserId,
    Instant createdAt) {}
