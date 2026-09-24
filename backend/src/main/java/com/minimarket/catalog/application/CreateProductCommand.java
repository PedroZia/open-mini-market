package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link CreateProductUseCase}: o barcode chega cru (é o caso de uso que normaliza) e a
 * categoria é opcional — produto sem categoria é permitido (§5.3), como {@code minQuantity} nulo é
 * produto sem ponto de reposição. O {@code internalCode} (passo 1104d) também chega cru: o caso de
 * uso o normaliza e o completa com zeros até o tamanho da etiqueta da loja.
 */
public record CreateProductCommand(
    String name,
    String barcode,
    String internalCode,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity) {}
