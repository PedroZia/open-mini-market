package com.minimarket.cash.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/cash-registers/{id}/withdrawals} e {@code POST
 * /api/v1/cash-registers/{id}/supplies} (§9.3, passo 610): o valor movimentado e o motivo
 * obrigatório (BR-10). Só isso vem do cliente — quem opera, a sessão, a loja e o instante são do
 * servidor.
 *
 * <p>A forma é validada aqui ({@code amount} ausente → {@code @NotNull}; zero ou negativo →
 * {@code @Positive}; motivo vazio → {@code @NotBlank}); o caso de uso valida de novo, como backstop
 * de quem chama a aplicação fora da API (passos 609 e 610). O valor é sempre positivo no corpo: o
 * sinal do tipo é do ledger, não do cliente.
 */
public record CashMovementRequest(
    @NotNull(message = "é obrigatório") @Positive(message = "deve ser maior que zero")
        BigDecimal amount,
    @NotBlank(message = "não pode ser vazio") String reason) {}
