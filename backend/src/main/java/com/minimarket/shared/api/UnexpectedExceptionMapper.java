package com.minimarket.shared.api;

import com.minimarket.shared.domain.ErrorCode;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/** Último recurso: erro inesperado vira 500 sem vazar detalhe nem stack trace ao cliente. */
@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Exception> {

  private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(Exception exception) {
    if (exception instanceof WebApplicationException webApplicationException) {
      // exceção do próprio JAX-RS sem mapper específico: mantém o status original
      return webApplicationException.getResponse();
    }
    LOG.error("erro inesperado na API", exception);
    return ProblemDetail.response(
        uriInfo, ErrorCode.INTERNAL_ERROR, "Erro interno inesperado", null);
  }
}
