package com.minimarket.sales.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /api/v1/sales/{id}/cancel} (§9.3, passo 813): o motivo da desistência. É o
 * único campo — o autor e o instante saem da sessão autenticada e do relógio do servidor, nunca do
 * cliente (BR-11, BR-12).
 *
 * <p>A forma é validada aqui ({@code reason} ausente ou em branco → {@code @NotBlank} → 400 {@code
 * VALIDATION_ERROR}); o caso de uso repete a checagem como backstop, porque cancelamento sem motivo
 * não é operação auditável (§7.2). A permissão {@code sale.cancel} exigida pela rota não vem no
 * corpo: é da sessão autenticada, checada pelo interceptor do {@code RequirePermission} e de novo
 * pelo caso de uso.
 */
public record SaleCancelRequest(@NotBlank(message = "é obrigatório") String reason) {}
