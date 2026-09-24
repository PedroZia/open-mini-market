package com.minimarket.catalog.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Bipe do PDV (passo 409, estendido no 1104b3): resolve o produto pelo código lido no caminho
 * quente — GTIN, código interno digitado ou etiqueta de balança (BR-14) — e devolve a quantidade
 * que a etiqueta sugere, quando sugere alguma. A ordem da resolução e a normalização única moram no
 * {@link BarcodeResolver}, compartilhado com o item da venda.
 *
 * <p>Código em branco, desconhecido, de produto inativo ou soft-deletado → 404 {@code
 * PRODUCT_NOT_FOUND}, como no detalhe de 408: o PDV usa o 404 para oferecer cadastro rápido e não
 * distingue "não existe" de "não vende". Etiqueta malformada não cai no 404: é 422 {@code
 * INVALID_INTERNAL_BARCODE} do parser, que o mapper genérico já converte.
 *
 * <p>Leitura pura, sem {@code @Transactional}: não grava nada — mesma escolha do {@code
 * GetProductUseCase} e do {@link ListProductsUseCase}.
 */
@ApplicationScoped
public class GetProductByBarcodeUseCase {

  @Inject BarcodeResolver barcodeResolver;

  public BarcodeResolution execute(String barcode) {
    BarcodeResolution resolution = barcodeResolver.resolve(barcode);
    if (!resolution.product().active()) {
      throw BarcodeResolver.notFound(barcode);
    }
    return resolution;
  }
}
