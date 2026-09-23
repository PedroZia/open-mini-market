package com.minimarket.auth.application;

import com.minimarket.auth.domain.SessionClient;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão nova para a porta {@link AuthSessionStore}: carrega só o hash do token (§6.2) — o token em
 * claro existe apenas no resultado do login. {@code lastSeenAt} é o marco zero do idle timeout e
 * vem do relógio do caso de uso (não do {@code now()} do JPA); {@code cashRegisterId}, {@code ip} e
 * {@code userAgent} são opcionais.
 */
public record NewAuthSession(
    UUID userId,
    String tokenHash,
    SessionClient client,
    UUID storeId,
    UUID cashRegisterId,
    InetAddress ip,
    String userAgent,
    Instant lastSeenAt,
    Instant expiresAt) {}
