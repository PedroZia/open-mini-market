package com.minimarket.auth.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;

/**
 * Recurso só de teste (passo 206): expõe a identidade montada pelo mecanismo bearer para provar o
 * caminho ponta a ponta. O path é o único protegido pela política de {@code %test} — a proteção das
 * rotas reais é dos passos 307/308 e a janela da Fase 1 continua aberta.
 */
@Path(TestIdentityResource.PATH)
public class TestIdentityResource {

  public static final String PATH = "/api/v1/test/identity";

  @Inject SecurityIdentity identity;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public IdentityResponse identity() {
    return new IdentityResponse(
        identity.getPrincipal().getName(),
        identity.getRoles().stream().sorted().toList(),
        permissions(),
        (String) identity.getAttribute(BearerTokenIdentityProvider.SESSION_ID_ATTRIBUTE));
  }

  /** Permissões efetivas vêm como atributo da identidade (passos 305/306 leem daqui). */
  private List<String> permissions() {
    Object attribute = identity.getAttribute(BearerTokenIdentityProvider.PERMISSIONS_ATTRIBUTE);
    return attribute instanceof Set<?> values
        ? values.stream().map(String.class::cast).sorted().toList()
        : List.of();
  }

  /** Identidade devolvida ao teste: usuário, roles, permissões e id da sessão autenticada. */
  public record IdentityResponse(
      String username, List<String> roles, List<String> permissions, String sessionId) {}
}
