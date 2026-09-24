package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link LinkCustomerUseCase} (passo 811): a venda, o cliente a vincular e o caixa da
 * sessão autenticada. A venda guarda um cliente por vez — vincular outro substitui o anterior — e
 * quem confere se o cliente existe e está ativo é o caso de uso (404 {@code CUSTOMER_NOT_FOUND} /
 * 422 {@code CUSTOMER_INACTIVE}).
 *
 * <p>{@code cashRegisterId} é o caixa da sessão autenticada, montado pela API do {@code
 * OperationContext} — nunca do corpo: a {@link SaleAccessGuard} só deixa a operação seguir na venda
 * do caixa da sessão (BR-11, §9.4).
 *
 * @param saleId venda que recebe o cliente
 * @param cashRegisterId caixa da sessão autenticada; nulo é sessão sem vínculo de caixa e a guarda
 *     recusa com 403
 * @param customerId cliente a vincular; nulo é comando inválido (400)
 */
public record LinkCustomerCommand(UUID saleId, UUID cashRegisterId, UUID customerId) {}
