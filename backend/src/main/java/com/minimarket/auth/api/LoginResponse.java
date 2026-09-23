package com.minimarket.auth.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resposta de {@code POST /api/v1/auth/login} (§9.3 do plano): o token em claro (única vez em que
 * ele existe fora do cliente), a expiração absoluta e o RBAC efetivo do usuário autenticado. Nunca
 * carrega o hash da senha nem o hash do token.
 */
public record LoginResponse(
    String token,
    Instant expiresAt,
    LoginUser user,
    List<String> roles,
    Set<String> permissions,
    boolean mustChangePassword) {

  /**
   * Identificação do usuário autenticado; o RBAC fica no nível de cima ({@code roles}/{@code
   * permissions}).
   */
  public record LoginUser(UUID id, String username, String displayName) {}
}
