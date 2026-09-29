package com.minimarket.reports.application;

import java.util.List;

/** Página do relatório de estoque baixo já validada e limitada pelo {@link ListLowStockUseCase}. */
public record LowStockPage(
    List<LowStockItem> items, int page, int size, long totalItems, int totalPages) {}
