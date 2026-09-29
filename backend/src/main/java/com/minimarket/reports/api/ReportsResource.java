package com.minimarket.reports.api;

import com.minimarket.reports.application.ListLowStockUseCase;
import com.minimarket.reports.application.ListSalesSummaryUseCase;
import com.minimarket.reports.application.LowStockItem;
import com.minimarket.reports.application.LowStockPage;
import com.minimarket.reports.application.SalesSummaryGroup;
import com.minimarket.reports.application.SalesSummaryView;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.QueryParams;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Relatórios de leitura (passo 1212a, §9.3): o resumo de vendas do período que alimenta o dashboard
 * e o relatório de estoque baixo. A API valida a forma, delega ao caso de uso e mapeia a resposta —
 * zero regra de negócio aqui.
 *
 * <p>As duas rotas exigem {@code report.read} (§4.5): sem a permissão o interceptor do {@code
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar — é o 403
 * do OPERADOR. Nenhum filtro usa o conversor implícito do JAX-RS, que devolveria 404 para um valor
 * inválido: o parse é explícito pelo {@link QueryParams} e o valor que não é ISO-8601 com offset
 * sai como 400 {@code VALIDATION_ERROR} com {@code errors[]} (passo 1007).
 */
@Path(ReportsResource.PATH)
public class ReportsResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/reports";

  @Inject ListSalesSummaryUseCase listSalesSummaryUseCase;

  @Inject ListLowStockUseCase listLowStockUseCase;

  /**
   * Resumo das vendas concluídas do período, agrupado por {@code day|operator|paymentMethod}. Os
   * três parâmetros são obrigatórios e o período é {@code from} inclusivo com {@code to} exclusivo
   * sobre o {@code completed_at}; {@code from} não anterior a {@code to} → 400. Parâmetro ausente,
   * {@code groupBy} fora da whitelist ou instante fora do ISO-8601 com offset → 400 {@code
   * VALIDATION_ERROR} com {@code errors[]} citando o campo.
   */
  @GET
  @Path("/sales-summary")
  @RequirePermission(Permission.REPORT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public SalesSummaryResponse salesSummary(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("groupBy") String groupBy) {
    SalesSummaryView summary =
        listSalesSummaryUseCase.execute(
            groupBy, QueryParams.instantOf(from, "from"), QueryParams.instantOf(to, "to"));
    return new SalesSummaryResponse(
        summary.from(),
        summary.to(),
        summary.groupBy().wireName(),
        summary.salesCount(),
        summary.total(),
        summary.ticketAverage(),
        summary.groups().stream().map(ReportsResource::toGroupResponse).toList());
  }

  /**
   * Relatório de estoque baixo paginado: os produtos com saldo no mínimo configurado ou abaixo, em
   * ordem de nome como a lista de estoque. {@code page} default 0 e {@code size} default 20 com
   * teto de 100; página negativa, {@code size} menor que 1 ou valor não numérico → 400 {@code
   * VALIDATION_ERROR} com {@code errors[]}.
   */
  @GET
  @Path("/low-stock")
  @RequirePermission(Permission.REPORT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<LowStockItemResponse> lowStock(
      @QueryParam("page") @DefaultValue("0") String page,
      @QueryParam("size") @DefaultValue("20") String size) {
    LowStockPage lowStock =
        listLowStockUseCase.execute(
            QueryParams.intOf(page, "page"), QueryParams.intOf(size, "size"));
    return new PageResponse<>(
        lowStock.items().stream().map(ReportsResource::toItemResponse).toList(),
        lowStock.page(),
        lowStock.size(),
        lowStock.totalItems(),
        lowStock.totalPages());
  }

  private static SalesSummaryGroupResponse toGroupResponse(SalesSummaryGroup group) {
    return new SalesSummaryGroupResponse(group.key(), group.salesCount(), group.total());
  }

  private static LowStockItemResponse toItemResponse(LowStockItem item) {
    return new LowStockItemResponse(
        item.productId(),
        item.name(),
        item.barcode(),
        item.unit(),
        item.quantity(),
        item.minQuantity());
  }
}
