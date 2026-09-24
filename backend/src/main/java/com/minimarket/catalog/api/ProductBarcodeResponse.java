package com.minimarket.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Produto do bipe (§9.3, passo 409): resposta enxuta do caminho quente — só o que a venda precisa
 * para montar o item (id, barcode, nome, preço e unidade), sem o cadastro completo do {@code
 * ProductResponse} e sem saldo de estoque (que é do módulo {@code inventory}). Entidade JPA nunca
 * vai a JSON: a projeção {@code ProductSummary} é mapeada para cá.
 */
public record ProductBarcodeResponse(
    UUID id, String barcode, String name, BigDecimal price, String unit) {}
