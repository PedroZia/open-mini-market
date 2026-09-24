package com.minimarket.shared.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Loja atual do MVP: o código é resolvido por configuração ({@code minimarket.store.default-code}),
 * nunca por parâmetro do cliente (§5.4 do plano). Carrega só os parâmetros de negócio que os
 * clientes precisam receber (§9.3); seletor de loja não existe no MVP. O {@code id} entrou no passo
 * 204a porque a sessão guarda {@code store_id} (§5.3) e o {@code name} no 207 porque o {@code
 * /auth/me} identifica a loja da sessão para o cliente.
 *
 * <p>Os campos da etiqueta de balança (passo 1104b1, BR-14) são o formato que a balança da loja
 * imprime: prefixo, tamanho do código interno, campo embutido (peso ou preço) e casas decimais.
 */
public record Store(
    UUID id,
    String code,
    String name,
    boolean allowNegativeStock,
    BigDecimal maxDiscountPercent,
    String internalBarcodePrefix,
    int internalCodeLength,
    ScaleEmbeddedField scaleEmbeddedField,
    int scaleEmbeddedDecimals) {}
