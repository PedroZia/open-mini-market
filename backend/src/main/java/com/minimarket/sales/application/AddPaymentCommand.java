package com.minimarket.sales.application;

import com.minimarket.sales.domain.PaymentMethod;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link AddPaymentUseCase}: a venda que está sendo paga, a forma e o valor, o valor
 * entregue quando for dinheiro e o operador que registrou. Nada de valor calculado pelo cliente
 * (BR-12): troco e total pago saem do servidor a partir do que chega aqui.
 *
 * <p>O {@code tenderedAmount} é o que o cliente entregou em espécie, só no dinheiro (BR-05) — nas
 * demais formas ele é recusado, porque não existe troco em cartão, Pix ou voucher. O valor do
 * pagamento nunca supera o restante da venda: dinheiro a mais é troco, não valor pago.
 *
 * <p>{@code cashRegisterId} e {@code createdByUserId} são o caixa e o operador da sessão
 * autenticada, montados pela API a partir do {@code OperationContext} — nunca do corpo da
 * requisição (BR-11): a {@link SaleAccessGuard} só deixa pagar a venda do caixa da sessão e o autor
 * do pagamento é quem registrou, não quem o cliente disser.
 *
 * @param saleId venda que recebe o pagamento
 * @param cashRegisterId caixa da sessão autenticada; nulo é sessão sem vínculo de caixa e a guarda
 *     recusa com 403
 * @param method forma de pagamento; obrigatória
 * @param amount valor do pagamento, maior que zero e até o restante da venda
 * @param tenderedAmount valor entregue pelo cliente; obrigatório e maior ou igual ao pagamento em
 *     dinheiro, ausente nas demais formas
 * @param createdByUserId operador que registrou o pagamento, obrigatório
 */
public record AddPaymentCommand(
    UUID saleId,
    UUID cashRegisterId,
    PaymentMethod method,
    BigDecimal amount,
    BigDecimal tenderedAmount,
    UUID createdByUserId) {}
