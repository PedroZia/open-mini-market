package com.minimarket.users.api;

import java.util.List;
import java.util.UUID;

/**
 * Usuário exposto pela API (§9.3): nunca carrega senha nem hash. {@code mustChangePassword} indica
 * que o usuário precisa trocar a senha no próximo login (passo 113).
 */
public record UserResponse(
    UUID id,
    String username,
    String displayName,
    List<String> roles,
    String status,
    boolean mustChangePassword) {}
