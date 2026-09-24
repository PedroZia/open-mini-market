package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link RemoveSaleItemUseCase} (passo 809): a venda e o produto do item que sai. O item
 * é identificado pelo {@code productId} — a identidade do item no agregado é o produto (decisão do
 * 802). Remover não recebe quantidade nem motivo: zerar a quantidade é remover, e o rastro fica no
 * evento de auditoria.
 *
 * @param saleId venda que contém o item
 * @param cashRegisterId caixa da sessão autenticada (BR-11); a {@link SaleAccessGuard} confere a
 *     posse da venda contra ele
 * @param productId produto do item removido
 */
public record RemoveSaleItemCommand(UUID saleId, UUID cashRegisterId, UUID productId) {}
