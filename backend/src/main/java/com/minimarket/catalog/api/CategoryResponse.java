package com.minimarket.catalog.api;

import java.util.UUID;

/**
 * Categoria como a API devolve (§9.3): só os campos do contrato — {@code storeId}, timestamps e
 * {@code version} são do banco e não aparecem. A projeção {@code CategorySummary} de {@code
 * application} é mapeada para cá; entidade JPA nunca vai a JSON.
 */
public record CategoryResponse(
    UUID id, String name, UUID parentId, boolean active, int sortOrder) {}
