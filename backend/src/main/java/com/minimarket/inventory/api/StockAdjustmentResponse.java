package com.minimarket.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado do ajuste manual (§9.3, passo 705): o movimento {@code ADJUSTMENT} gravado no ledger e
 * o saldo antes/depois calculado pelo servidor sob o lock — o cliente só exibe, nunca recalcula
 * (BR-12). Nem entidade JPA nem {@code storeId} vazam no JSON.
 */
public record StockAdjustmentResponse(
    UUID movementId,
    UUID productId,
    BigDecimal quantityDelta,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter) {}
