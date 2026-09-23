package com.minimarket.shared.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;

/**
 * Correlation ID da requisição: aceita o {@code X-Request-Id} do cliente ou gera um UUID, publica
 * no MDC (chave {@code traceId}, lida pelos logs e pelo corpo de erro) e devolve no header da
 * resposta. O mesmo filtro registra o log de acesso com método, rota, status e duração.
 */
@Provider
@PreMatching
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

  /** Header de correlação: aceito na entrada e devolvido em toda resposta. */
  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  /** Chave do MDC com o id de correlação da requisição. */
  public static final String TRACE_ID_MDC_KEY = "traceId";

  private static final String START_NANOS_PROPERTY =
      RequestIdFilter.class.getName() + ".startNanos";
  private static final Logger LOG = Logger.getLogger(RequestIdFilter.class);

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String requestId = requestContext.getHeaderString(REQUEST_ID_HEADER);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    MDC.put(TRACE_ID_MDC_KEY, requestId);
    requestContext.setProperty(TRACE_ID_MDC_KEY, requestId);
    requestContext.setProperty(START_NANOS_PROPERTY, System.nanoTime());
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    Object requestId = requestContext.getProperty(TRACE_ID_MDC_KEY);
    if (requestId != null) {
      responseContext.getHeaders().putSingle(REQUEST_ID_HEADER, requestId);
    }
    LOG.infof(
        "%s %s -> %d (%d ms)",
        requestContext.getMethod(),
        requestContext.getUriInfo().getRequestUri().getPath(),
        responseContext.getStatus(),
        elapsedMillis(requestContext));
    MDC.remove(TRACE_ID_MDC_KEY);
  }

  private static long elapsedMillis(ContainerRequestContext requestContext) {
    Object startNanos = requestContext.getProperty(START_NANOS_PROPERTY);
    return startNanos instanceof Long start ? (System.nanoTime() - start) / 1_000_000 : 0L;
  }
}
