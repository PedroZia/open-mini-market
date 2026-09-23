package com.minimarket.users.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Corpo de {@code PUT /api/v1/users/{id}}: só o que é mutável por aqui — o username e a senha não
 * aparecem (senha tem caso de uso próprio). {@code roleCodes} é obrigatório e substitui o conjunto
 * atual de papéis; lista vazia remove todos.
 */
public record UpdateUserRequest(
    @NotBlank(message = "não pode ser vazio") String displayName,
    @NotNull(message = "não pode ser nulo") List<String> roleCodes) {}
