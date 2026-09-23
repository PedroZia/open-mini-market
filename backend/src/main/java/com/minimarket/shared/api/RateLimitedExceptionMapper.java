package com.minimarket.shared.api;

import com.minimarket.shared.domain.RateLimitedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Converte {@link RateLimitedException} no problem+json padrão acrescido do header {@code
 * Retry-After} em segundos, como o 429 do §9.2 exige. O mapper mais específico vence o de {@code
 * BusinessException}: sem ele o cliente não saberia quando tentar de novo.
 */
@Provider
public class RateLimitedExceptionMapper implements ExceptionMapper<RateLimitedException> {

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(RateLimitedException exception) {
    return Response.fromResponse(
            ProblemDetail.response(uriInfo, exception.code(), exception.getMessage(), null))
        .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds())
        .build();
  }
}
