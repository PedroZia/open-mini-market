package com.minimarket.shared.api;

import com.minimarket.shared.domain.ErrorCode;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Converte o 404 do próprio JAX-RS (rota inexistente) no problem+json padrão. */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(NotFoundException exception) {
    return ProblemDetail.response(
        uriInfo, ErrorCode.NOT_FOUND, "Nenhum recurso corresponde a este caminho", null);
  }
}
