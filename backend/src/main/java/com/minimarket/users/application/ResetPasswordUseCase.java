package com.minimarket.users.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Reset de senha por ADMIN (passo 113): define uma senha temporária, guarda só o hash e marca o
 * usuário para trocá-la no próximo login ({@code mustChangePassword}). Uma execução = uma transação
 * (§2.2, regra 6); o adaptador nunca abre transação. Revogar as sessões antigas é do passo 213.
 */
@ApplicationScoped
public class ResetPasswordUseCase {

  /** Mesma política mínima do {@link CreateUserUseCase}: só tamanho. */
  private static final int MIN_PASSWORD_LENGTH = 8;

  @Inject UserStore userStore;

  @Inject PasswordHasher passwordHasher;

  /**
   * 404 {@code USER_NOT_FOUND} quando não existe usuário vivo com o id (soft-deletado conta como
   * inexistente); 400 {@code VALIDATION_ERROR} quando a senha nova é curta demais.
   */
  @Transactional
  public UserSummary execute(UUID id, String newPassword) {
    userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    requireValidPassword(newPassword);
    return userStore
        .resetPassword(id, passwordHasher.hash(newPassword))
        .orElseThrow(() -> notFound(id));
  }

  private static void requireValidPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "senha deve ter ao menos %d caracteres".formatted(MIN_PASSWORD_LENGTH));
    }
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id));
  }
}
