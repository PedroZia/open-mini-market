package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ajuste manual de estoque que o {@link AdjustStockUseCase} deve aplicar (passo 705): o produto, o
 * delta assinado na convenção do ledger (positivo na entrada, negativo na saída) e o motivo
 * obrigatório — ajuste sem justificativa não é operação auditável (BR-10/BR-13). O {@code
 * performedByUserId} é o ator da requisição: a coluna {@code created_by_user_id} do ledger tem FK
 * para {@code users} (§5.3) e o evento de auditoria o registra.
 */
public record AdjustStockCommand(
    UUID productId, BigDecimal quantityDelta, String reason, UUID performedByUserId) {}
