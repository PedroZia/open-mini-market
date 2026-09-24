package com.minimarket.inventory.api;

import com.minimarket.inventory.application.AdjustStockCommand;
import com.minimarket.inventory.application.AdjustStockUseCase;
import com.minimarket.inventory.application.AppliedStockAdjustment;
import com.minimarket.inventory.application.GetStockUseCase;
import com.minimarket.inventory.application.ListStockUseCase;
import com.minimarket.inventory.application.StockDetail;
import com.minimarket.inventory.application.StockItemSummary;
import com.minimarket.inventory.application.StockMovementSummary;
import com.minimarket.inventory.application.StockPage;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.UUID;

/**
 * Estoque (§9.3 do plano): leitura dos saldos com busca, filtro de estoque baixo e paginação (passo
 * 704) e o ajuste manual (passo 705). A API valida forma, delega ao caso de uso e mapeia a resposta
 * — zero regra de negócio aqui; o recebimento de mercadoria é do passo 706.
 *
 * <p>A leitura exige {@code stock.read} e o ajuste {@code stock.adjust}: sem a permissão o
 * interceptor do {@code RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do
 * método rodar. O detalhe de id desconhecido, de produto soft-deletado ou desativado responde 404
 * {@code PRODUCT_NOT_FOUND}; a listagem devolve o envelope {@link PageResponse} e produto sem
 * movimento aparece com saldo zero.
 */
@Path(StockResource.PATH)
public class StockResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/stock";

  @Inject ListStockUseCase listStockUseCase;

  @Inject GetStockUseCase getStockUseCase;

  /** Ajuste manual de estoque (passo 705): a rota que o expõe nasce neste passo. */
  @Inject AdjustStockUseCase adjustStockUseCase;

  /** Idempotência da operação de estoque (§8, passo 607a). */
  @Inject IdempotencyGuard idempotencyGuard;

  /** O ator da requisição: quem ajustou, gravado no ledger e no evento de auditoria. */
  @Inject OperationContext operationContext;

  @Context UriInfo uriInfo;

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

  /**
   * Ajuste manual de estoque (passo 705): corrige a divergência do saldo com rastro e devolve 201
   * com o movimento {@code ADJUSTMENT} gravado. Exige {@code stock.adjust} — sem a permissão o
   * interceptor responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar — e é operação
   * de estoque idempotente por contrato (§8, BR-13): o {@link IdempotencyGuard} exige o header
   * {@code Idempotency-Key} (sem ele, 400 {@code IDEMPOTENCY_KEY_REQUIRED}) e o retry com a mesma
   * chave devolve a resposta gravada com {@code Idempotency-Replayed: true}, sem ajustar de novo.
   *
   * <p>A forma é validada antes ({@code quantityDelta} ausente → 400; delta zero ou motivo vazio →
   * 400 {@code VALIDATION_ERROR} do caso de uso); produto inexistente ou soft-deletado é 404 {@code
   * PRODUCT_NOT_FOUND} e ajuste que deixaria o saldo negativo com a loja sem estoque negativo é 422
   * {@code INSUFFICIENT_STOCK} do {@link com.minimarket.inventory.application.StockService}. O
   * {@code Location} aponta para o detalhe do estoque (rota do passo 704), onde o efeito é visível.
   */
  @POST
  @Path("/{productId}/adjustments")
  @RequirePermission(Permission.STOCK_ADJUST)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response adjust(
      @PathParam("productId") UUID productId,
      @HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey,
      @Valid StockAdjustmentRequest request) {
    return idempotencyGuard.execute(
        idempotencyKey,
        HttpMethod.POST,
        adjustmentPath(productId),
        request,
        () -> adjustmentCreated(productId, adjustStock(productId, request)));
  }

  /** Ação idempotente: ajusta com o ator da requisição e monta o 201 com o {@code Location}. */
  private Response adjustmentCreated(UUID productId, AppliedStockAdjustment adjustment) {
    return Response.created(stockDetailLocation(productId))
        .entity(toAdjustmentResponse(adjustment))
        .build();
  }

  /** Ajusta com o ator da requisição: o delta e o motivo vêm do corpo validado. */
  private AppliedStockAdjustment adjustStock(UUID productId, StockAdjustmentRequest request) {
    return adjustStockUseCase.execute(
        new AdjustStockCommand(
            productId, request.quantityDelta(), request.reason(), operationContext.userId()));
  }

  /** O caminho concreto da requisição: é ele que a chave de idempotência identifica (§8). */
  private static String adjustmentPath(UUID productId) {
    return PATH + "/" + productId + "/adjustments";
  }

  /**
   * Onde o cliente lê o efeito do ajuste: o detalhe do estoque do produto (rota do passo 704), não
   * a rota do ajuste. O replay não regrava o {@code Location} — o corpo com o id é o que o cliente
   * precisa.
   */
  private URI stockDetailLocation(UUID productId) {
    return uriInfo.getBaseUriBuilder().path(PATH).path(productId.toString()).build();
  }

  private static StockAdjustmentResponse toAdjustmentResponse(AppliedStockAdjustment adjustment) {
    return new StockAdjustmentResponse(
        adjustment.movementId(),
        adjustment.productId(),
        adjustment.quantityDelta(),
        adjustment.balanceBefore(),
        adjustment.balanceAfter());
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
