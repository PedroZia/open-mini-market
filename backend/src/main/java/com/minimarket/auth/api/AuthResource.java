package com.minimarket.auth.api;

import com.minimarket.auth.application.AuthenticateSessionUseCase;
import com.minimarket.auth.application.ChangeOwnPasswordUseCase;
import com.minimarket.auth.application.CurrentSession;
import com.minimarket.auth.application.GetCurrentSessionUseCase;
import com.minimarket.auth.application.ListUserSessionsUseCase;
import com.minimarket.auth.application.LoginCommand;
import com.minimarket.auth.application.LoginResult;
import com.minimarket.auth.application.LoginUseCase;
import com.minimarket.auth.application.LogoutUseCase;
import com.minimarket.auth.application.RevokeSessionUseCase;
import com.minimarket.auth.application.UserSessionSummary;
import com.minimarket.auth.domain.SessionClient;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

/**
 * Login, sessão atual e sessões do PDV (§9.3 do plano). A API valida forma, delega ao caso de uso e
 * mapeia a resposta — zero regra de negócio aqui. O login é público (exceção do passo 308): é por
 * ele que o cliente obtém o token. Falha de credenciais e conta bloqueada saem do {@code
 * LoginUseCase} como {@code problem+json} (401 {@code INVALID_CREDENTIALS} e 423 {@code
 * ACCOUNT_LOCKED}), assim como o rate limit por IP (429 {@code RATE_LIMITED} com {@code
 * Retry-After}, passo 212).
 */
@Path(AuthResource.PATH)
public class AuthResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/auth";

  /**
   * Header que diz de onde vem a tentativa (decisão do passo 205): {@code TUI} ou {@code WEB};
   * ausente assume {@code WEB}. O corpo do login (§9.3) não tem esse campo e o header é o caminho
   * menos invasivo para o contrato. Valor fora do par responde 400 {@code VALIDATION_ERROR}.
   */
  public static final String CLIENT_HEADER = "X-Client";

  @Inject LoginUseCase loginUseCase;

  @Inject GetCurrentSessionUseCase getCurrentSessionUseCase;

  @Inject LogoutUseCase logoutUseCase;

  @Inject ListUserSessionsUseCase listUserSessionsUseCase;

  @Inject RevokeSessionUseCase revokeSessionUseCase;

  @Inject ChangeOwnPasswordUseCase changeOwnPasswordUseCase;

  /** Identidade montada pelo mecanismo bearer (passo 206); as rotas de sessão a usam. */
  @Inject SecurityIdentity identity;

  @POST
  @Path("/login")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public LoginResponse login(
      @Valid LoginRequest request,
      @HeaderParam(CLIENT_HEADER) String clientHeader,
      @Context HttpHeaders headers,
      @Context HttpServerRequest httpRequest) {
    if (request == null) {
      // Sem corpo o leitor entrega null: é erro de forma (400), não erro interno.
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "corpo do login é obrigatório");
    }
    LoginResult result =
        loginUseCase.execute(
            new LoginCommand(
                request.username(),
                request.password(),
                clientOf(clientHeader),
                request.cashRegisterId(),
                remoteAddress(httpRequest),
                headers.getHeaderString(HttpHeaders.USER_AGENT)));
    return toResponse(result);
  }

  /**
   * Sessão atual (§9.3, passo 207): o cliente valida a sessão ao abrir. O {@code @Authenticated}
   * documenta a exigência no resource e a política global (passo 307a) garante o 401 {@code
   * problem+json} de quem não apresenta token. Sem token, ou com token
   * desconhecido/revogado/expirado, o challenge do mecanismo bearer (passo 206) responde 401 {@code
   * problem+json}.
   */
  @GET
  @Path("/me")
  @Authenticated
  @Produces(MediaType.APPLICATION_JSON)
  public CurrentSessionResponse me() {
    return toResponse(getCurrentSessionUseCase.execute(currentSessionId()));
  }

  /**
   * Encerra a sessão atual (§9.3, passo 208): revoga o token apresentado com o motivo {@code
   * LOGOUT} e responde 204 sem corpo (método {@code void}, como manda a especificação do JAX-RS). O
   * mesmo token deixa de autenticar na requisição seguinte — o mecanismo bearer só enxerga sessão
   * não revogada. Sem token, a política global (passo 307a) responde 401 {@code problem+json},
   * igual ao {@code /auth/me}.
   */
  @POST
  @Path("/logout")
  @Authenticated
  public void logout() {
    logoutUseCase.execute(currentSessionId());
  }

  /**
   * Sessões ativas do usuário autenticado (§6.2, passo 210): o cliente mostra de onde veio cada uma
   * e qual é a atual. A lista é sempre do dono do token — não há como pedir a sessão de outro
   * usuário. Sem token, a política global (passo 307a) responde 401 {@code problem+json}, como no
   * {@code /auth/me}.
   */
  @GET
  @Path("/sessions")
  @Authenticated
  @Produces(MediaType.APPLICATION_JSON)
  public List<UserSessionResponse> sessions() {
    UUID currentSessionId = currentSessionId();
    return listUserSessionsUseCase.execute(currentSessionId).stream()
        .map(session -> toResponse(session, currentSessionId))
        .toList();
  }

  /**
   * Revoga uma sessão pela lista (§6.2, passo 210): as do próprio usuário sempre; as de outro
   * usuário quando quem pede tem {@code user.session.revoke} (passo 307b), permissão que só o ADMIN
   * tem. Sem a permissão, a sessão alheia responde 404 pelo caso de uso, para não vazar a
   * existência dela. A rota segue sem {@code @RequirePermission}: qualquer autenticado revoga as
   * próprias sessões. Revogar a sessão atual é permitido — o cliente cai junto. Responde 204 sem
   * corpo e é idempotente como o logout: sessão já revogada ou id desconhecido também é 204. Sem
   * token, a política global (passo 307a) responde 401 {@code problem+json}.
   */
  @DELETE
  @Path("/sessions/{id}")
  @Authenticated
  public void revokeSession(@PathParam("id") UUID id) {
    revokeSessionUseCase.execute(currentSessionId(), id);
  }

  /**
   * Troca a própria senha (§9.3, passo 214): exige a senha atual, aplica a política da nova, limpa
   * {@code mustChangePassword} e derruba as outras sessões do usuário — a sessão que fez a troca
   * segue viva. Responde 204 sem corpo. Senha atual incorreta responde 400 {@code
   * INVALID_CURRENT_PASSWORD} e senha nova fora da política, 400 {@code VALIDATION_ERROR} (forma e
   * caso de uso). Sem token, a política global (passo 307a) responde 401 {@code problem+json}, como
   * no {@code /auth/me}.
   */
  @POST
  @Path("/password")
  @Authenticated
  @Consumes(MediaType.APPLICATION_JSON)
  public void changePassword(@Valid ChangePasswordRequest request) {
    if (request == null) {
      // Sem corpo o leitor entrega null: é erro de forma (400), não erro interno.
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "corpo da troca de senha é obrigatório");
    }
    changeOwnPasswordUseCase.execute(
        currentSessionId(), request.currentPassword(), request.newPassword());
  }

  private static LoginResponse toResponse(LoginResult result) {
    return new LoginResponse(
        result.token(),
        result.expiresAt(),
        new LoginResponse.LoginUser(result.userId(), result.username(), result.displayName()),
        result.roles(),
        result.permissions(),
        result.mustChangePassword());
  }

  private static CurrentSessionResponse toResponse(CurrentSession session) {
    return new CurrentSessionResponse(
        new LoginResponse.LoginUser(session.userId(), session.username(), session.displayName()),
        session.roles(),
        session.permissions(),
        new CurrentSessionResponse.StoreRef(session.storeCode(), session.storeName()),
        session.cashRegisterId(),
        session.client(),
        session.expiresAt(),
        session.lastSeenAt());
  }

  /** Sessão da lista com o {@code current} marcado pela sessão que pediu a listagem (passo 210). */
  private static UserSessionResponse toResponse(UserSessionSummary session, UUID currentSessionId) {
    return new UserSessionResponse(
        session.id(),
        session.client(),
        session.ip(),
        session.userAgent(),
        session.createdAt(),
        session.lastSeenAt(),
        session.expiresAt(),
        session.id().equals(currentSessionId));
  }

  /**
   * Id da sessão autenticada, do atributo que o provider bearer grava (passo 206). Identidade
   * autenticada sem sessão só existe se o mecanismo mudar: responde 401, não 500.
   */
  private UUID currentSessionId() {
    Object attribute = identity.getAttribute(BearerTokenIdentityProvider.SESSION_ID_ATTRIBUTE);
    if (attribute instanceof String value) {
      return UUID.fromString(value);
    }
    throw new BusinessException(
        ErrorCode.INVALID_CREDENTIALS, AuthenticateSessionUseCase.INVALID_TOKEN_DETAIL);
  }

  /** Traduz o header no cliente da sessão; ausente é {@code WEB}, qualquer outro valor é 400. */
  private static SessionClient clientOf(String clientHeader) {
    if (clientHeader == null || clientHeader.isBlank()) {
      return SessionClient.WEB;
    }
    return switch (clientHeader.trim()) {
      case "TUI" -> SessionClient.TUI;
      case "WEB" -> SessionClient.WEB;
      default ->
          throw new BusinessException(
              ErrorCode.VALIDATION_ERROR, CLIENT_HEADER + " deve ser TUI ou WEB");
    };
  }

  /**
   * Endereço remoto da conexão para a auditoria da sessão. Nulo quando o Vert.x não informa — o
   * {@code OperationContext} do passo 302 formaliza a coleta de IP e user agent.
   */
  private static InetAddress remoteAddress(HttpServerRequest httpRequest) {
    SocketAddress remote = httpRequest.remoteAddress();
    // hostAddress é sempre um literal (IPv4/IPv6): ofLiteral não faz DNS nem bloqueia.
    return remote == null || remote.hostAddress() == null
        ? null
        : InetAddress.ofLiteral(remote.hostAddress());
  }
}
