package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado da entrada de mercadoria (passo 706): o id da linha do ledger e o saldo antes/depois
 * que o {@link StockService} calculou sob o lock, com a quantidade e o custo unitário normalizados
 * pelo caso de uso. Os saldos são os mesmos gravados em {@code stock_movements.balance_after} e em
 * {@code product_stocks.quantity} — o cliente só exibe, nunca recalcula (BR-12).
 */
public record AppliedStockReceipt(
    UUID movementId,
    UUID productId,
    BigDecimal quantity,
    BigDecimal unitCost,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter) {}
