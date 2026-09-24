package com.minimarket.users.api;

import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import com.minimarket.users.application.ListRolesUseCase;
import com.minimarket.users.application.ReplaceRolePermissionsUseCase;
import com.minimarket.users.application.RoleSummary;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Papéis e mapa de permissões (§9.3 do plano, passo 114). A API valida forma, delega ao caso de uso
 * e mapeia a resposta — zero regra de negócio aqui. A política global (passo 307a) exige token e o
 * {@link RequirePermission} de cada rota exige a permissão: leitura com {@code user.read} (GERENTE
 * e ADMIN) e escrita do mapa só com {@code role.write} (ADMIN).
 */
@Path(RolesResource.PATH)
public class RolesResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/roles";

  @Inject ListRolesUseCase listRolesUseCase;

  @Inject ReplaceRolePermissionsUseCase replaceRolePermissionsUseCase;

  /** Catálogo completo: cada role com o nome, a descrição, o {@code system} e as permissões. */
  @GET
  @RequirePermission(Permission.USER_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public List<RoleResponse> list() {
    return listRolesUseCase.execute().stream().map(RolesResource::toResponse).toList();
  }

  /**
   * Substitui as permissões da role (passe o conjunto completo). Devolve 200 com a role atualizada
   * para o cliente conferir o resultado. Role fora do catálogo → 404 {@code ROLE_NOT_FOUND}; código
   * de permissão inexistente → 400 {@code UNKNOWN_PERMISSION} sem gravar nada.
   */
  @PUT
  @Path("/{code}/permissions")
  @RequirePermission(Permission.ROLE_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public RoleResponse replacePermissions(
      @PathParam("code") String code, @Valid ReplaceRolePermissionsRequest request) {
    return toResponse(replaceRolePermissionsUseCase.execute(code, request.permissions()));
  }

  private static RoleResponse toResponse(RoleSummary role) {
    return new RoleResponse(
        role.code(), role.name(), role.description(), role.system(), role.permissions());
  }
}
