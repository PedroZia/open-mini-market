package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Comando da conclusão da venda (passo 906): o alvo, o caixa da sessão autenticada e o operador que
 * concluiu. O caixa e o ator saem do {@code OperationContext} na API — nunca do corpo da requisição
 * (BR-11) — e o instante da conclusão é do {@code Clock} do caso de uso, não do comando.
 *
 * @param saleId venda que está sendo concluída
 * @param cashRegisterId caixa da sessão autenticada; nulo é sessão sem vínculo de caixa e a guarda
 *     recusa com 403
 * @param completedByUserId operador que concluiu a venda, autor do movimento de estoque e do de
 *     caixa; obrigatório
 */
public record CompleteSaleCommand(UUID saleId, UUID cashRegisterId, UUID completedByUserId) {}
