package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pedido de edição do cadastro (passo 410): os cinco campos que o {@code PUT} substitui — nome,
 * categoria, unidade, descrição e quantidade mínima — mais a versão que o cliente leu do produto e
 * mandou no {@code If-Match}. Preço (passo 411), barcode (imutável) e status (passo 412) não passam
 * por aqui.
 */
public record UpdateProductCommand(
    UUID id,
    long expectedVersion,
    String name,
    UUID categoryId,
    String unit,
    String description,
    BigDecimal minQuantity) {}
