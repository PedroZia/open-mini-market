package com.minimarket.reports.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Produto com estoque baixo na projeção do relatório (passo 1212a): os campos que o gestor precisa
 * para repor, sem o flag derivado nem a loja — a regra de estoque baixo continua só no inventário e
 * aqui o produto já chega filtrado por ela.
 */
public record LowStockItem(
    UUID productId,
    String name,
    String barcode,
    String unit,
    BigDecimal quantity,
    BigDecimal minQuantity) {}
