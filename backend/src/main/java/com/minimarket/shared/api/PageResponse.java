package com.minimarket.shared.api;

import java.util.List;

/**
 * Envelope padrão de listagem paginada (§9.1 do plano): {@code page} é 0-based, {@code size} nunca
 * passa do teto de 100 e {@code totalPages} é o teto de {@code totalItems / size} (zero quando não
 * há item). Reutilizado por toda listagem da API.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}
