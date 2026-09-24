package com.minimarket.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Item da listagem de estoque (§9.3, passo 704): o produto com o saldo da loja e o flag de estoque
 * baixo. Só o que o contrato expõe — nem {@code storeId} nem a entidade JPA do saldo; {@code
 * quantity} é zero para produto sem movimento e {@code minQuantity} é nulo quando o produto não tem
 * mínimo configurado.
 */
public record StockItemResponse(
    UUID productId,
    String name,
    String barcode,
    String unit,
    BigDecimal quantity,
    BigDecimal minQuantity,
    boolean lowStock) {}
