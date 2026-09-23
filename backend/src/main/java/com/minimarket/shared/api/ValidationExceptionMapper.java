package com.minimarket.shared.api;

import com.minimarket.shared.domain.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;

/** Converte falhas de bean validation (corpo ou parâmetros) em 400 com {@code errors[]}. */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(ConstraintViolationException exception) {
    List<ProblemDetail.FieldError> errors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    new ProblemDetail.FieldError(fieldOf(violation), violation.getMessage()))
            .toList();
    return ProblemDetail.response(
        uriInfo, ErrorCode.VALIDATION_ERROR, "Um ou mais campos são inválidos", errors);
  }

  private static String fieldOf(ConstraintViolation<?> violation) {
    String field = "";
    for (Path.Node node : violation.getPropertyPath()) {
      field = node.getName();
    }
    return field;
  }
}
