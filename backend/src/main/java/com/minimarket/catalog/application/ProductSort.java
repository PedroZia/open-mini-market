package com.minimarket.catalog.application;

/**
 * Campos ordenáveis da listagem de produtos (§9.1). Whitelist fechada: o adaptador traduz o enum em
 * coluna JPQL, então nenhuma string do cliente chega à consulta.
 */
public enum ProductSort {
  NAME,
  PRICE,
  CREATED_AT
}
