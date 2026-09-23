package com.minimarket.auth.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Identidade resolvida a partir do token de sessão (passo 206): o que o adaptador HTTP precisa para
 * montar a {@code SecurityIdentity}. O RBAC efetivo é relido do banco a cada requisição (§6.4 — o
 * servidor não confia em cache de token) e o id da sessão é o que os passos 208/210 usam para
 * revogar.
 */
public record AuthenticatedSession(
    UUID sessionId, String username, List<String> roles, Set<String> permissions) {}
