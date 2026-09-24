package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projeção de produto para a porta {@link ProductStore}: sem entidade JPA atravessando para {@code
 * application}. {@code deletedAt} preenchido é produto desativado; {@code active} falso com {@code
 * deletedAt} nulo é a janela entre desativar e reativar no caso de uso (passo 412). {@code version}
 * é o lock otimista que a edição (passo 410) exige no {@code If-Match}. {@code internalCode} é o
 * PLU da etiqueta de balança (passo 1104b1, BR-14).
 */
public record ProductSummary(
    UUID id,
    UUID storeId,
    String barcode,
    String internalCode,
    String name,
    String description,
    UUID categoryId,
    String unit,
    BigDecimal price,
    BigDecimal minQuantity,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt,
    long version) {

  /** Produto sem código interno: o atalho de quem não tem o PLU da etiqueta em mãos. */
  public ProductSummary(
      UUID id,
      UUID storeId,
      String barcode,
      String name,
      String description,
      UUID categoryId,
      String unit,
      BigDecimal price,
      BigDecimal minQuantity,
      boolean active,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version) {
    this(
        id,
        storeId,
        barcode,
        null,
        name,
        description,
        categoryId,
        unit,
        price,
        minQuantity,
        active,
        createdAt,
        updatedAt,
        deletedAt,
        version);
  }
}
