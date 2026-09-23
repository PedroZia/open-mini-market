package com.minimarket.auth.application;

import com.minimarket.auth.domain.SessionClient;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sessão atual do token autenticado (passo 207): o que o cliente precisa para se apresentar ao
 * abrir — usuário, RBAC efetivo, loja e caixa da sessão, cliente de origem e ciclo de vida. Nunca
 * carrega o hash da senha nem o hash do token.
 */
public record CurrentSession(
    UUID userId,
    String username,
    String displayName,
    List<String> roles,
    Set<String> permissions,
    String storeCode,
    String storeName,
    UUID cashRegisterId,
    SessionClient client,
    Instant expiresAt,
    Instant lastSeenAt) {}
