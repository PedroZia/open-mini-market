package com.minimarket.users.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Estado de autenticação do usuário para o login (passos 204a/204b): o que o caso de uso precisa
 * saber sem que a entidade JPA atravesse a porta {@link UserStore}. Traz o hash da senha (nunca sai
 * de {@code application} nem vira JSON) e o {@code deleted_at} porque o login precisa enxergar o
 * usuário soft-deletado para recusá-lo com a mesma mensagem genérica de senha errada. {@code
 * failedLoginAttempts} e {@code lockedUntil} são o estado do lock por tentativas (§6.3): quem
 * aplica a política é o caso de uso, que decide quando incrementar e quando bloquear. {@code roles}
 * e {@code permissions} são o RBAC efetivo devolvido ao cliente autenticado (§6.4).
 */
public record UserAuthState(
    UUID id,
    String username,
    String displayName,
    String passwordHash,
    String status,
    Instant deletedAt,
    int failedLoginAttempts,
    Instant lockedUntil,
    boolean mustChangePassword,
    List<String> roles,
    Set<String> permissions) {}
