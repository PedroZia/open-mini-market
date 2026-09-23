package com.minimarket.users.application;

import java.util.List;
import java.util.UUID;

/**
 * Saída do {@link CreateUserUseCase}: nunca carrega a senha nem o hash — o que sai daqui pode virar
 * JSON sem vazar credencial.
 */
public record CreateUserResult(UUID id, String username, String displayName, List<String> roles) {}
