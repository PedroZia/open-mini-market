package com.minimarket.cash.api;

import com.minimarket.cash.application.CashMovementResult;
import com.minimarket.cash.application.CashRegisterView;
import com.minimarket.cash.application.CashSessionSummary;
import com.minimarket.cash.application.CloseCashSessionCommand;
import com.minimarket.cash.application.CloseCashSessionUseCase;
import com.minimarket.cash.application.CurrentCashSessionView;
import com.minimarket.cash.application.GetCurrentCashSessionUseCase;
import com.minimarket.cash.application.ListCashRegistersUseCase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.cash.application.RecordSupplyCommand;
import com.minimarket.cash.application.RecordSupplyUseCase;
import com.minimarket.cash.application.RecordWithdrawalCommand;
import com.minimarket.cash.application.RecordWithdrawalUseCase;
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
 * <p>A leitura exige {@code cash.read} (a TUI escolhe o caixa no login), a abertura {@code
 * cash.open} e os movimentos de dinheiro as permissões de cada um — {@code cash.withdrawal} na
 * sangria e {@code cash.supply} no suprimento (BR-10): sem a permissão o interceptor do {@code
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar.
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

  /** Fechamento com conferência (passo 611): a rota que o expõe é do passo 612. */
  @Inject CloseCashSessionUseCase closeCashSessionUseCase;

  @Inject GetCurrentCashSessionUseCase getCurrentCashSessionUseCase;

  /** Sangria (passo 609) e suprimento (passo 610): os dois movimentos de dinheiro do caixa. */
  @Inject RecordWithdrawalUseCase recordWithdrawalUseCase;

  @Inject RecordSupplyUseCase recordSupplyUseCase;

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
   * Fecha o caixa com a conferência do operador e devolve 200 com o detalhe da sessão fechada: é
   * atualização de estado, não criação (§9.1). Exige {@code cash.close} — sem a permissão o
   * interceptor responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar — e é operação
   * de dinheiro idempotente por contrato (§8): o {@link IdempotencyGuard} exige o header {@code
   * Idempotency-Key} (sem ele, 400 {@code IDEMPOTENCY_KEY_REQUIRED}) e o retry com a mesma chave
   * devolve a resposta gravada com {@code Idempotency-Replayed: true}, sem fechar de novo.
   *
   * <p>A forma é validada antes ({@code countedAmount} ausente ou negativo → 400 {@code
   * VALIDATION_ERROR}); caixa sem sessão aberta é 409 {@code CASH_SESSION_ALREADY_CLOSED} — a
   * segunda chamada com chave nova cai no mesmo conflito de estado. Quem calcula o esperado e a
   * diferença é o caso de uso (passo 611), nunca o cliente (BR-12).
   */
  @POST
  @Path("/{id}/close")
  @RequirePermission(Permission.CASH_CLOSE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response close(
      @PathParam("id") UUID id,
      @HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey,
      @Valid CloseCashSessionRequest request) {
    return idempotencyGuard.execute(
        idempotencyKey,
        HttpMethod.POST,
        closePath(id),
        request,
        () -> Response.ok(toDetailResponse(closeSession(id, request))).build());
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

  /**
   * Sangria: retira dinheiro da sessão aberta do caixa (passo 609) e devolve 201 com o movimento
   * gravado. Exige {@code cash.withdrawal} — sem a permissão o interceptor responde 403 {@code
   * ACCESS_DENIED} antes de o corpo do método rodar — e é operação de dinheiro idempotente por
   * contrato (§8): o {@link IdempotencyGuard} exige o header {@code Idempotency-Key} (sem ele, 400
   * {@code IDEMPOTENCY_KEY_REQUIRED}) e o retry com a mesma chave devolve a resposta gravada com
   * {@code Idempotency-Replayed: true}, sem sangrar de novo.
   *
   * <p>A forma é validada antes ({@code amount} ausente/não positivo ou motivo vazio → 400 {@code
   * VALIDATION_ERROR}); caixa sem sessão aberta é 404 {@code CASH_SESSION_NOT_OPEN} e sangria acima
   * do esperado <em>não</em> bloqueia: devolve 201 com {@code aboveExpected = true} para o cliente
   * alertar o operador. O {@code Location} aponta para a sessão atual do caixa (passo 608), onde o
   * efeito do movimento aparece.
   */
  @POST
  @Path("/{id}/withdrawals")
  @RequirePermission(Permission.CASH_WITHDRAWAL)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response withdraw(
      @PathParam("id") UUID id,
      @HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey,
      @Valid CashMovementRequest request) {
    return idempotencyGuard.execute(
        idempotencyKey,
        HttpMethod.POST,
        PATH + "/" + id + "/withdrawals",
        request,
        () -> movementCreated(id, recordWithdrawal(id, request)));
  }

  /**
   * Suprimento: coloca dinheiro na sessão aberta do caixa e devolve 201 com o movimento gravado.
   * Exige {@code cash.supply} — o OPERADOR tem {@code cash.open}/{@code cash.close}, mas suprir é
   * do gerente (seed da V3) — e segue o mesmo contrato de idempotência da sangria: header {@code
   * Idempotency-Key} obrigatório e retry com a mesma chave devolve a resposta gravada, sem suprir
   * de novo.
   *
   * <p>Valor positivo e motivo obrigatório na forma; caixa sem sessão aberta é 404 {@code
   * CASH_SESSION_NOT_OPEN}. O {@code aboveExpected} do corpo é sempre {@code false}: o alerta é só
   * da sangria (o suprimento só aumenta o esperado).
   */
  @POST
  @Path("/{id}/supplies")
  @RequirePermission(Permission.CASH_SUPPLY)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response supply(
      @PathParam("id") UUID id,
      @HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey,
      @Valid CashMovementRequest request) {
    return idempotencyGuard.execute(
        idempotencyKey,
        HttpMethod.POST,
        PATH + "/" + id + "/supplies",
        request,
        () -> movementCreated(id, recordSupply(id, request)));
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

  /** Fecha a sessão com o ator da requisição: o contado e as observações vêm do corpo validado. */
  private CashSessionSummary closeSession(UUID id, CloseCashSessionRequest request) {
    return closeCashSessionUseCase.execute(
        new CloseCashSessionCommand(
            id, request.countedAmount(), request.notes(), operationContext.userId()));
  }

  /** Sangra com o ator da requisição: o motivo e o valor vêm do corpo validado. */
  private CashMovementResult recordWithdrawal(UUID id, CashMovementRequest request) {
    return recordWithdrawalUseCase.execute(
        new RecordWithdrawalCommand(
            id, request.amount(), request.reason(), operationContext.userId()));
  }

  /** Suprimento com o ator da requisição, no mesmo formato da sangria. */
  private CashMovementResult recordSupply(UUID id, CashMovementRequest request) {
    return recordSupplyUseCase.execute(
        new RecordSupplyCommand(id, request.amount(), request.reason(), operationContext.userId()));
  }

  /**
   * 201 do movimento: o corpo é a projeção do caso de uso e o {@code Location} aponta para onde o
   * efeito é visível — a sessão atual do caixa (rota do passo 608); o movimento em si não tem GET
   * no §9.3 (o histórico sai do resumo do passo 612).
   */
  private Response movementCreated(UUID cashRegisterId, CashMovementResult movement) {
    return Response.created(currentSessionLocation(cashRegisterId))
        .entity(toMovementResponse(movement))
        .build();
  }

  /** O caminho concreto da requisição: é ele que a chave de idempotência identifica (§8). */
  private static String openPath(UUID cashRegisterId) {
    return PATH + "/" + cashRegisterId + "/open";
  }

  /** O caminho concreto do fechamento, pelo mesmo motivo do {@link #openPath}. */
  private static String closePath(UUID cashRegisterId) {
    return PATH + "/" + cashRegisterId + "/close";
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

  /** Detalhe da sessão fechada (passo 612): abertura + conferência, sem {@code storeId}/version. */
  private static CashSessionDetailResponse toDetailResponse(CashSessionSummary session) {
    return new CashSessionDetailResponse(
        session.id(),
        session.cashRegisterId(),
        session.status(),
        session.openedAt(),
        session.openedByUserId(),
        session.openingAmount(),
        session.closedAt(),
        session.closedByUserId(),
        session.countedAmount(),
        session.expectedAmount(),
        session.differenceAmount(),
        session.closingNotes());
  }

  private static CashMovementResponse toMovementResponse(CashMovementResult movement) {
    return new CashMovementResponse(
        movement.sessionId(),
        movement.type(),
        movement.amount(),
        movement.reason(),
        movement.expectedBefore(),
        movement.expectedAfter(),
        movement.aboveExpected());
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
