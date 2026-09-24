package com.minimarket.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/products}: só a <em>forma</em> do que o cliente manda. A whitelist
 * de unidade ({@code UN}/{@code KG}), a normalização do barcode e do código interno da etiqueta e a
 * existência da categoria ficam no caso de uso — fonte única da regra — e não se repetem aqui.
 * {@code barcode}, {@code internalCode}, {@code description}, {@code categoryId} e {@code
 * minQuantity} são opcionais; loja e estado ativo nunca vêm do cliente.
 *
 * <p>Os limites de dígitos espelham o schema: preço {@code numeric(14,2)} e quantidade {@code
 * numeric(14,3)}, ambos não negativos.
 */
public record CreateProductRequest(
    @NotBlank(message = "não pode ser vazio") String name,
    String barcode,
    String internalCode,
    String description,
    UUID categoryId,
    @NotNull(message = "não pode ser nulo") String unit,
    @NotNull(message = "não pode ser nulo")
        @DecimalMin(value = "0.00", message = "não pode ser negativo")
        @Digits(integer = 12, fraction = 2, message = "no máximo 12 dígitos inteiros e 2 decimais")
        BigDecimal price,
    @DecimalMin(value = "0.00", message = "não pode ser negativo")
        @Digits(integer = 11, fraction = 3, message = "no máximo 11 dígitos inteiros e 3 decimais")
        BigDecimal minQuantity) {}
