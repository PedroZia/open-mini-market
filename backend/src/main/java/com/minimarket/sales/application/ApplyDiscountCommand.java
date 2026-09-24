package com.minimarket.sales.application;

import com.minimarket.sales.domain.DiscountType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link ApplyDiscountUseCase} (passo 810): a venda alvo, o tipo e o valor do desconto e
 * o motivo. O valor chega como o operador o digitou — em reais quando {@code VALUE}, em percentual
 * quando {@code PERCENT} — e quem calcula quanto isso desconta do subtotal é o servidor (BR-03,
 * BR-12): o cliente nunca manda o valor final do desconto.
 *
 * <p>O motivo é obrigatório (BR-04): sem ele o caso de uso recusa com 400 {@code VALIDATION_ERROR}
 * e a auditoria não teria o que registrar. A permissão {@code sale.discount.apply} exigida pelo
 * caso de uso não vem no comando: é da sessão autenticada, checada pelo {@code
 * AuthorizationService} (passo 305).
 *
 * <p>{@code cashRegisterId} é o caixa da sessão autenticada, montado pela API (passo 811) do {@code
 * OperationContext} — nunca do corpo: a {@link SaleAccessGuard} só deixa a operação seguir na venda
 * do caixa da sessão (BR-11, §9.4).
 *
 * @param saleId venda que recebe o desconto
 * @param cashRegisterId caixa da sessão autenticada; nulo é sessão sem vínculo de caixa e a guarda
 *     recusa com 403
 * @param type tipo do desconto; nulo é comando inválido (400)
 * @param value valor informado, maior que zero; nulo ou não positivo é comando inválido (400)
 * @param reason motivo do desconto; nulo ou em branco é comando inválido (400)
 */
public record ApplyDiscountCommand(
    UUID saleId, UUID cashRegisterId, DiscountType type, BigDecimal value, String reason) {}
