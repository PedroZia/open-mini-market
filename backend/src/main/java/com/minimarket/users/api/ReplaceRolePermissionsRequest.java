package com.minimarket.users.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Corpo de {@code PUT /api/v1/roles/{code}/permissions}: os códigos que substituem o conjunto atual
 * da role; lista vazia zera. A existência dos códigos é do caso de uso — a API só garante que o
 * campo veio.
 */
public record ReplaceRolePermissionsRequest(
    @NotNull(message = "não pode ser nulo") List<String> permissions) {}
