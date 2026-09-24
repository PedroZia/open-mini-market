package com.minimarket.inventory.application;

import java.util.List;
import java.util.UUID;

/**
 * Porta de leitura da consulta de estoque (passo 704); o adaptador fica em {@code
 * inventory.infrastructure}. Diferente das portas de escrita dos passos 702/703, a consulta cruza o
 * saldo com o cadastro de produto: o join com {@code products} é feito pelo adaptador em SQL
 * nativo, sem importar a entidade de outro módulo (produto é do {@code catalog}).
 */
public interface StockQueryStore {

  /**
   * Página da lista de estoque da loja, ordenada por nome e id: produto vivo (<em>soft-deletado
   * nunca aparece</em>) com o saldo da loja — zero quando o produto nunca teve movimento. {@code
   * search} em branco = sem filtro textual (nome sem diferenciar maiúsculas ou barcode exato);
   * {@code lowStock} nulo = sem filtro — {@code true} devolve os produtos com estoque baixo e
   * {@code false} o complemento.
   */
  List<StockItemSummary> search(UUID storeId, String search, Boolean lowStock, int page, int size);

  /**
   * Total de produtos vivos que casam com os filtros de {@link #search} (sem ordenação nem
   * paginação), para o {@code totalItems} e o {@code totalPages} da página.
   */
  long count(UUID storeId, String search, Boolean lowStock);
}
