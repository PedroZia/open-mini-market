package com.minimarket.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/products/quick}: o cadastro rápido do PDV (F-02, passo 1122) leva só
 * o que o modal do caixa coleta — nome, código lido e preço/unidade. Aqui só a <em>forma</em>: a
 * whitelist de unidade ({@code UN}/{@code KG}), a normalização do barcode e a duplicidade ficam no
 * caso de uso — fonte única da regra — e não se repetem.
 *
 * <p>{@code barcode} é obrigatório, diferente do cadastro completo: é a leitura desconhecida que
 * abriu o modal e o código que o operador não pode editar. Os limites de dígitos espelham o schema:
 * preço {@code numeric(14,2)}, não negativo.
 */
public record QuickCreateProductRequest(
    @NotBlank(message = "não pode ser vazio") String name,
    @NotBlank(message = "não pode ser vazio") String barcode,
    @NotNull(message = "não pode ser nulo")
        @DecimalMin(value = "0.00", message = "não pode ser negativo")
        @Digits(integer = 12, fraction = 2, message = "no máximo 12 dígitos inteiros e 2 decimais")
        BigDecimal price,
    @NotNull(message = "não pode ser nulo") String unit) {}
