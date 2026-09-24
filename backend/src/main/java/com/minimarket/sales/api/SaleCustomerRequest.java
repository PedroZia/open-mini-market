package com.minimarket.sales.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Corpo de {@code PUT /api/v1/sales/{id}/customer} (§9.3, passo 811b): o cliente a vincular à venda
 * aberta. A venda guarda um cliente por vez — vincular outro substitui o anterior — e quem confere
 * se o cliente existe e está ativo é o caso de uso (404 {@code CUSTOMER_NOT_FOUND} / 422 {@code
 * CUSTOMER_INACTIVE}).
 *
 * <p>A forma é validada aqui ({@code customerId} ausente → {@code @NotNull} → 400 {@code
 * VALIDATION_ERROR}); a permissão {@code sale.create} exigida pela rota não vem no corpo: é da
 * sessão autenticada.
 */
public record SaleCustomerRequest(@NotNull(message = "é obrigatório") UUID customerId) {}
