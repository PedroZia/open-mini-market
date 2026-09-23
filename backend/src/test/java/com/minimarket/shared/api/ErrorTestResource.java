package com.minimarket.shared.api;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/** Recurso só de teste: força cada erro mapeado sem depender dos módulos de negócio. */
@Path("/test/errors")
public class ErrorTestResource {

  @GET
  @Path("/not-found")
  public String notFound() {
    throw new NotFoundException("o recurso 42 não existe");
  }

  @GET
  @Path("/conflict")
  public String conflict() {
    throw new ConflictException("o recurso já está concluído");
  }

  @GET
  @Path("/business")
  public String business() {
    throw new BusinessException(ErrorCode.BUSINESS_ERROR, "a regra X foi violada");
  }

  @GET
  @Path("/boom")
  public String boom() {
    throw new IllegalStateException("detalhe interno que não pode vazar");
  }

  @POST
  @Path("/validation")
  public String validation(@Valid ValidationRequest request) {
    return "ok";
  }

  /** Corpo usado para forçar erro de validação (400). */
  public record ValidationRequest(@NotBlank(message = "não pode ser vazio") String name) {}
}
