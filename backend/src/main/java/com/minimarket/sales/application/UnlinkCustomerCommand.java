package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link UnlinkCustomerUseCase} (passo 811): a venda e o caixa da sessão autenticada.
 * Não há cliente no comando: desvincular tira o que estiver vinculado e devolve a venda ao estado
 * anônimo; o rastro de qual cliente saiu fica no evento de auditoria.
 *
 * @param saleId venda que perde o cliente
 * @param cashRegisterId caixa da sessão autenticada (BR-11); a {@link SaleAccessGuard} confere a
 *     posse da venda contra ele
 */
public record UnlinkCustomerCommand(UUID saleId, UUID cashRegisterId) {}
