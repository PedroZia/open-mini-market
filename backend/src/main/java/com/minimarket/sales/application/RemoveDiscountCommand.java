package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link RemoveDiscountUseCase} (passo 810): a venda e o caixa da sessão autenticada.
 * Não há motivo nem valor: remover zera o desconto que está na venda e devolve o total ao subtotal
 * (BR-03); o rastro de quem tirou e do que saiu fica no evento de auditoria.
 *
 * @param saleId venda que perde o desconto
 * @param cashRegisterId caixa da sessão autenticada (BR-11); a {@link SaleAccessGuard} confere a
 *     posse da venda contra ele
 */
public record RemoveDiscountCommand(UUID saleId, UUID cashRegisterId) {}
