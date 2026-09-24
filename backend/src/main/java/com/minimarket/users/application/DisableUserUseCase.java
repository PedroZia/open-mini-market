package com.minimarket.users.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Desativa o usuário (passo 112) sem apagar histórico: o adaptador grava {@code status = DISABLED}
 * e {@code deleted_at}, o que tira o usuário da busca padrão e libera o username para reuso (§5.3).
 * Uma execução = uma transação (§2.2, regra 6).
 *
 * <p>Depois de desativar, publica {@link UserAccessChangedEvent} com o motivo {@code
 * USER_DISABLED}: o observer de {@code auth} (passo 213) revoga todas as sessões vivas do usuário
 * na mesma transação — o acesso cai já na requisição seguinte, sem esperar a expiração.
 *
 * <p>Auditoria (passo 310a): a desativação vira {@code USER_DISABLED} na mesma transação, com o
 * antes/depois mínimo de status (§7.2). A desativação que não acontece (404 do último ADMIN ativo
 * ou de id inexistente) não inventa evento, porque nada foi gravado.
 */
@ApplicationScoped
public class DisableUserUseCase {

  /** Papel cujo último exemplar ativo não pode ser desativado: sem ele o sistema fica sem ADMIN. */
  private static final String ADMIN_ROLE = "ADMIN";

  /** Motivo gravado em {@code revoked_reason} das sessões revogadas pela desativação. */
  public static final String USER_DISABLED_REASON = "USER_DISABLED";

  /** Ação da desativação (§7.2). */
  private static final String USER_DISABLED_ACTION = "USER_DISABLED";

  /** Alvo dos eventos de administração de usuário (§7.2). */
  private static final String USER_ENTITY_TYPE = "USER";

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  /** Evento síncrono do corte de acesso (passo 213): a revogação das sessões é do módulo auth. */
  @Inject Event<UserAccessChangedEvent> accessChanged;

  /** Auditoria da administração de acesso (passo 310a), na transação da desativação. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code USER_NOT_FOUND} quando não existe usuário vivo com o id (já desativado conta como
   * inexistente); 409 {@code CONFLICT} quando o alvo é ADMIN e é o último ADMIN ativo.
   */
  @Transactional
  public UserSummary execute(UUID id) {
    UserSummary user = userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    requireNotLastActiveAdmin(user);
    UserSummary disabled = userStore.disable(id).orElseThrow(() -> notFound(id));
    auditRecorder.record(
        USER_DISABLED_ACTION,
        USER_ENTITY_TYPE,
        id,
        null,
        Map.of(
            "before", Map.of("status", user.status()),
            "after", Map.of("status", disabled.status())));
    accessChanged.fire(new UserAccessChangedEvent(id, USER_DISABLED_REASON));
    return disabled;
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
