package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dados que a porta {@link ProductStore} precisa para inserir um produto novo. O {@code storeId}
 * chega já resolvido pelo caso de uso (loja única do MVP, §5.3) e o produto nasce ativo, como no
 * default da tabela. {@code barcode} e {@code minQuantity} são opcionais (nulos).
 */
public record NewProduct(
    UUID storeId,
    String name,
    String barcode,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity) {}
