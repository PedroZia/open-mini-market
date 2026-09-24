package com.minimarket.inventory.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/stock/{productId}/receipts} (§9.3, passo 706): a quantidade que
 * chegou, o custo unitário e o motivo. O custo é opcional — nulo repõe o saldo sem mexer no {@code
 * cost_price} — e o motivo também: o recebimento é rotina, não exceção. Só isso vem do cliente:
 * quem recebe, a loja, o saldo antes/depois e o instante são do servidor.
 *
 * <p>A forma é validada aqui ({@code quantity} ausente → {@code @NotNull}); o caso de uso valida de
 * novo, como backstop de quem chama a aplicação fora da API, e é ele quem recusa a quantidade não
 * positiva e o custo negativo — e quem normaliza as escalas do projeto (3 para quantidade, 2 para
 * dinheiro).
 */
public record StockReceiptRequest(
    @NotNull(message = "é obrigatório") BigDecimal quantity, BigDecimal unitCost, String reason) {}
