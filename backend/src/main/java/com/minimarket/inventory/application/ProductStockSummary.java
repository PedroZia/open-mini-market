package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projeção do saldo de estoque para a porta {@link ProductStockStore}: sem entidade JPA
 * atravessando para {@code application}. {@code quantity} é o cache do último {@code balance_after}
 * do ledger; {@code version} é o lock otimista da linha (o pessimista é o de {@link
 * ProductStockStore#lockByProduct}). A tabela não tem {@code created_at}: a linha nasce no primeiro
 * movimento e só o {@code updated_at} evolui.
 */
public record ProductStockSummary(
    UUID id, UUID storeId, UUID productId, BigDecimal quantity, Instant updatedAt, long version) {}
