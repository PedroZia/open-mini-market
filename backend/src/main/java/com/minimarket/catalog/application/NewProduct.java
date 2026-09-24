package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dados que a porta {@link ProductStore} precisa para inserir um produto novo. O {@code storeId}
 * chega já resolvido pelo caso de uso (loja única do MVP, §5.3) e o produto nasce ativo, como no
 * default da tabela. {@code barcode}, {@code internalCode} e {@code minQuantity} são opcionais
 * (nulos); o código interno é o PLU que a balança imprime na etiqueta (passos 1104b1 e 1104d,
 * BR-14) e chega aqui já na forma canônica do {@code InternalCodeNormalizer} — quem o normaliza é o
 * caso de uso, nunca o adaptador.
 */
public record NewProduct(
    UUID storeId,
    String name,
    String barcode,
    String internalCode,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity) {

  /** Produto sem código interno: o atalho de quem não informa o PLU da etiqueta. */
  public NewProduct(
      UUID storeId,
      String name,
      String barcode,
      String description,
      UUID categoryId,
      String unit,
      BigDecimal price,
      BigDecimal minQuantity) {
    this(storeId, name, barcode, null, description, categoryId, unit, price, minQuantity);
  }
}
