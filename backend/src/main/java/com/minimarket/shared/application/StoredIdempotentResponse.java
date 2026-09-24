package com.minimarket.shared.application;

import java.util.UUID;

/**
 * Registro devolvido pela porta {@link IdempotencyKeyStore}: o suficiente para comparar a
 * requisição atual e recompor a resposta gravada (status e corpo JSON), sem reexecutar o caso de
 * uso. O que decide o replay é {@code userId + method + path + requestHash} (§8).
 */
public record StoredIdempotentResponse(
    int statusCode,
    String responseBody,
    String requestHash,
    String method,
    String path,
    UUID userId) {}
