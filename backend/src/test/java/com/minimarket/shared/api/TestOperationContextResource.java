package com.minimarket.shared.api;

import com.minimarket.shared.application.OperationContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.InetAddress;
import java.util.UUID;

/**
 * Recurso só de teste (passo 302): devolve o {@link OperationContext} preenchido durante a
 * requisição, provando o caminho identidade → contexto. O path é protegido pela política de {@code
 * %test} — a proteção das rotas reais é dos passos 307/308.
 */
@Path(TestOperationContextResource.PATH)
public class TestOperationContextResource {

  public static final String PATH = "/api/v1/test/operation-context";

  @Inject OperationContext context;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public OperationContextResponse context() {
    return new OperationContextResponse(
        context.userId(),
        context.username(),
        context.authSessionId(),
        context.storeId(),
        context.cashRegisterId(),
        context.requestId(),
        hostAddress(context.ip()),
        context.source().name());
  }

  /** IP como texto: o teste confere que é um literal de loopback, não um objeto. */
  private static String hostAddress(InetAddress ip) {
    return ip == null ? null : ip.getHostAddress();
  }

  /** Contexto devolvido ao teste: ator, sessão, loja, caixa, correlação, IP e origem. */
  public record OperationContextResponse(
      UUID userId,
      String username,
      UUID authSessionId,
      UUID storeId,
      UUID cashRegisterId,
      String requestId,
      String ip,
      String source) {}
}
