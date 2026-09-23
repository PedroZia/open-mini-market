package com.minimarket.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/auth/password} (§9.3, passo 214): a troca exige a senha atual e a
 * nova. Aqui só a forma é validada — a política de tamanho é repetida no caso de uso, que é quem
 * decide; a API não contém regra de negócio.
 */
public record ChangePasswordRequest(
    @NotBlank(message = "não pode ser vazio") String currentPassword,
    @NotBlank(message = "não pode ser vazio")
        @Size(min = 8, message = "deve ter ao menos 8 caracteres")
        String newPassword) {}
