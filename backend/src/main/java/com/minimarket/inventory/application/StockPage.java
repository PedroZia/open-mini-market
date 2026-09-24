package com.minimarket.inventory.application;

import java.util.List;

/** Página da consulta de estoque já validada e limitada pelo {@link ListStockUseCase}. */
public record StockPage(
    List<StockItemSummary> items, int page, int size, long totalItems, int totalPages) {}
