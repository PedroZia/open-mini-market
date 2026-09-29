package com.minimarket.reports.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Resumo de vendas do período já validado pelo {@link ListSalesSummaryUseCase} (passo 1212a): o
 * período ecoado como veio, a dimensão pedida, os totais das vendas concluídas e os grupos da
 * dimensão. A API mapeia para o DTO — nada de record de aplicação atravessando para JSON sem
 * mapper.
 */
public record SalesSummaryView(
    Instant from,
    Instant to,
    SalesSummaryGroupBy groupBy,
    long salesCount,
    BigDecimal total,
    BigDecimal ticketAverage,
    List<SalesSummaryGroup> groups) {}
