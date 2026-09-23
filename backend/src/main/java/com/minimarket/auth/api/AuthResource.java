package com.minimarket.auth.api;

import com.minimarket.auth.application.LoginCommand;
import com.minimarket.auth.application.LoginResult;
import com.minimarket.auth.application.LoginUseCase;
import com.minimarket.auth.domain.SessionClient;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.net.InetAddress;

/**
 * Login do PDV (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta —
 * zero regra de negócio aqui. A rota é pública (exceção do passo 308): é por ela que o cliente
 * obtém o token. Falha de credenciais e conta bloqueada saem do {@code LoginUseCase} como {@code
 * problem+json} (401 {@code INVALID_CREDENTIALS} e 423 {@code ACCOUNT_LOCKED}).
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

  private static LoginResponse toResponse(LoginResult result) {
    return new LoginResponse(
        result.token(),
        result.expiresAt(),
        new LoginResponse.LoginUser(result.userId(), result.username(), result.displayName()),
        result.roles(),
        result.permissions(),
        result.mustChangePassword());
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
