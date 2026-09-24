package com.minimarket.customers.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cliente como a API devolve (§9.3): só os campos do contrato — {@code storeId} e {@code deletedAt}
 * são detalhe do banco e não aparecem (o {@code active} já diz se o cliente está vivo). A projeção
 * {@code CustomerSummary} de {@code application} é mapeada para cá; entidade JPA nunca vai a JSON.
 * Reutilizado pelo cadastro, pelo detalhe, pela edição e pela desativação.
 */
public record CustomerResponse(
    UUID id,
    String name,
    String taxId,
    String phone,
    String email,
    String notes,
    boolean active,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
