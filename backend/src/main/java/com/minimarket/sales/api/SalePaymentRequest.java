package com.minimarket.sales.api;

import com.minimarket.sales.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/sales/{id}/payments} (§9.3, passo 905): a forma e o valor do
 * pagamento, mais o valor entregue quando for dinheiro. Nada de valor calculado pelo cliente
 * (BR-12): o troco e o total pago são do servidor, a partir do que chega aqui.
 *
 * <p>{@code tenderedAmount} é o que o cliente entregou em espécie, só no dinheiro (BR-05) — nas
 * demais formas o caso de uso o recusa com 422 {@code INVALID_TENDERED_AMOUNT}, porque não existe
 * troco em cartão, Pix ou voucher. O valor do pagamento nunca supera o restante da venda: dinheiro
 * a mais é troco, não valor pago.
 *
 * <p>A forma é validada aqui ({@code method} e {@code amount} ausentes → {@code @NotNull}; {@code
 * amount} abaixo de um centavo → {@code @DecimalMin} → 400 {@code VALIDATION_ERROR}); o caso de uso
 * do 904 repete as checagens como backstop e é ele quem confere o restante e o valor entregue. A
 * permissão {@code payment.add} exigida pela rota não vem no corpo: é da sessão autenticada,
 * checada pelo interceptor do {@code RequirePermission} e de novo pelo caso de uso.
 *
 * @param method forma de pagamento; obrigatória
 * @param amount valor do pagamento, de um centavo até o restante da venda
 * @param tenderedAmount valor entregue pelo cliente; obrigatório e maior ou igual ao pagamento em
 *     dinheiro, ausente nas demais formas
 */
public record SalePaymentRequest(
    @NotNull(message = "é obrigatório") PaymentMethod method,
    @NotNull(message = "é obrigatório")
        @DecimalMin(value = "0.01", message = "deve ser maior que zero")
        BigDecimal amount,
    BigDecimal tenderedAmount) {}
