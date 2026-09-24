package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado do ajuste manual (passo 705): o id da linha do ledger e o saldo antes/depois que o
 * {@link StockService} calculou sob o lock, com o delta aplicado. Os saldos são os mesmos gravados
 * em {@code stock_movements.balance_after} e em {@code product_stocks.quantity} — o cliente só
 * exibe, nunca recalcula (BR-12).
 */
public record AppliedStockAdjustment(
    UUID movementId,
    UUID productId,
    BigDecimal quantityDelta,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter) {}
