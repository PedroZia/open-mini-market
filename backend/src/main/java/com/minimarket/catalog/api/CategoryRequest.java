package com.minimarket.catalog.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/categories} e {@code PUT /api/v1/categories/{id}}: os dois têm a
 * mesma forma (nome, pai e ordenação), então um record só atende os dois. {@code parentId} nulo é
 * categoria raiz e {@code sortOrder} nulo vale o default da coluna (0) — a API não tem outros
 * campos. Loja e estado ativo nunca vêm do cliente.
 */
public record CategoryRequest(
    @NotBlank(message = "não pode ser vazio") String name, UUID parentId, Integer sortOrder) {}
