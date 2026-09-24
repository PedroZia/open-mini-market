package com.minimarket.catalog.application;

import java.util.UUID;

/**
 * Projeção de categoria para a porta {@link CategoryStore}: sem entidade JPA atravessando para
 * {@code application}. {@code active} falso é categoria desativada — a linha continua na tabela,
 * porque categorias não têm {@code deleted_at}.
 */
public record CategorySummary(
    UUID id, UUID storeId, String name, UUID parentId, boolean active, int sortOrder) {}
