package com.minimarket.reports.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Produto com estoque baixo no contrato (§9.3, passo 1212a): o mínimo configurado e o saldo que o
 * gestor usa para repor. Nem o flag derivado nem a loja entram — o filtro já é a regra do
 * inventário.
 */
public record LowStockItemResponse(
    UUID productId,
    String name,
    String barcode,
    String unit,
    BigDecimal quantity,
    BigDecimal minQuantity) {}
