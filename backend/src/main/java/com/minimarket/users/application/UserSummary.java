package com.minimarket.users.application;

import java.util.List;
import java.util.UUID;

/**
 * Projeção de usuário para a listagem: sem hash de senha e sem entidade JPA atravessando a porta
 * {@link UserStore}. {@code mustChangePassword} (passo 113) diz se o usuário precisa trocar a senha
 * no próximo login.
 */
public record UserSummary(
    UUID id,
    String username,
    String displayName,
    String status,
    List<String> roles,
    boolean mustChangePassword) {}
