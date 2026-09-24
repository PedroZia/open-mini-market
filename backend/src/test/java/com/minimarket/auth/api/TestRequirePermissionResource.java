package com.minimarket.auth.api;

import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Recurso só de teste (passo 306): prova o porteiro declarativo ponta a ponta. A anotação da classe
 * exige {@code user.write} (só ADMIN) e a do método exige {@code audit.read} (GERENTE e ADMIN), o
 * que separa os papéis e prova que a anotação do método vence a da classe. O path é protegido pela
 * política global de {@code /api/v1/*} (passo 307a), como qualquer rota da API.
 */
@Path(TestRequirePermissionResource.PATH)
@RequirePermission(Permission.USER_WRITE)
public class TestRequirePermissionResource {

  public static final String PATH = "/api/v1/test/require-permission";

  @Inject SecurityIdentity identity;

  /** Exige {@code audit.read} no método: vence a permissão da classe. */
  @GET
  @Path("/audit")
  @RequirePermission(Permission.AUDIT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PermissionResponse audit() {
    return new PermissionResponse(identity.getPrincipal().getName());
  }

  /** Sem anotação no método: herda {@code user.write} da classe. */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public PermissionResponse management() {
    return new PermissionResponse(identity.getPrincipal().getName());
  }

  /** Corpo devolvido ao teste: quem passou pelo porteiro. */
  public record PermissionResponse(String username) {}
}
