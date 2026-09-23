package com.minimarket.shared.domain;

import java.math.BigDecimal;

/**
 * Loja atual do MVP: o código é resolvido por configuração ({@code minimarket.store.default-code}),
 * nunca por parâmetro do cliente (§5.4 do plano). Carrega só os parâmetros de negócio que os
 * clientes precisam receber (§9.3); seletor de loja não existe no MVP.
 */
public record Store(String code, boolean allowNegativeStock, BigDecimal maxDiscountPercent) {}
