package com.minimarket.customers.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Projeção de cliente para a porta {@link CustomerStore}: sem entidade JPA atravessando para {@code
 * application}. {@code taxId} é o CPF já normalizado (só dígitos) quando informado; {@code
 * deletedAt} preenchido é cliente desativado e {@code version} é o lock otimista da edição.
 */
public record CustomerSummary(
    UUID id,
    UUID storeId,
    String name,
    String taxId,
    String phone,
    String email,
    String notes,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt,
    long version) {}
