package com.minimarket.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Produto como a API devolve (§9.3): só os campos do contrato — {@code storeId} e {@code deletedAt}
 * são detalhe do banco e não aparecem (o {@code active} já diz se o produto está vivo). A projeção
 * {@code ProductSummary} de {@code application} é mapeada para cá; entidade JPA nunca vai a JSON.
 * Reutilizado pelo detalhe (passo 408) e pela edição (passo 410).
 */
public record ProductResponse(
    UUID id,
    String name,
    String barcode,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity,
    boolean active,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
