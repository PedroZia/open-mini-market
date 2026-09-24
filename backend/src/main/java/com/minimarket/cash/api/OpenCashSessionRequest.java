package com.minimarket.cash.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/cash-registers/{id}/open} (§9.3, passo 607): o fundo de troco que o
 * operador põe na gaveta. Só isso vem do cliente — quem abre, a loja e o instante são do servidor.
 *
 * <p>A forma é validada aqui ({@code openingAmount} ausente → {@code @NotNull}; negativo →
 * {@code @DecimalMin}); o caso de uso valida de novo, como backstop de quem chama a aplicação fora
 * da API (passo 606).
 */
public record OpenCashSessionRequest(
    @NotNull(message = "é obrigatório")
        @DecimalMin(value = "0.00", message = "não pode ser negativo")
        BigDecimal openingAmount) {}
