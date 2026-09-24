package com.minimarket.catalog.application;

import java.util.UUID;

/**
 * Saída do {@link CreateProductUseCase}: o id gerado pelo adaptador (UUIDv7) e o produto como o
 * banco o guardou, para a resposta 201 refletir os valores normalizados (barcode sem espaços, preço
 * em escala 2, {@code active = true} e timestamps) — nunca a entidade JPA.
 */
public record CreateProductResult(UUID id, ProductSummary product) {}
