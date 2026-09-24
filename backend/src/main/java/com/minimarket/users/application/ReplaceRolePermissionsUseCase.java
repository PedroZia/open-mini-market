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
import java.util.Objects;
import java.util.Set;

/**
 * Troca o mapa de permissões de uma role (§9.3 do plano, passo 114): editar role semeada é o
 * próprio propósito da tabela {@code role_permissions} — o mapa muda sem deploy, inclusive nas
 * roles {@code system}. Uma execução = uma transação (§2.2, regra 6): pedido inválido não deixa
 * permissão trocada.
 *
 * <p>Auditoria (passo 310b): a troca vira {@code ROLE_PERMISSIONS_CHANGED} na mesma transação, com
 * o código da role em {@code details} — role não tem UUID, então {@code entityId} fica nulo — e o
 * antes/depois das permissões, na ordem em que a porta as devolve. Troca que repete o conjunto
 * atual não muda nada e por isso não inventa evento.
 */
@ApplicationScoped
public class ReplaceRolePermissionsUseCase {

  /** Ação da troca do mapa de permissões de uma role (§7.2). */
  private static final String ROLE_PERMISSIONS_CHANGED_ACTION = "ROLE_PERMISSIONS_CHANGED";

  /** Alvo do evento: a role do catálogo, identificada pelo código, não por UUID (§7.2). */
  private static final String ROLE_ENTITY_TYPE = "ROLE";

  @Inject RoleAdminStore roleAdminStore;

  /** Auditoria da administração de acesso (passo 310b), na transação da troca. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code ROLE_NOT_FOUND} quando o código do path não existe no catálogo; 400 {@code
   * UNKNOWN_PERMISSION} quando o corpo traz código fora do catálogo, sem gravar nada; 200 com a
   * role já atualizada, para o cliente conferir o resultado.
   */
  @Transactional
  public RoleSummary execute(String roleCode, List<String> permissionCodes) {
    RoleSummary before = requireRole(roleCode);
    List<String> codes = normalize(permissionCodes);
    requireKnownPermissions(codes);
    RoleSummary after = roleAdminStore.replacePermissions(roleCode, codes);
    if (!before.permissions().equals(after.permissions())) {
      auditRecorder.record(
          ROLE_PERMISSIONS_CHANGED_ACTION,
          ROLE_ENTITY_TYPE,
          null,
          null,
          Map.of("role", roleCode, "before", before.permissions(), "after", after.permissions()));
    }
    return after;
  }

  /**
   * Valida o path antes de tudo — sem a role, o corpo nem é considerado — e devolve a projeção
   * atual, que é o "before" do evento: depois da troca ela já viria com as permissões novas.
   */
  private RoleSummary requireRole(String roleCode) {
    return roleAdminStore
        .findRole(roleCode)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.ROLE_NOT_FOUND, "papel %s não encontrado".formatted(roleCode)));
  }

  /** Valida antes de gravar: permissão desconhecida é erro de forma do pedido (400), não 404. */
  private void requireKnownPermissions(List<String> permissionCodes) {
    Set<String> unknown = roleAdminStore.findUnknownPermissionCodes(permissionCodes);
    if (!unknown.isEmpty()) {
      throw new BusinessException(
          ErrorCode.UNKNOWN_PERMISSION,
          "permissão %s não existe".formatted(String.join(", ", unknown)));
    }
  }

  /**
   * Trim, sem nulos e sem repetição, com a ordem preservada — mesmo tratamento dado aos códigos de
   * papel em {@link RoleCodes#normalize}.
   */
  private static List<String> normalize(List<String> permissionCodes) {
    return permissionCodes.stream().filter(Objects::nonNull).map(String::trim).distinct().toList();
  }
}
