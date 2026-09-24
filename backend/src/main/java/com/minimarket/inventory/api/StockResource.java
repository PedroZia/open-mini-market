package com.minimarket.inventory.api;

import com.minimarket.inventory.application.GetStockUseCase;
import com.minimarket.inventory.application.ListStockUseCase;
import com.minimarket.inventory.application.StockDetail;
import com.minimarket.inventory.application.StockItemSummary;
import com.minimarket.inventory.application.StockMovementSummary;
import com.minimarket.inventory.application.StockPage;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

/**
 * Estoque (§9.3 do plano): leitura dos saldos com busca, filtro de estoque baixo e paginação (passo
 * 704). A API valida forma, delega ao caso de uso e mapeia a resposta — zero regra de negócio aqui;
 * a escrita (ajuste e recebimento) é dos passos 705/706.
 *
 * <p>A leitura exige {@code stock.read}: sem a permissão o interceptor do {@code RequirePermission}
 * responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar. O detalhe de id
 * desconhecido, de produto soft-deletado ou desativado responde 404 {@code PRODUCT_NOT_FOUND}; a
 * listagem devolve o envelope {@link PageResponse} e produto sem movimento aparece com saldo zero.
 */
@Path(StockResource.PATH)
public class StockResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/stock";

  @Inject ListStockUseCase listStockUseCase;

  @Inject GetStockUseCase getStockUseCase;

  /**
   * Lista paginada dos saldos da loja: busca por trecho do nome (sem diferenciar maiúsculas) ou
   * barcode exato e filtro de estoque baixo (§9.3). {@code lowStock} ausente = sem filtro; {@code
   * size} acima de 100 é limitado; parâmetro de paginação fora da regra → 400 {@code
   * VALIDATION_ERROR} do caso de uso.
   */
  @GET
  @RequirePermission(Permission.STOCK_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<StockItemResponse> list(
      @QueryParam("search") String search,
      @QueryParam("lowStock") Boolean lowStock,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size) {
    StockPage stock = listStockUseCase.execute(search, lowStock, page, size);
    return new PageResponse<>(
        stock.items().stream().map(StockResource::toItemResponse).toList(),
        stock.page(),
        stock.size(),
        stock.totalItems(),
        stock.totalPages());
  }

  /**
   * Detalhe do estoque do produto: saldo da loja e últimos vinte movimentos do ledger, do mais
   * recente para o mais antigo. Id inexistente ou produto soft-deletado/desativado → 404 {@code
   * PRODUCT_NOT_FOUND}; produto vivo sem movimento → 200 com saldo zero e movimentos vazios.
   */
  @GET
  @Path("/{productId}")
  @RequirePermission(Permission.STOCK_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public StockDetailResponse get(@PathParam("productId") UUID productId) {
    StockDetail detail = getStockUseCase.execute(productId);
    return new StockDetailResponse(
        detail.item().productId(),
        detail.item().name(),
        detail.item().barcode(),
        detail.item().unit(),
        detail.item().quantity(),
        detail.item().minQuantity(),
        detail.item().lowStock(),
        detail.movements().stream().map(StockResource::toMovementResponse).toList());
  }

  private static StockItemResponse toItemResponse(StockItemSummary item) {
    return new StockItemResponse(
        item.productId(),
        item.name(),
        item.barcode(),
        item.unit(),
        item.quantity(),
        item.minQuantity(),
        item.lowStock());
  }

  private static StockMovementResponse toMovementResponse(StockMovementSummary movement) {
    return new StockMovementResponse(
        movement.id(),
        movement.type(),
        movement.quantityDelta(),
        movement.balanceAfter(),
        movement.unitCost(),
        movement.referenceType(),
        movement.referenceId(),
        movement.reason(),
        movement.createdByUserId(),
        movement.createdAt());
  }
}
