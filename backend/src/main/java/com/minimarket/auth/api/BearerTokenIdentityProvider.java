package com.minimarket.auth.api;

import com.minimarket.auth.application.AuthenticateSessionUseCase;
import com.minimarket.auth.application.AuthenticatedSession;
import com.minimarket.auth.domain.TokenHasher;
import com.minimarket.shared.domain.BusinessException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;

/**
 * Resolve o token bearer em {@code SecurityIdentity} (passo 206): hasheia o token, valida a sessão
 * pelo {@link AuthenticateSessionUseCase} e monta a identidade com o principal (username), as
 * roles, as permissões efetivas e o id da sessão.
 *
 * <p>O acesso ao banco é bloqueante e roda em worker thread pelo {@code runBlocking} do Quarkus
 * Security — é o caminho documentado para autenticar com persistência (a autenticação proativa roda
 * no event loop). A falha de sessão vira {@link AuthenticationFailedException} <em>sem causa</em>:
 * o failure handler do Quarkus extrai a causa raiz e precisa reconhecer a exceção para produzir o
 * 401; o motivo viaja em atributo até o challenge.
 *
 * <p>As permissões vão como <em>atributo</em> da identidade ({@link #PERMISSIONS_ATTRIBUTE}) — o
 * {@code AuthorizationService}/{@code @RequirePermission} dos passos 305/306 é quem as interpreta.
 */
@ApplicationScoped
public class BearerTokenIdentityProvider implements IdentityProvider<TokenAuthenticationRequest> {

  /** Atributo da identidade com as permissões efetivas do usuário ({@code Set<String>}). */
  public static final String PERMISSIONS_ATTRIBUTE = "permissions";

  /** Atributo da identidade com o id da sessão autenticada ({@code String}, UUID). */
  public static final String SESSION_ID_ATTRIBUTE = "sessionId";

  @Inject AuthenticateSessionUseCase authenticateSessionUseCase;

  /** Hash do token é domínio puro, sem estado e sem CDI (passo 203). */
  private final TokenHasher tokenHasher = new TokenHasher();

  @Override
  public Class<TokenAuthenticationRequest> getRequestType() {
    return TokenAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TokenAuthenticationRequest request, AuthenticationRequestContext context) {
    return context.runBlocking(() -> resolve(request.getToken().getToken()));
  }

  /** Sessão válida vira identidade; credencial recusada vira 401 no challenge do mecanismo. */
  private SecurityIdentity resolve(String token) {
    try {
      AuthenticatedSession session = authenticateSessionUseCase.execute(tokenHasher.hash(token));
      return QuarkusSecurityIdentity.builder()
          .setPrincipal(new QuarkusPrincipal(session.username()))
          .addRoles(Set.copyOf(session.roles()))
          .addAttribute(PERMISSIONS_ATTRIBUTE, session.permissions())
          .addAttribute(SESSION_ID_ATTRIBUTE, session.sessionId().toString())
          .build();
    } catch (BusinessException failure) {
      throw new AuthenticationFailedException(
          Map.of(BearerTokenAuthenticationMechanism.FAILURE_CODE_ATTRIBUTE, failure.code()));
    }
  }
}
