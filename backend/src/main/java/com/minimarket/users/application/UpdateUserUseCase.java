package com.minimarket.users.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Atualiza o que é mutável no cadastro (passo 111): nome de exibição e conjunto de papéis — o
 * username e a senha não passam por aqui. Uma execução = uma transação (§2.2, regra 6): o 400 de
 * papel desconhecido desfaz o que já foi gravado, então requisição inválida não deixa nome
 * alterado.
 *
 * <p>Auditoria (passo 310a): a alteração vira {@code USER_UPDATED} na mesma transação, com o
 * antes/depois mínimo de displayName e roles (§7.2). O "before" é lido antes de gravar — depois do
 * update a projeção já viria com os valores novos.
 */
@ApplicationScoped
public class UpdateUserUseCase {

  /** Ação do usuário alterado (§7.2). */
  private static final String USER_UPDATED_ACTION = "USER_UPDATED";

  /** Alvo dos eventos de administração de usuário (§7.2). */
  private static final String USER_ENTITY_TYPE = "USER";

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  /** Auditoria da administração de acesso (passo 310a), na transação da alteração. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 quando não existe usuário vivo com o id; 400 quando algum código de papel é desconhecido.
   */
  @Transactional
  public UserSummary execute(UUID id, String displayName, List<String> roleCodes) {
    UserSummary before = userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    List<String> roles = RoleCodes.normalize(roleCodes);
    requireKnownRoles(roles);

    userStore.updateDisplayName(id, displayName);
    roleStore.assignRoles(id, roles);

    UserSummary after = userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    auditRecorder.record(
        USER_UPDATED_ACTION,
        USER_ENTITY_TYPE,
        id,
        null,
        Map.of("before", displayNameAndRoles(before), "after", displayNameAndRoles(after)));
    return after;
  }

  /** O antes/depois mínimo do §7.2: só o que a operação muda, nunca o cadastro inteiro. */
  private static Map<String, Object> displayNameAndRoles(UserSummary user) {
    return Map.of("displayName", user.displayName(), "roles", user.roles());
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
