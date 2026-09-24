package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projeção de produto para a porta {@link ProductStore}: sem entidade JPA atravessando para {@code
 * application}. {@code deletedAt} preenchido é produto desativado; {@code active} falso com {@code
 * deletedAt} nulo é a janela entre desativar e reativar no caso de uso (passo 412). {@code version}
 * é o lock otimista que a edição (passo 410) exige no {@code If-Match}.
 */
public record ProductSummary(
    UUID id,
    UUID storeId,
    String barcode,
    String name,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt,
    long version) {}
