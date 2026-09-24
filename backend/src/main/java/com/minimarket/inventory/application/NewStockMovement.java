package com.minimarket.inventory.application;

import com.minimarket.inventory.domain.StockMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Movimento novo do ledger de estoque para a porta {@link StockMovementStore}. {@code
 * quantityDelta} é assinado, na convenção da tabela: positivo nas entradas e negativo nas saídas;
 * {@code balanceAfter} é o saldo resultante da aplicação (passo 703 calcula sob o lock). {@code
 * unitCost} só existe na entrada com custo informado e {@code referenceType}/{@code referenceId}
 * descrevem a origem (venda, ajuste, recebimento) — opcionais. {@code createdAt} é o instante do
 * movimento, do relógio do caso de uso, nunca o {@code now()} do banco.
 */
public record NewStockMovement(
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
