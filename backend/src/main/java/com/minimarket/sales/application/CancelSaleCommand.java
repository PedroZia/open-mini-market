package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Comando do cancelamento da venda aberta (passo 813): o alvo, o caixa da sessão autenticada, o
 * motivo digitado pelo operador e o autor do cancelamento. O caixa e o autor saem do {@code
 * OperationContext} na API — nunca do corpo da requisição (BR-11) — e o instante do cancelamento é
 * do {@code Clock} do caso de uso, não do comando.
 */
public record CancelSaleCommand(
    UUID saleId, UUID cashRegisterId, String reason, UUID cancelledByUserId) {}
