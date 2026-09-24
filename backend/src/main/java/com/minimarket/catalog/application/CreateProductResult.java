package com.minimarket.catalog.application;

import java.util.UUID;

/**
 * Saída do {@link CreateProductUseCase}: o id gerado pelo adaptador (UUIDv7) — nunca a entidade JPA
 * nem os campos que o banco completou.
 */
public record CreateProductResult(UUID id) {}
