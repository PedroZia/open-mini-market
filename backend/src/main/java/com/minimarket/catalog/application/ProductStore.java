package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência do produto; o adaptador JPA fica em {@code catalog.infrastructure}. Só
 * tipos simples atravessam: nem entidade nem JPA chegam a {@code application}.
 */
public interface ProductStore {

  /** Insere o produto e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewProduct product);

  /**
   * Produto pelo id, <em>incluindo</em> o soft-deletado: a reativação (passo 412) precisa alcançar
   * o registro e quem decide o que fazer com {@code deletedAt} é o caso de uso. Vazio quando não
   * existe produto com o id.
   */
  Optional<ProductSummary> findById(UUID id);

  /**
   * Produto não deletado pelo barcode (caminho quente do PDV, passo 409). Diferente de {@link
   * #findById}, ignora o soft-deletado: o barcode só resolve produto vivo — a checagem de
   * duplicidade (passo 409) e a venda enxergam apenas o que o índice único parcial alcança.
   */
  Optional<ProductSummary> findByBarcode(String barcode);

  /**
   * Página de produtos não deletados (soft-deletado nunca aparece) com filtro textual em {@code
   * name} sem diferenciar maiúsculas ({@code search} em branco = sem filtro), filtro de categoria e
   * de status ({@code null} = sem filtro). {@code sort} é a whitelist já resolvida pelo caso de uso
   * — string do cliente nunca chega à consulta.
   */
  List<ProductSummary> search(
      String search,
      UUID categoryId,
      Boolean active,
      ProductSort sort,
      boolean ascending,
      int page,
      int size);

  /**
   * Total de produtos não deletados que casam com os filtros de {@link #search} (sem ordenação nem
   * paginação), para o {@code totalItems} e o {@code totalPages} da página.
   */
  long count(String search, UUID categoryId, Boolean active);

  /**
   * Grava nome, categoria, unidade, descrição e quantidade mínima do produto não deletado — preço
   * (passo 411), barcode (imutável) e status (passo 412) não passam por aqui. Devolve a projeção já
   * atualizada, com o {@code version} novo para o {@code If-Match} seguinte; vazio quando não
   * existe produto não deletado com o id — o 404 é do caso de uso.
   */
  Optional<ProductSummary> update(
      UUID id,
      String name,
      UUID categoryId,
      String unit,
      String description,
      BigDecimal minQuantity);

  /**
   * Grava o preço do produto não deletado (passo 411) — cadastro, barcode e status não passam por
   * aqui. Devolve a projeção já atualizada, com o {@code version} novo para o {@code If-Match}
   * seguinte; vazio quando não existe produto não deletado com o id — o 404 é do caso de uso.
   */
  Optional<ProductSummary> updatePrice(UUID id, BigDecimal price);

  /**
   * Soft delete: grava {@code deleted_at} e nada mais (o {@code active} é regra do caso de uso de
   * desativar/reativar) e libera o barcode para outro produto, como no índice único parcial. Id
   * desconhecido é no-op.
   */
  void softDelete(UUID id);

  /**
   * Desativa o produto vivo (passo 412): grava {@code active = false} e {@code deleted_at} de uma
   * vez, o que tira o produto da busca padrão, do detalhe e do bipe — e libera o barcode para outro
   * produto, como no índice único parcial. Vazio para id desconhecido ou produto já desativado
   * ({@code deletedAt} preenchido) — o 404 é do caso de uso. Devolve a projeção já com o estado
   * novo.
   */
  Optional<ProductSummary> disable(UUID id);

  /**
   * Reativa o produto desativado (passo 412): enxerga o soft-deletado, grava {@code active = true}
   * e limpa {@code deleted_at}, o que devolve o produto à busca e ao bipe. A gravação é conferida
   * contra o índice único de barcode: se outro produto vivo já tomou o código liberado na
   * desativação, a operação falha com {@code ConflictException(BARCODE_ALREADY_EXISTS)} e nada é
   * gravado. Vazio para id desconhecido — o 404 é do caso de uso.
   */
  Optional<ProductSummary> enable(UUID id);

  /**
   * Indica se existe produto não deletado com o barcode informado. A checagem não filtra por loja
   * porque o índice único do banco é {@code (store_id, barcode)} e o MVP tem loja única (§5.3) —
   * cobrir a tabela inteira é o mesmo escopo da constraint.
   */
  boolean existsActiveBarcode(String barcode);
}
