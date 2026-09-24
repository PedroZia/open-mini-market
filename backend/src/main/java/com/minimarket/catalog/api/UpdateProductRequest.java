package com.minimarket.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Corpo de {@code PUT /api/v1/products/{id}} (passo 410): só a <em>forma</em> do que o cliente
 * manda. A whitelist de unidade ({@code UN}/{@code KG}), a existência da categoria e a versão lida
 * no {@code If-Match} ficam com o caso de uso — fonte única da regra. Preço (passo 411), barcode
 * (imutável) e status (passo 412) não entram no corpo: quem os muda é a operação própria.
 *
 * <p>Os limites de dígitos espelham o schema: quantidade {@code numeric(14,3)}, não negativa.
 */
public record UpdateProductRequest(
    @NotBlank(message = "não pode ser vazio") String name,
    UUID categoryId,
    @NotNull(message = "não pode ser nulo") String unit,
    String description,
    @DecimalMin(value = "0.00", message = "não pode ser negativo")
        @Digits(integer = 11, fraction = 3, message = "no máximo 11 dígitos inteiros e 3 decimais")
        BigDecimal minQuantity) {}
