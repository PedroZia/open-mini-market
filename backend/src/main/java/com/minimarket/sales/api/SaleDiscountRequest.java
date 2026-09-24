package com.minimarket.sales.api;

import com.minimarket.sales.domain.DiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Corpo de {@code PUT /api/v1/sales/{id}/discount} (§9.3, passo 811b): o tipo, o valor informado e
 * o motivo do desconto. O valor chega como o operador o digitou — em reais quando {@code VALUE}, em
 * percentual quando {@code PERCENT} — e quem calcula quanto isso desconta do subtotal e o total da
 * venda é o servidor (BR-03, BR-12): o cliente nunca manda o valor final do desconto.
 *
 * <p>A forma é validada aqui ({@code type} ausente → {@code @NotNull}; {@code value} ausente ou não
 * positivo → {@code @NotNull}/{@code @Positive}; {@code reason} ausente ou em branco →
 * {@code @NotBlank} → 400 {@code VALIDATION_ERROR}); o caso de uso do 810 repete as três checagens
 * como backstop e é ele quem confere o limite da loja (422 {@code DISCOUNT_LIMIT_EXCEEDED}).
 *
 * <p>A permissão {@code sale.discount.apply} exigida pela rota (BR-04) não vem no corpo: é da
 * sessão autenticada, checada pelo interceptor do {@code RequirePermission}.
 */
public record SaleDiscountRequest(
    @NotNull(message = "é obrigatório") DiscountType type,
    @NotNull(message = "é obrigatório") @Positive(message = "deve ser maior que zero")
        BigDecimal value,
    @NotBlank(message = "é obrigatório") String reason) {}
