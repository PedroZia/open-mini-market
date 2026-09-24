package com.minimarket.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/stock/{productId}/adjustments} (§9.3, passo 705): o delta assinado
 * do ajuste — positivo aumenta, negativo reduz — e o motivo obrigatório (BR-13). Só isso vem do
 * cliente: quem ajusta, a loja, o saldo antes/depois e o instante são do servidor.
 *
 * <p>A forma é validada aqui ({@code quantityDelta} ausente → {@code @NotNull}; motivo vazio →
 * {@code @NotBlank}); o caso de uso valida de novo, como backstop de quem chama a aplicação fora da
 * API, e é ele quem recusa o delta zero — ajuste sem mudança não tem efeito nem rastro útil.
 */
public record StockAdjustmentRequest(
    @NotNull(message = "é obrigatório") BigDecimal quantityDelta,
    @NotBlank(message = "não pode ser vazio") String reason) {}
