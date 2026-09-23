package com.minimarket.shared.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Loja atual do MVP: o código é resolvido por configuração ({@code minimarket.store.default-code}),
 * nunca por parâmetro do cliente (§5.4 do plano). Carrega só os parâmetros de negócio que os
 * clientes precisam receber (§9.3); seletor de loja não existe no MVP. O {@code id} entrou no passo
 * 204a porque a sessão guarda {@code store_id} (§5.3) e o {@code name} no 207 porque o {@code
 * /auth/me} identifica a loja da sessão para o cliente.
 */
public record Store(
    UUID id, String code, String name, boolean allowNegativeStock, BigDecimal maxDiscountPercent) {}
