package com.minimarket.catalog.application;

import java.math.BigDecimal;

/**
 * Código lido resolvido no servidor (passo 1104b3, BR-14): o produto e a quantidade que o próprio
 * código embutiu, quando embutiu alguma.
 *
 * <p>{@code quantity} nulo é o caminho comum — GTIN e código interno digitado não trazem
 * quantidade, e quem manda nela é o cliente (o multiplicador {@code 3*} da TUI, §11.2). Preenchida
 * é a etiqueta de balança: o peso em kg ou o total em reais dividido pelo preço vigente do produto.
 * É uma <em>sugestão do servidor</em>, não um efeito: cada chamador decide o que fazer com ela — o
 * bipe devolve na resposta e o item da venda usa no lugar da quantidade do comando.
 *
 * <p>Produto inativo atravessa o resolver normalmente: o filtro de status é do chamador (o bipe
 * responde 404 e o item da venda, 422 {@code PRODUCT_INACTIVE}), como antes do 1104b3.
 */
public record BarcodeResolution(ProductSummary product, BigDecimal quantity) {}
