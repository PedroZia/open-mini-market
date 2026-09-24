package com.minimarket.shared.api;

import com.minimarket.shared.application.AccessDeniedEvent;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ForbiddenException;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Converte {@link BusinessException} e subclasses no problem+json padrão.
 *
 * <p>O 403 ({@link ForbiddenException}) também publica o {@link AccessDeniedEvent} (passo 309), que
 * o observer de {@code audit} grava como {@code ACCESS_DENIED} em transação própria — quando este
 * mapper roda, a transação do caso de uso já foi desfeita. O evento sai antes de montar a resposta
 * e sem {@code try/catch}: falha ao auditar sobe e derruba a requisição, como manda §7.1.
 */
@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {

  @Context UriInfo uriInfo;

  /** Método HTTP da requisição, um dos contextos padrão do JAX-RS. */
  @Context Request request;

  /**
   * Observado por {@code audit.application}; o {@code shared} não conhece o módulo de auditoria.
   */
  @Inject Event<AccessDeniedEvent> accessDeniedEvents;

  @Override
  public Response toResponse(BusinessException exception) {
    if (exception instanceof ForbiddenException forbidden) {
      accessDeniedEvents.fire(
          new AccessDeniedEvent(
              request.getMethod(), ProblemDetail.pathOf(uriInfo), forbidden.requiredPermission()));
    }
    return ProblemDetail.response(uriInfo, exception.code(), exception.getMessage(), null);
  }
}
