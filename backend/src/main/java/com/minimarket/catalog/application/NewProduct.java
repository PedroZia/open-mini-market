package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dados que a porta {@link ProductStore} precisa para inserir um produto novo. O {@code storeId}
 * chega já resolvido pelo caso de uso (loja única do MVP, §5.3) e o produto nasce ativo, como no
 * default da tabela. {@code barcode}, {@code internalCode} e {@code minQuantity} são opcionais
 * (nulos); o código interno é o PLU que a balança imprime na etiqueta (passo 1104b1, BR-14) e o
 * cadastro ainda não o informa.
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

  /**
   * Produto sem código interno: o cadastro (passo 405) ainda não expõe o campo, então todo produto
   * nasce assim — o atalho evita repetir o {@code null} nos call sites que não têm o dado.
   */
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
