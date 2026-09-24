package com.minimarket.users.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
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
 * <p>Remover o papel ADMIN do último ADMIN ativo zeraria os administradores do sistema, então esse
 * update responde 409 {@code CONFLICT} (passo 1012) — a checagem trava os ADMINs ativos antes de
 * decidir, como o disable do passo 1008, para dois updates simultâneos não zerarem os ADMINs.
 *
 * <p>Auditoria (passo 310a): a alteração vira {@code USER_UPDATED} na mesma transação, com o
 * antes/depois mínimo de displayName e roles (§7.2). O "before" é lido antes de gravar — depois do
 * update a projeção já viria com os valores novos.
 */
@ApplicationScoped
public class UpdateUserUseCase {

  /** Papel cujo último exemplar ativo não pode ser removido: sem ele o sistema fica sem ADMIN. */
  private static final String ADMIN_ROLE = "ADMIN";

  /** Ação do usuário alterado (§7.2). */
  private static final String USER_UPDATED_ACTION = "USER_UPDATED";

  /** Alvo dos eventos de administração de usuário (§7.2). */
  private static final String USER_ENTITY_TYPE = "USER";

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  /** Auditoria da administração de acesso (passo 310a), na transação da alteração. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 quando não existe usuário vivo com o id; 400 quando algum código de papel é desconhecido;
   * 409 quando o novo conjunto removeria o papel ADMIN do último ADMIN ativo.
   */
  @Transactional
  public UserSummary execute(UUID id, String displayName, List<String> roleCodes) {
    UserSummary before = userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    List<String> roles = RoleCodes.normalize(roleCodes);
    requireKnownRoles(roles);
    requireNotRemovingLastActiveAdmin(before, roles);

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

  /**
   * Trava os ADMINs ativos e decide sobre a lista travada, como o disable do passo 1008: se o alvo
   * está entre eles e a lista tem um só, tirar o papel dele zeraria os administradores. A checagem
   * não é read-then-write — dois updates simultâneos não enxergam o mesmo "ainda há dois": o
   * perdedor da corrida reavalia a lista já sem o ADMIN que o vencedor tirou do papel e recebe o
   * 409. Só o update que pode zerar os ADMINs trava; alvo já desativado (fora da lista) ou que
   * continua com o papel não é afetado, e a partir de dois ADMINs ativos a remoção é permitida.
   */
  private void requireNotRemovingLastActiveAdmin(UserSummary user, List<String> roles) {
    if (!user.roles().contains(ADMIN_ROLE) || roles.contains(ADMIN_ROLE)) {
      return;
    }
    // Trava os ADMINs ativos (passo 1008): dois updates concorrentes não decidem sobre o mesmo
    // retrato — quem chega depois espera o commit do vencedor.
    roleStore.lockActiveUserIdsWithRole(ADMIN_ROLE);
    // Revalida sob o lock com leitura nova: a consulta travada acima pode responder com o retrato
    // de antes do commit do vencedor (a trava é na linha de users, e a troca de papéis não altera
    // essa linha), enquanto esta enxerga o papel que ele já removeu.
    List<UUID> activeAdmins = roleStore.lockActiveUserIdsWithRole(ADMIN_ROLE);
    if (activeAdmins.contains(user.id()) && activeAdmins.size() <= 1) {
      throw new ConflictException(
          ErrorCode.CONFLICT, "não é possível remover o papel ADMIN do último ADMIN ativo");
    }
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id));
  }
}
