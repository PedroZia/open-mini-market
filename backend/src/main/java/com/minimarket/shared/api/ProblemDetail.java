package com.minimarket.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;
import org.jboss.logmanager.MDC;

/** Corpo padrão de erro da API, no formato RFC 9457 (§9.2 do plano). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String traceId,
    List<FieldError> errors) {

  /** Item de {@code errors[]}, usado nos erros de validação. */
  public record FieldError(String field, String message) {}

  /** Monta a resposta {@code application/problem+json} do erro. */
  public static Response response(
      UriInfo uriInfo, ErrorCode errorCode, String detail, List<FieldError> errors) {
    return Response.status(errorCode.status())
        .type("application/problem+json")
        .entity(
            new ProblemDetail(
                errorCode.type(),
                errorCode.title(),
                errorCode.status(),
                detail,
                pathOf(uriInfo),
                errorCode.name(),
                currentTraceId(),
                errors))
        .build();
  }

  /** Caminho da requisição; o mesmo que o evento de acesso negado leva como rota (passo 309). */
  static String pathOf(UriInfo uriInfo) {
    return uriInfo == null ? null : uriInfo.getRequestUri().getPath();
  }

  /** Correlação da requisição; o MDC é populado pelo {@link RequestIdFilter} a cada request. */
  private static String currentTraceId() {
    String traceId = MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
    return traceId != null ? traceId : UUID.randomUUID().toString();
  }
}
