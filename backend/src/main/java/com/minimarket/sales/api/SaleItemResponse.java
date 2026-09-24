package com.minimarket.sales.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Item da venda como a API devolve no detalhe (passo 809b): o snapshot do produto no instante da
 * inclusão (BR-01) — id, código de barras, nome, unidade e preço — mais a quantidade e o total da
 * linha. Alterar o cadastro do produto depois não muda nada aqui: o preço congelado é o que a linha
 * mostra. O item é identificado pelo {@code productId} (decisão do 802), e é ele que o cliente usa
 * nas rotas de item no lugar do {@code {itemId}} do §9.3.
 *
 * <p>Quantidade tem escala 3 (venda por peso) e o dinheiro, escala 2 (§3 do plano); os valores são
 * os recalculados pelo servidor (BR-02, BR-12). A projeção {@code SaleItem} do domínio é mapeada
 * para cá — entidade JPA nunca vai a JSON.
 */
public record SaleItemResponse(
    UUID productId,
    String barcode,
    String name,
    String unit,
    BigDecimal unitPrice,
    BigDecimal quantity,
    BigDecimal lineTotal) {}
