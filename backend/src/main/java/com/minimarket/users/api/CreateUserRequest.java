package com.minimarket.users.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Corpo de {@code POST /api/v1/users}: valida só a forma do que o cliente enviou. Normalização do
 * username e política de senha são regra do {@code CreateUserUseCase}, não da API.
 */
public record CreateUserRequest(
    @NotBlank(message = "não pode ser vazio") String username,
    @NotBlank(message = "não pode ser vazio") String displayName,
    @NotBlank(message = "não pode ser vazio")
        @Size(min = 8, message = "deve ter ao menos 8 caracteres")
        String password,
    List<String> roleCodes) {}
