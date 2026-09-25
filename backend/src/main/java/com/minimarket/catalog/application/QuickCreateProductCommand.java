package com.minimarket.catalog.application;

import java.math.BigDecimal;

/**
 * Entrada do {@link QuickCreateProductUseCase}: o cadastro rápido do PDV (F-02, passo 1122) leva só
 * o que o modal do caixa coleta — nome, código lido (cru, como o cliente o digitou: o caso de uso o
 * normaliza) e preço/unidade. Descrição, categoria, código interno e quantidade mínima não entram:
 * o produto nasce com o essencial e o que faltar se completa na retaguarda, pelo PUT do passo 410.
 */
public record QuickCreateProductCommand(
    String name, String barcode, BigDecimal price, String unit) {}
