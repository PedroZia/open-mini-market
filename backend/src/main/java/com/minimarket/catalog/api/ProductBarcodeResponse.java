package com.minimarket.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Produto do bipe (§9.3, passo 409): resposta enxuta do caminho quente — só o que a venda precisa
 * para montar o item (id, barcode, nome, preço e unidade), sem o cadastro completo do {@code
 * ProductResponse} e sem saldo de estoque (que é do módulo {@code inventory}). Entidade JPA nunca
 * vai a JSON: a projeção {@code BarcodeResolution} é mapeada para cá.
 *
 * <p>{@code quantity} (passo 1104b3, §9.4) é a quantidade sugerida quando o código embute peso ou
 * preço (etiqueta de balança, BR-14) e nula fora da etiqueta: GTIN e código interno digitado levam
 * a quantidade do cliente. O servidor nunca aplica o valor sozinho no bipe — quem decide é o
 * chamador.
 */
public record ProductBarcodeResponse(
    UUID id, String barcode, String name, BigDecimal price, String unit, BigDecimal quantity) {}
