package com.minimarket.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Sessão lida para autenticar uma requisição (passo 206): o que o caso de uso precisa saber sem que
 * a entidade JPA atravesse a porta {@link AuthSessionStore}. "Ativa" é o que o adaptador devolve —
 * não revogada; a expiração absoluta é decisão do caso de uso, com o {@code Clock} injetado.
 */
public record AuthSessionSnapshot(UUID id, UUID userId, Instant lastSeenAt, Instant expiresAt) {}
