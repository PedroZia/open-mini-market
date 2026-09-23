package com.minimarket.users.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * Garante o ADMIN inicial (passo 115): primeiro acesso em ambiente novo. Cria o usuário pelo {@link
 * CreateUserUseCase} — normalização do username, política de senha, hash e atribuição de papéis
 * continuam lá — e marca a troca obrigatória no primeiro login. Uma execução = uma transação (§2.2,
 * regra 6); o adaptador nunca abre transação.
 *
 * <p>Idempotente: com ADMIN ativo já existente nada é criado. A condição é a mesma da regra "não
 * desativar o último ADMIN ativo" (passo 112) — papel ADMIN, {@code status = ACTIVE} e {@code
 * deleted_at} nulo — e a consulta é do adaptador, via {@link RoleStore#countActiveUsersWithRole}.
 */
@ApplicationScoped
public class EnsureInitialAdminUseCase {

  /** Papel do usuário semeado: sem ADMIN ativo o sistema fica sem administrador. */
  private static final String ADMIN_ROLE = "ADMIN";

  /** Nome de exibição do ADMIN semeado; username e senha vêm da configuração (passo 115). */
  private static final String DISPLAY_NAME = "Administrador";

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  @Inject CreateUserUseCase createUserUseCase;

  /**
   * Cria o ADMIN inicial e devolve {@code true} quando não havia ADMIN ativo; com ADMIN ativo já
   * existente devolve {@code false} sem gravar nada. Senha fora da política mínima derruba a
   * operação com {@code VALIDATION_ERROR} — configuração inválida falha no startup, não em
   * silêncio.
   */
  @Transactional
  public boolean execute(String username, String password) {
    if (roleStore.countActiveUsersWithRole(ADMIN_ROLE) > 0) {
      return false;
    }
    CreateUserResult created =
        createUserUseCase.execute(
            new CreateUserCommand(username, DISPLAY_NAME, password, List.of(ADMIN_ROLE)));
    userStore.requirePasswordChange(created.id());
    return true;
  }
}
