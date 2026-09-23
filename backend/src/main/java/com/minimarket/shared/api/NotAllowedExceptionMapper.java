package com.minimarket.shared.api;

import com.minimarket.shared.domain.ErrorCode;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Converte o 405 do próprio JAX-RS no problem+json padrão. */
@Provider
public class NotAllowedExceptionMapper implements ExceptionMapper<NotAllowedException> {

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(NotAllowedException exception) {
    return ProblemDetail.response(
        uriInfo, ErrorCode.METHOD_NOT_ALLOWED, "Método não permitido para este recurso", null);
  }
}
