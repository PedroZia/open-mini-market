package com.minimarket.users.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/users/{id}/password-reset}: valida só a forma do que o cliente
 * enviou. O hash e a flag {@code mustChangePassword} são decididos pelo caso de uso — nunca vêm do
 * request.
 */
public record ResetPasswordRequest(
    @NotBlank(message = "não pode ser vazio")
        @Size(min = 8, message = "deve ter ao menos 8 caracteres")
        String newPassword) {}
