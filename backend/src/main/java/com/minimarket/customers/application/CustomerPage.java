package com.minimarket.customers.application;

import java.util.List;

/** Página de clientes já validada e limitada pelo {@link ListCustomersUseCase}. */
public record CustomerPage(
    List<CustomerSummary> items, int page, int size, long totalItems, int totalPages) {}
