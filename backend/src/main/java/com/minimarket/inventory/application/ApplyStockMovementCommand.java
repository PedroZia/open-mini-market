package com.minimarket.inventory.application;

import com.minimarket.inventory.domain.StockMovementType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Movimento de estoque que o {@link StockService} deve aplicar (passo 703): o {@code quantityDelta}
 * assinado na convenção do ledger — positivo na entrada, negativo na saída — e o rastro de quem o
 * originou. {@code unitCost} só existe na entrada com custo informado; {@code referenceType}/{@code
 * referenceId} descrevem o documento de origem (venda, ajuste, recebimento) e são opcionais, como
 * no {@link NewStockMovement}. O {@code createdByUserId} é obrigatório: a coluna do ledger tem FK
 * para {@code users} (§5.3).
 */
public record ApplyStockMovementCommand(
    UUID productId,
    StockMovementType type,
    BigDecimal quantityDelta,
    BigDecimal unitCost,
    String referenceType,
    UUID referenceId,
    String reason,
    UUID createdByUserId) {}
