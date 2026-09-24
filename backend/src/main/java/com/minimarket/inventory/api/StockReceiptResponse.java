package com.minimarket.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado da entrada de mercadoria (§9.3, passo 706): o movimento {@code PURCHASE_IN} gravado no
 * ledger e o saldo antes/depois calculado pelo servidor sob o lock — o cliente só exibe, nunca
 * recalcula (BR-12). Nem entidade JPA nem {@code storeId} vazam no JSON.
 */
public record StockReceiptResponse(
    UUID movementId,
    UUID productId,
    BigDecimal quantity,
    BigDecimal unitCost,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter) {}
