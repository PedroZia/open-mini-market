package com.minimarket.sales.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Corpo de {@code PATCH /api/v1/sales/{id}/items/{itemId}} (§9.3, passo 809b): a quantidade nova do
 * item, <em>absoluta</em> — quanto o item passa a ter, nunca o delta. Quem calcula o total da linha
 * e os totais da venda é o servidor (BR-02, BR-12).
 *
 * <p>A forma é validada aqui ({@code quantity} ausente → {@code @NotNull}; zero ou negativa →
 * {@code @Positive} → 400 {@code VALIDATION_ERROR}); zerar a quantidade é remover o item, e o
 * caminho para isso é o {@code DELETE} da mesma rota.
 */
public record SaleItemQuantityRequest(
    @NotNull(message = "é obrigatório") @Positive(message = "deve ser maior que zero")
        BigDecimal quantity) {}
