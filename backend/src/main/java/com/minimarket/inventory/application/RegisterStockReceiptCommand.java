package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada de mercadoria que o {@link RegisterStockReceiptUseCase} deve registrar (passo 706): o
 * produto, a quantidade que chegou e o custo unitário — opcional: sem ele a entrada só repõe o
 * saldo, sem mexer no {@code cost_price}. O motivo é opcional e vai para o movimento do ledger e
 * para o evento de auditoria. O {@code performedByUserId} é o ator da requisição: a coluna {@code
 * created_by_user_id} do ledger tem FK para {@code users} (§5.3) e o evento o registra.
 */
public record RegisterStockReceiptCommand(
    UUID productId,
    BigDecimal quantity,
    BigDecimal unitCost,
    String reason,
    UUID performedByUserId) {}
