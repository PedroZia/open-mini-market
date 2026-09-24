package com.minimarket.auth.application;

import com.minimarket.auth.domain.SessionClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Identidade resolvida a partir do token de sessão (passo 206): o que o adaptador HTTP precisa para
 * montar a {@code SecurityIdentity}. O RBAC efetivo é relido do banco a cada requisição (§6.4 — o
 * servidor não confia em cache de token) e o id da sessão é o que os passos 208/210 usam para
 * revogar.
 *
 * <p>O usuário, o cliente, a loja e o caixa vêm da sessão, não do usuário (§6.2): são eles que o
 * adaptador publica como atributos da identidade e o {@code OperationContext} (passo 302) lê para a
 * auditoria — por isso viajam aqui em vez de uma segunda consulta por requisição.
 */
public record AuthenticatedSession(
    UUID sessionId,
    UUID userId,
    String username,
    List<String> roles,
    Set<String> permissions,
    SessionClient client,
    UUID storeId,
    UUID cashRegisterId) {}
