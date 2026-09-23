package com.minimarket.users.application;

import java.util.List;

/**
 * Entrada do {@link CreateUserUseCase}: a senha chega em texto puro (é o único ponto do fluxo em
 * que ela existe) e os papéis vêm por código.
 */
public record CreateUserCommand(
    String username, String displayName, String password, List<String> roleCodes) {}
