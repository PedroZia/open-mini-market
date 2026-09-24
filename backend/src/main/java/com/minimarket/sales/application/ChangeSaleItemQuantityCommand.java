package com.minimarket.sales.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link ChangeSaleItemQuantityUseCase} (passo 809): a venda, o produto do item e a
 * quantidade nova. O item é identificado pelo {@code productId} — a identidade do item no agregado
 * é o produto (decisão do 802) — e a quantidade é a <em>absoluta</em>: o cliente manda quanto o
 * item passa a ter, nunca o delta, e quem calcula total da linha e totais da venda é o servidor
 * (BR-02, BR-12).
 *
 * @param saleId venda que contém o item
 * @param cashRegisterId caixa da sessão autenticada (BR-11); a {@link SaleAccessGuard} confere a
 *     posse da venda contra ele
 * @param productId produto do item alterado
 * @param quantity quantidade nova, maior que zero
 */
public record ChangeSaleItemQuantityCommand(
    UUID saleId, UUID cashRegisterId, UUID productId, BigDecimal quantity) {}
