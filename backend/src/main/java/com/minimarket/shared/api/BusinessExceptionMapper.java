package com.minimarket.shared.api;

import com.minimarket.shared.domain.BusinessException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Converte {@link BusinessException} e subclasses no problem+json padrão. */
@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(BusinessException exception) {
    return ProblemDetail.response(uriInfo, exception.code(), exception.getMessage(), null);
  }
}
