package com.minimarket.auth.application;

import com.minimarket.auth.domain.SessionClient;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão do usuário para a lista de sessões (passo 210): o que o dono precisa para reconhecer de
 * onde veio cada uma — cliente, IP e user agent — e quando nasceu, foi usada pela última vez e
 * expira. Nunca carrega o hash do token (§6.2). "Ativa" é o que a porta devolve: não revogada; a
 * expiração absoluta viaja como dado para o cliente decidir o que mostrar.
 */
public record UserSessionSummary(
    UUID id,
    SessionClient client,
    String ip,
    String userAgent,
    Instant createdAt,
    Instant lastSeenAt,
    Instant expiresAt) {}
