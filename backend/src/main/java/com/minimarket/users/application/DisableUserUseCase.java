package com.minimarket.users.application;

import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Desativa o usuário (passo 112) sem apagar histórico: o adaptador grava {@code status = DISABLED}
 * e {@code deleted_at}, o que tira o usuário da busca padrão e libera o username para reuso (§5.3).
 * Uma execução = uma transação (§2.2, regra 6).
 */
@ApplicationScoped
public class DisableUserUseCase {

  /** Papel cujo último exemplar ativo não pode ser desativado: sem ele o sistema fica sem ADMIN. */
  private static final String ADMIN_ROLE = "ADMIN";

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  /**
   * 404 {@code USER_NOT_FOUND} quando não existe usuário vivo com o id (já desativado conta como
   * inexistente); 409 {@code CONFLICT} quando o alvo é ADMIN e é o último ADMIN ativo.
   */
  @Transactional
  public UserSummary execute(UUID id) {
    UserSummary user = userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    requireNotLastActiveAdmin(user);
    return userStore.disable(id).orElseThrow(() -> notFound(id));
  }

  /**
   * Conta os ADMINs ativos antes de desativar: a partir do segundo a operação é permitida, porque
   * ainda sobra um ADMIN para administrar o sistema.
   */
  private void requireNotLastActiveAdmin(UserSummary user) {
    if (user.roles().contains(ADMIN_ROLE) && roleStore.countActiveUsersWithRole(ADMIN_ROLE) <= 1) {
      throw new ConflictException(
          ErrorCode.CONFLICT, "não é possível desativar o último ADMIN ativo");
    }
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id));
  }
}
