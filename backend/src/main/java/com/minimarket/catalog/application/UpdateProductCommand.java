package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pedido de edição do cadastro (passo 410): os campos que o {@code PUT} substitui — nome, código
 * interno (passo 1104d), categoria, unidade, descrição e quantidade mínima — mais a versão que o
 * cliente leu do produto e mandou no {@code If-Match}. Como os demais campos do PUT, o {@code
 * internalCode} segue a semântica de substituição: nulo — campo ausente no corpo ou {@code null} —
 * grava o produto sem código interno. Preço (passo 411), barcode (imutável) e status (passo 412)
 * não passam por aqui.
 */
public record UpdateProductCommand(
    UUID id,
    long expectedVersion,
    String name,
    String internalCode,
    UUID categoryId,
    String unit,
    String description,
    BigDecimal minQuantity) {}
