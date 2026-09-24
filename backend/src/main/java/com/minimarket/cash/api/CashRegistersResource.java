package com.minimarket.cash.api;

import com.minimarket.cash.application.CashRegisterView;
import com.minimarket.cash.application.CashSessionSummary;
import com.minimarket.cash.application.CurrentCashSessionView;
import com.minimarket.cash.application.GetCurrentCashSessionUseCase;
import com.minimarket.cash.application.ListCashRegistersUseCase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Caixas físicos (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta —
 * zero regra de negócio aqui.
 *
 * <p>A leitura exige {@code cash.read} (a TUI escolhe o caixa no login) e a abertura exige {@code
 * cash.open}: sem a permissão o interceptor do {@code RequirePermission} responde 403 {@code
 * ACCESS_DENIED} antes de o corpo do método rodar.
 *
 * <p>A listagem é um array simples, sem paginação: o §9.3 não define {@code page}/{@code size} para
 * esta rota e são poucos caixas por loja — a ordenação por código vem do repositório.
 */
@Path(CashRegistersResource.PATH)
public class CashRegistersResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/cash-registers";

  @Inject ListCashRegistersUseCase listCashRegistersUseCase;

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject GetCurrentCashSessionUseCase getCurrentCashSessionUseCase;

  /** Idempotência da operação de dinheiro (§8, passo 607a). */
  @Inject IdempotencyGuard idempotencyGuard;

  /** O ator da requisição: quem abriu o caixa e a sessão autenticada a vincular (passo 606). */
  @Inject OperationContext operationContext;

  @Context UriInfo uriInfo;

  /** Caixas ativos, cada um com o status da sessão atual e o operador dela. */
  @GET
  @RequirePermission(Permission.CASH_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public List<CashRegisterResponse> list() {
    return listCashRegistersUseCase.execute().stream()
        .map(CashRegistersResource::toResponse)
        .toList();
  }

  /**
   * Abre o caixa e devolve 201 com a sessão criada; o {@code Location} aponta para a sessão atual
   * do caixa (§9.3, rota que nasce no passo 608). O fundo de troco é validado na forma ({@code
   * openingAmount} ausente ou negativo → 400 {@code VALIDATION_ERROR}) e de novo pelo caso de uso,
   * que é o backstop.
   *
   * <p>Abertura de caixa é operação de dinheiro e idempotente por contrato (§8): o {@link
   * IdempotencyGuard} exige o header {@code Idempotency-Key} (sem ele, 400 {@code
   * IDEMPOTENCY_KEY_REQUIRED}) e o retry com a mesma chave devolve a resposta gravada com {@code
   * Idempotency-Replayed: true}, sem reabrir o caixa. Chave nova com o caixa já aberto é 409 {@code
   * CASH_REGISTER_ALREADY_OPEN} do caso de uso; caixa inexistente ou inativo é 404 {@code
   * CASH_REGISTER_NOT_FOUND}.
   *
   * <p>O ator sai do {@link OperationContext}: o caso de uso grava quem abriu e vincula a sessão
   * autenticada ao caixa, na mesma transação da abertura.
   */
  @POST
  @Path("/{id}/open")
  @RequirePermission(Permission.CASH_OPEN)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response open(
      @PathParam("id") UUID id,
      @HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey,
      @Valid OpenCashSessionRequest request) {
    return idempotencyGuard.execute(
        idempotencyKey, HttpMethod.POST, openPath(id), request, () -> openSession(id, request));
  }

  /**
   * Sessão atual do caixa (§9.3): a TUI usa com o caixa aberto para mostrar os totais por tipo de
   * movimento e o saldo esperado, recalculado pelo servidor (BR-12). Leitura, sem idempotência.
   * Caixa sem sessão aberta — inclusive registro inexistente — é 404 {@code CASH_SESSION_NOT_OPEN};
   * sem {@code cash.read} o interceptor responde 403 antes de o corpo do método rodar.
   */
  @GET
  @Path("/{id}/current-session")
  @RequirePermission(Permission.CASH_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public CurrentCashSessionResponse currentSession(@PathParam("id") UUID id) {
    return toCurrentSessionResponse(getCurrentCashSessionUseCase.execute(id));
  }

  /** Ação idempotente: abre a sessão e monta o 201 com o {@code Location} da sessão atual. */
  private Response openSession(UUID id, OpenCashSessionRequest request) {
    CashSessionSummary session =
        openCashSessionUseCase.execute(
            new OpenCashSessionCommand(
                id,
                request.openingAmount(),
                operationContext.userId(),
                operationContext.authSessionId()));
    return Response.created(currentSessionLocation(id)).entity(toSessionResponse(session)).build();
  }

  /** O caminho concreto da requisição: é ele que a chave de idempotência identifica (§8). */
  private static String openPath(UUID cashRegisterId) {
    return PATH + "/" + cashRegisterId + "/open";
  }

  /**
   * Onde o cliente lê a sessão recém-aberta: a rota da sessão atual do caixa (passo 608), não a da
   * abertura. O replay não regrava o {@code Location} — o corpo com o id é o que o cliente precisa.
   */
  private URI currentSessionLocation(UUID cashRegisterId) {
    return uriInfo
        .getBaseUriBuilder()
        .path(PATH)
        .path(cashRegisterId.toString())
        .path("current-session")
        .build();
  }

  private static CashRegisterResponse toResponse(CashRegisterView register) {
    return new CashRegisterResponse(
        register.id(),
        register.code(),
        register.name(),
        register.status(),
        register.operatorName());
  }

  private static CashSessionResponse toSessionResponse(CashSessionSummary session) {
    return new CashSessionResponse(
        session.id(),
        session.cashRegisterId(),
        session.status(),
        session.openedAt(),
        session.openedByUserId(),
        session.openingAmount());
  }

  private static CurrentCashSessionResponse toCurrentSessionResponse(
      CurrentCashSessionView session) {
    return new CurrentCashSessionResponse(
        session.sessionId(),
        session.cashRegisterId(),
        session.status(),
        session.openedAt(),
        session.openedByUserId(),
        session.openingAmount(),
        session.expectedAmount(),
        session.totalsByType());
  }
}
