package com.minimarket.catalog.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Bipe do PDV (passo 409): resolve o produto pelo barcode lido no caminho quente. A string bruta do
 * path passa pela mesma normalização do {@code CreateProductUseCase} (trim, sem espaços internos —
 * o leitor às vezes insere separador) e vai à porta {@link ProductStore#findByBarcode}, que ignora
 * o soft-deletado. Código em branco, desconhecido ou de produto inativo → 404 {@code
 * PRODUCT_NOT_FOUND}, como no detalhe de 408.
 *
 * <p>Leitura pura, sem {@code @Transactional}: não grava nada — mesma escolha do {@code
 * GetProductUseCase} e do {@link ListProductsUseCase}. Código interno e etiqueta de balança ficam
 * para o passo 1104b (BR-14).
 */
@ApplicationScoped
public class GetProductByBarcodeUseCase {

  @Inject ProductStore productStore;

  public ProductSummary execute(String barcode) {
    String normalized = normalizeBarcode(barcode);
    if (normalized == null) {
      throw notFound(barcode);
    }
    return productStore
        .findByBarcode(normalized)
        .filter(ProductSummary::active)
        .orElseThrow(() -> notFound(barcode));
  }

  /**
   * Mesma regra do {@code CreateProductUseCase} (passo 405): trim, todos os espaços internos fora e
   * nulo quando em branco — barcode em branco não tem produto para resolver.
   */
  private static String normalizeBarcode(String barcode) {
    if (barcode == null) {
      return null;
    }
    String normalized = barcode.trim().replaceAll("\\s+", "");
    return normalized.isEmpty() ? null : normalized;
  }

  private static NotFoundException notFound(String barcode) {
    return new NotFoundException(
        ErrorCode.PRODUCT_NOT_FOUND,
        "produto com código de barras %s não encontrado".formatted(barcode));
  }
}
