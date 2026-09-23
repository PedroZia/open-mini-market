package com.minimarket.shared.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Corpo de {@code GET /api/v1/meta} (§9.3 do plano): parâmetros de negócio que TUI e Web
 * compartilham. {@code serverTime} é o instante UTC do servidor em ISO-8601.
 */
public record MetaResponse(
    String apiVersion,
    String storeCode,
    boolean allowNegativeStock,
    BigDecimal maxDiscountPercent,
    Instant serverTime) {}
