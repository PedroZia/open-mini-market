package com.minimarket.users.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Porta de administração do RBAC (passo 114): ler o catálogo de roles com suas permissões e trocar
 * o mapa role→permissão sem deploy. O adaptador JPA vive em {@code users.infrastructure}, onde as
 * consultas de {@code role_permissions} já moram (passo 106) — só tipos simples e {@link
 * RoleSummary} atravessam.
 */
public interface RoleAdminStore {

  /** Todas as roles do catálogo em ordem de código, cada uma com as permissões em ordem. */
  List<RoleSummary> listRoles();

  /** Role pelo código, com as permissões atuais; vazio quando o código não existe no catálogo. */
  Optional<RoleSummary> findRole(String roleCode);

  /**
   * Códigos de permissão informados que não existem no catálogo, na ordem de entrada. Consulta
   * pura, sem gravar nada: quem transforma o resultado em erro (400 {@code UNKNOWN_PERMISSION}) é o
   * caso de uso, antes de tocar na role — o 400 não sai de exceção do adaptador.
   */
  Set<String> findUnknownPermissionCodes(Collection<String> permissionCodes);

  /**
   * Substitui as permissões da role pelos códigos informados (lista vazia zera) e devolve a
   * projeção já atualizada. Role ou permissão inexistente derruba a operação com {@code
   * NotFoundException}, sem gravar nada; o caso de uso valida antes, então na prática só a corrida
   * chega aqui.
   */
  RoleSummary replacePermissions(String roleCode, Collection<String> permissionCodes);
}
