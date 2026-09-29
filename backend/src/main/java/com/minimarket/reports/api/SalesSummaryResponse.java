package com.minimarket.reports.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Resumo de vendas do período (§9.3, passo 1212a): os totais das vendas concluídas, o ticket médio
 * e os grupos da dimensão pedida. {@code from}/{@code to} voltam como vieram na consulta (ISO-8601)
 * e {@code groupBy} é o mesmo nome aceito na query ({@code day|operator|paymentMethod}), para o
 * cliente ecoar o que pediu sem traduzir. O período é {@code from} inclusivo e {@code to} exclusivo
 * sobre o {@code completed_at}; {@code ticketAverage} é zero quando o período não tem venda.
 */
public record SalesSummaryResponse(
    Instant from,
    Instant to,
    String groupBy,
    long salesCount,
    BigDecimal total,
    BigDecimal ticketAverage,
    List<SalesSummaryGroupResponse> groups) {}
