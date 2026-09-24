package com.minimarket.shared.api;

import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.OperationSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import java.net.InetAddress;
import java.util.UUID;
import org.jboss.logmanager.MDC;

/**
 * Preenche o {@link OperationContext} da requisição (passo 302) a partir da identidade montada pelo
 * mecanismo bearer (passo 206) — sem consulta ao banco: o que o contexto carrega já veio na
 * autenticação.
 *
 * <p>Não é {@code @PreMatching} de propósito: assim roda depois do {@link RequestIdFilter}, que
 * publica o id de correlação antes do roteamento. Sem identidade (login, meta) o contexto fica com
 * ator nulo e origem {@code API}; o cliente da sessão autenticada decide entre {@code TUI} e {@code
 * WEB}.
 */
@Provider
public class OperationContextFilter implements ContainerRequestFilter {

  /** Identidade da requisição: é ela que traz o ator e a sessão. */
  @Inject SecurityIdentity identity;

  /** Contexto preenchido por este filtro e lido pelo resto da aplicação. */
  @Inject OperationContext context;

  /** Conexão Vert.x: de onde sai o IP de origem (mesmo padrão do login, passo 205). */
  @Context HttpServerRequest httpRequest;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    context.fill(
        uuidAttribute(OperationContext.USER_ID_ATTRIBUTE),
        username(),
        uuidAttribute(OperationContext.AUTH_SESSION_ID_ATTRIBUTE),
        uuidAttribute(OperationContext.STORE_ID_ATTRIBUTE),
        uuidAttribute(OperationContext.CASH_REGISTER_ID_ATTRIBUTE),
        requestId(requestContext),
        remoteAddress(),
        source());
  }

  /** Requisição anônima não tem ator: o principal do Quarkus não vale como username aqui. */
  private String username() {
    return identity.isAnonymous() ? null : identity.getPrincipal().getName();
  }

  /** Atributo de UUID gravado como texto pelo provider bearer; ausente continua ausente. */
  private UUID uuidAttribute(String name) {
    Object value = identity.getAttribute(name);
    return value instanceof String text ? UUID.fromString(text) : null;
  }

  /** Cliente da sessão autenticada vira a origem do evento; sem identidade a origem é a API. */
  private OperationSource source() {
    Object client = identity.getAttribute(OperationContext.CLIENT_ATTRIBUTE);
    return client instanceof OperationSource value ? value : OperationSource.API;
  }

  /**
   * Reusa o id de correlação do {@link RequestIdFilter} — o filtro é {@code @PreMatching} e já
   * publicou o id no MDC; o header é o fallback de quem chega sem o MDC preenchido. Gerar um
   * segundo UUID aqui faria o {@code request_id} da auditoria não casar com o {@code X-Request-Id}
   * da resposta.
   */
  private static String requestId(ContainerRequestContext requestContext) {
    String requestId = MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
    return requestId == null || requestId.isBlank()
        ? requestContext.getHeaderString(RequestIdFilter.REQUEST_ID_HEADER)
        : requestId;
  }

  /** Endereço remoto da conexão como literal (IPv4/IPv6): sem DNS e sem bloquear o request. */
  private InetAddress remoteAddress() {
    SocketAddress remote = httpRequest.remoteAddress();
    return remote == null || remote.hostAddress() == null
        ? null
        : InetAddress.ofLiteral(remote.hostAddress());
  }
}
