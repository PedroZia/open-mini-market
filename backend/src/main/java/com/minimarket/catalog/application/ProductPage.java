package com.minimarket.catalog.application;

import java.util.List;

/** Página de produtos já validada e limitada pelo {@link ListProductsUseCase}. */
public record ProductPage(
    List<ProductSummary> items, int page, int size, long totalItems, int totalPages) {}
