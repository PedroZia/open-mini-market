package com.minimarket.users.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Atualiza o que é mutável no cadastro (passo 111): nome de exibição e conjunto de papéis — o
 * username e a senha não passam por aqui. Uma execução = uma transação (§2.2, regra 6): o 400 de
 * papel desconhecido desfaz o que já foi gravado, então requisição inválida não deixa nome
 * alterado.
 */
@ApplicationScoped
public class UpdateUserUseCase {

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  /**
   * 404 quando não existe usuário vivo com o id; 400 quando algum código de papel é desconhecido.
   */
  @Transactional
  public UserSummary execute(UUID id, String displayName, List<String> roleCodes) {
    userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    List<String> roles = RoleCodes.normalize(roleCodes);
    requireKnownRoles(roles);

    userStore.updateDisplayName(id, displayName);
    roleStore.assignRoles(id, roles);

    return userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
  }

  /** Valida antes de gravar: papel desconhecido é erro de forma do pedido (400), não 404. */
  private void requireKnownRoles(List<String> roleCodes) {
    Set<String> unknown = roleStore.findUnknownCodes(roleCodes);
    if (!unknown.isEmpty()) {
      throw new BusinessException(
          ErrorCode.UNKNOWN_ROLE, "papel %s não existe".formatted(String.join(", ", unknown)));
    }
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id));
  }
}
