package com.minimarket.auth.api;

import com.minimarket.auth.domain.SessionClient;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resposta de {@code GET /api/v1/auth/me} (passo 207): o cliente valida a sessão ao abrir e monta a
 * tela com o usuário, o RBAC efetivo, a loja e o caixa da sessão. Reusa o {@code LoginUser} do
 * login (passo 205) — é o mesmo usuário, sem duplicar DTO — e nunca carrega hash de senha ou de
 * token.
 */
public record CurrentSessionResponse(
    LoginResponse.LoginUser user,
    List<String> roles,
    Set<String> permissions,
    StoreRef store,
    UUID cashRegisterId,
    SessionClient client,
    Instant expiresAt,
    Instant lastSeenAt) {

  /** Loja da sessão (§5.4): código e nome bastam para o cabeçalho do PDV. */
  public record StoreRef(String code, String name) {}
}
