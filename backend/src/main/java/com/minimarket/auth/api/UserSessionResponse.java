package com.minimarket.auth.api;

import com.minimarket.auth.domain.SessionClient;
import java.time.Instant;
import java.util.UUID;

/**
 * Item de {@code GET /api/v1/auth/sessions} (passo 210): a origem e o ciclo de vida de uma sessão
 * ativa do usuário, sem nunca carregar o hash do token (§6.2). {@code current} marca a sessão do
 * token que pediu a lista — é por ela que o cliente sabe qual linha é "este dispositivo".
 */
public record UserSessionResponse(
    UUID id,
    SessionClient client,
    String ip,
    String userAgent,
    Instant createdAt,
    Instant lastSeenAt,
    Instant expiresAt,
    boolean current) {}
