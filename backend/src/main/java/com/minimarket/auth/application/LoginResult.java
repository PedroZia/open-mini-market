package com.minimarket.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resultado do {@link LoginUseCase}: o token em claro (única vez em que ele existe fora do
 * cliente), a expiração absoluta e o RBAC efetivo do usuário autenticado. Nunca carrega o hash da
 * senha nem o hash do token.
 */
public record LoginResult(
    String token,
    Instant expiresAt,
    UUID userId,
    String username,
    String displayName,
    List<String> roles,
    Set<String> permissions,
    boolean mustChangePassword) {}
