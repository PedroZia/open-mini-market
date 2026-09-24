package com.minimarket.sales.application;

import java.util.List;

/** Página do histórico de vendas já validada e limitada pelo {@link ListSalesUseCase}. */
public record SalePage(
    List<SaleSummary> items, int page, int size, long totalItems, int totalPages) {}
