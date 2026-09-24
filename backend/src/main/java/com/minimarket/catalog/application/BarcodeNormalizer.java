package com.minimarket.catalog.application;

/**
 * Normalização do barcode que chega bruto do cliente (BR-14, passo 1104b1): trim, sem espaços
 * internos — o leitor às vezes insere separador — e nulo quando em branco (produto sem código é
 * permitido, como no índice único parcial). Regra única dos três pontos que leem o código: cadastro
 * (passo 405), bipe (passo 409) e item da venda (passo 808).
 */
public final class BarcodeNormalizer {

  private BarcodeNormalizer() {}

  /** O código normalizado, ou nulo quando a entrada é nula ou em branco. */
  public static String normalize(String barcode) {
    if (barcode == null) {
      return null;
    }
    String normalized = barcode.trim().replaceAll("\\s+", "");
    return normalized.isEmpty() ? null : normalized;
  }
}
