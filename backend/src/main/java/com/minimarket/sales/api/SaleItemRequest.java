package com.minimarket.sales.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/sales/{id}/items} (§9.3, passo 809b): o produto bipado — pelo código
 * de barras <em>bruto</em> ou pelo id — e a quantidade. O barcode chega como o leitor o digitou e
 * quem normaliza, resolve o produto e soma o item repetido é o caso de uso do 808 (BR-14); o
 * cliente nunca interpreta código nem calcula quantidade.
 *
 * <p>A forma é validada aqui ({@code quantity} ausente ou não positiva → 400 {@code
 * VALIDATION_ERROR}); {@code barcode} e {@code productId} são opcionais individualmente e a regra
 * "um dos dois" é do caso de uso, que responde 400 quando faltam os dois — a API não a repete.
 * Código interno e etiqueta de balança (BR-14) ficam para o passo 1104b.
 */
public record SaleItemRequest(
    String barcode,
    UUID productId,
    @NotNull(message = "é obrigatório") @Positive(message = "deve ser maior que zero")
        BigDecimal quantity) {}
