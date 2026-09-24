package com.minimarket.sales.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link AddSaleItemUseCase}: a venda alvo e o produto bipado, identificado pelo código
 * de barras <em>bruto</em> ou pelo id. A string do barcode chega como o leitor a digitou — quem
 * normaliza e resolve é o servidor (BR-14); o cliente nunca interpreta código nem calcula
 * quantidade.
 *
 * <p>O barcode tem precedência quando preenchido: é o caminho quente do PDV (409) e vem resolvido
 * pelo mesmo {@code ProductStore#findByBarcode} do bipe. Sem barcode, o produto vem do id — é o
 * caminho da TUI/web para produto sem código. Os dois ausentes é comando inválido: 400 {@code
 * VALIDATION_ERROR} no caso de uso. Código interno e etiqueta de balança (BR-14) ficam para o passo
 * 1104b.
 *
 * @param saleId venda que recebe o item
 * @param barcode código de barras lido, como veio do leitor; nulo ou em branco quando o produto vem
 *     pelo id
 * @param productId produto escolhido; ignorado quando há barcode
 * @param quantity quantidade vendida, maior que zero
 */
public record AddSaleItemCommand(
    UUID saleId, String barcode, UUID productId, BigDecimal quantity) {}
