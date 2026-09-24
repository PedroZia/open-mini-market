package com.minimarket.cash.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/cash-registers/{id}/close} (§9.3, passo 612): o valor contado na
 * conferência e as observações do fechamento. Só isso vem do cliente — quem fecha, o instante, o
 * esperado e a diferença são do servidor.
 *
 * <p>A forma é validada aqui ({@code countedAmount} ausente → {@code @NotNull}; negativo →
 * {@code @DecimalMin}); o caso de uso valida de novo, como backstop de quem chama a aplicação fora
 * da API (passo 611). As observações são opcionais — o operador nem sempre tem o que anotar.
 */
public record CloseCashSessionRequest(
    @NotNull(message = "é obrigatório")
        @DecimalMin(value = "0.00", message = "não pode ser negativo")
        BigDecimal countedAmount,
    String notes) {}
