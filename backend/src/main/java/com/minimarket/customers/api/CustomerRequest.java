package com.minimarket.customers.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /api/v1/customers} e {@code PUT /api/v1/customers/{id}}: os dois têm a mesma
 * forma (os cinco campos editáveis), então um record só atende os dois, como o {@code
 * CategoryRequest} do catálogo. {@code taxId}, {@code phone}, {@code email} e {@code notes} são
 * opcionais; o CPF (dígitos verificadores) e o telefone (só dígitos) são normalizados e validados
 * pelo caso de uso — fonte única da regra — e não se repetem aqui. Loja e estado ativo nunca vêm do
 * cliente.
 */
public record CustomerRequest(
    @NotBlank(message = "não pode ser vazio") String name,
    String taxId,
    String phone,
    String email,
    String notes) {}
