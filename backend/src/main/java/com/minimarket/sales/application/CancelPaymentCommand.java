package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link CancelPaymentUseCase}: a venda, o caixa da sessão autenticada e o pagamento a
 * cancelar. Nada de valor calculado pelo cliente (BR-12): o pago da venda é recalculado pelo
 * servidor a partir dos pagamentos que restarem aprovados.
 *
 * <p>{@code cashRegisterId} é o caixa da sessão autenticada, montado pela API a partir do {@code
 * OperationContext} — nunca do corpo da requisição (BR-11): a {@link SaleAccessGuard} só deixa
 * cancelar pagamento da venda do caixa da sessão. O autor e o instante do cancelamento não vêm
 * daqui: são a sessão e o relógio do servidor.
 *
 * @param saleId venda dona do pagamento
 * @param cashRegisterId caixa da sessão autenticada; nulo é sessão sem vínculo de caixa e a guarda
 *     recusa com 403
 * @param paymentId pagamento a cancelar; fora da venda é 404 {@code PAYMENT_NOT_FOUND}
 */
public record CancelPaymentCommand(UUID saleId, UUID cashRegisterId, UUID paymentId) {}
