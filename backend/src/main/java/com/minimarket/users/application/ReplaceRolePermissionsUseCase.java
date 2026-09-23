package com.minimarket.users.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Troca o mapa de permissões de uma role (§9.3 do plano, passo 114): editar role semeada é o
 * próprio propósito da tabela {@code role_permissions} — o mapa muda sem deploy, inclusive nas
 * roles {@code system}. Uma execução = uma transação (§2.2, regra 6): pedido inválido não deixa
 * permissão trocada.
 */
@ApplicationScoped
public class ReplaceRolePermissionsUseCase {

  @Inject RoleAdminStore roleAdminStore;

  /**
   * 404 {@code ROLE_NOT_FOUND} quando o código do path não existe no catálogo; 400 {@code
   * UNKNOWN_PERMISSION} quando o corpo traz código fora do catálogo, sem gravar nada; 200 com a
   * role já atualizada, para o cliente conferir o resultado.
   */
  @Transactional
  public RoleSummary execute(String roleCode, List<String> permissionCodes) {
    requireRole(roleCode);
    List<String> codes = normalize(permissionCodes);
    requireKnownPermissions(codes);
    return roleAdminStore.replacePermissions(roleCode, codes);
  }

  /** Valida o path antes de tudo: sem a role, o corpo nem é considerado. */
  private void requireRole(String roleCode) {
    if (roleAdminStore.findRole(roleCode).isEmpty()) {
      throw new NotFoundException(
          ErrorCode.ROLE_NOT_FOUND, "papel %s não encontrado".formatted(roleCode));
    }
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
