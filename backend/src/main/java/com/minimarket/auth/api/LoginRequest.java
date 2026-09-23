package com.minimarket.auth.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/auth/login} (§9.3 do plano): valida só a forma do que o cliente
 * enviou. {@code cashRegisterId} é opcional e apenas repassado — a tabela de caixa nasce na Fase 6,
 * então a validação contra ela chega junto (passo 205).
 */
public record LoginRequest(
    @NotBlank(message = "não pode ser vazio") String username,
    @NotBlank(message = "não pode ser vazio") String password,
    UUID cashRegisterId) {}
