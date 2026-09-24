package com.minimarket.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code PATCH /api/v1/products/{id}/price} (passo 411): só a <em>forma</em> do que o
 * cliente manda. O preço repetido no corpo é a única forma de alterá-lo — o {@code PUT} de cadastro
 * (passo 410) não o aceita — e o motivo é obrigatório porque a alteração é auditada. Os limites de
 * dígitos espelham o schema: preço {@code numeric(14,2)}, não negativo.
 */
public record ChangeProductPriceRequest(
    @NotNull(message = "não pode ser nulo")
        @DecimalMin(value = "0.00", message = "não pode ser negativo")
        @Digits(integer = 12, fraction = 2, message = "no máximo 12 dígitos inteiros e 2 decimais")
        BigDecimal price,
    @NotBlank(message = "não pode ser vazio") String reason) {}
