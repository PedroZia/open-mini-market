package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link CreateProductUseCase}: o barcode chega cru (é o caso de uso que normaliza) e a
 * categoria é opcional — produto sem categoria é permitido (§5.3), como {@code minQuantity} nulo é
 * produto sem ponto de reposição.
 */
public record CreateProductCommand(
    String name,
    String barcode,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity) {}
