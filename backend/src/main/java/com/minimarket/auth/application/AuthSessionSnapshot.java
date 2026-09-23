package com.minimarket.auth.application;

import com.minimarket.auth.domain.SessionClient;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão lida para autenticar uma requisição (passo 206) ou para montar o {@code /auth/me} (passo
 * 207): o que os casos de uso precisam saber sem que a entidade JPA atravesse a porta {@link
 * AuthSessionStore}. "Ativa" é o que o adaptador devolve — não revogada; a expiração absoluta é
 * decisão do caso de uso, com o {@code Clock} injetado. O cliente, a loja e o caixa são da sessão,
 * não do usuário (§6.2) e é o {@code /auth/me} quem os expõe.
 */
public record AuthSessionSnapshot(
    UUID id,
    UUID userId,
    SessionClient client,
    UUID storeId,
    UUID cashRegisterId,
    Instant lastSeenAt,
    Instant expiresAt) {}
