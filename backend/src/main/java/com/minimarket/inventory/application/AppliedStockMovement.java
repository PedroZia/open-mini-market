package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado da aplicação de um movimento pelo {@link StockService} (passo 703): o id da linha do
 * ledger e o saldo antes/depois que o serviço calculou sob o lock da linha de saldo. O {@code
 * balanceAfter} é o mesmo valor gravado em {@code stock_movements.balance_after} e em {@code
 * product_stocks.quantity}.
 */
public record AppliedStockMovement(
    UUID movementId, UUID productId, BigDecimal balanceBefore, BigDecimal balanceAfter) {}
