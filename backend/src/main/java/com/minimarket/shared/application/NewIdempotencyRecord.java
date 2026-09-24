package com.minimarket.shared.application;

import java.util.UUID;

/**
 * O que a primeira chamada tem para gravar: a chave, o ator e o caminho que a definem e a resposta
 * que será devolvida no replay. A expiração é política da aplicação ({@link
 * IdempotencyService#record}) e não viaja aqui.
 */
public record NewIdempotencyRecord(
    String key,
    UUID userId,
    String method,
    String path,
    String requestHash,
    int statusCode,
    String responseBody) {}
