package com.minimarket.auth.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/auth/login} (§9.3 do plano): valida só a forma do que o cliente
 * enviou. {@code cashRegisterId} é opcional; a validação contra a tabela de caixa — existente e
 * ativo — é do caso de uso (passo 607b), que responde 400 com {@code errors[]}.
 */
public record LoginRequest(
    @NotBlank(message = "não pode ser vazio") String username,
    @NotBlank(message = "não pode ser vazio") String password,
    UUID cashRegisterId) {}
