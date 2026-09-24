package com.minimarket.cash.api;

import com.minimarket.cash.application.CashSessionSummary;
import com.minimarket.cash.application.CashSessionSummaryView;
import com.minimarket.cash.application.GetCashSessionSummaryUseCase;
import com.minimarket.cash.application.GetCashSessionUseCase;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

/**
 * Sessões de caixa (§9.3, passo 612): o detalhe e o resumo do fechamento, aberta ou fechada. A API
 * valida forma, delega ao caso de uso e mapeia a resposta — zero regra de negócio aqui.
 *
 * <p>As duas rotas são leitura e exigem {@code cash.read}: sem a permissão o interceptor do {@code
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar. Id
 * desconhecido é 404 {@code CASH_SESSION_NOT_FOUND}, pelo caso de uso.
 */
@Path(CashSessionsResource.PATH)
public class CashSessionsResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/cash-sessions";

  @Inject GetCashSessionUseCase getCashSessionUseCase;

  @Inject GetCashSessionSummaryUseCase getCashSessionSummaryUseCase;

  /**
   * Detalhe da sessão pelo id: a abertura e, quando fechada, a conferência do fechamento. Leitura,
   * sem idempotência.
   */
  @GET
  @Path("/{id}")
  @RequirePermission(Permission.CASH_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public CashSessionDetailResponse detail(@PathParam("id") UUID id) {
    return toDetailResponse(getCashSessionUseCase.execute(id));
  }

  /**
   * Resumo do fechamento: esperado × contado com os totais por tipo de movimento, pela mesma conta
   * da sessão atual (passo 608) — o esperado é do servidor, nunca do cliente (BR-12).
   */
  @GET
  @Path("/{id}/summary")
  @RequirePermission(Permission.CASH_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public CashSessionSummaryResponse summary(@PathParam("id") UUID id) {
    return toSummaryResponse(getCashSessionSummaryUseCase.execute(id));
  }

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

  private static CashSessionSummaryResponse toSummaryResponse(CashSessionSummaryView summary) {
    return new CashSessionSummaryResponse(
        summary.sessionId(),
        summary.status(),
        summary.openingAmount(),
        summary.expectedAmount(),
        summary.countedAmount(),
        summary.differenceAmount(),
        summary.totalsByType());
  }
}
