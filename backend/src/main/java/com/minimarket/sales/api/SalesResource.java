package com.minimarket.sales.api;

import com.minimarket.sales.application.AddSaleItemCommand;
import com.minimarket.sales.application.AddSaleItemUseCase;
import com.minimarket.sales.application.ChangeSaleItemQuantityCommand;
import com.minimarket.sales.application.ChangeSaleItemQuantityUseCase;
import com.minimarket.sales.application.CreateSaleCommand;
import com.minimarket.sales.application.CreateSaleUseCase;
import com.minimarket.sales.application.RemoveSaleItemCommand;
import com.minimarket.sales.application.RemoveSaleItemUseCase;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Vendas (§9.3 do plano): a abertura da venda pela API/TUI (passo 807) e as operações de item —
 * inclusão, troca de quantidade e remoção (passo 809b). A API valida forma, delega ao caso de uso e
 * mapeia a resposta — zero regra de negócio aqui.
 *
 * <p>A abertura exige {@code sale.create} (BR-10): sem a permissão o interceptor do {@code
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar. As três
 * rotas de item exigem a mesma permissão: o §4.5 não define permissão de item — quem pode abrir
 * venda é quem pode operá-la — e a posse da venda é conferida pela guarda do caso de uso (BR-11,
 * §9.4), não pela permissão.
 *
 * <p><strong>Sem corpo de requisição na abertura.</strong> O {@code POST /sales} é o gesto "abrir
 * venda": caixa, operador e loja saem da sessão autenticada (§9.3) — o cliente não escolhe nenhum
 * deles (BR-06, BR-11) — e o número sai do alocador do servidor. Por isso a rota não declara
 * {@code @Consumes}: um POST sem corpo não deve exigir {@code Content-Type}. Se um dia houver
 * observações na abertura, elas entram no corpo; até lá não há contrato de entrada.
 *
 * <p>Abrir venda é operação idempotente por contrato (§8): o {@link IdempotencyGuard} exige o
 * header {@code Idempotency-Key} (sem ele, 400 {@code IDEMPOTENCY_KEY_REQUIRED}) e o retry com a
 * mesma chave devolve a resposta gravada com {@code Idempotency-Replayed: true}, sem abrir outra
 * venda. O payload é nulo — é ele que o guard hasheia, então duas chamadas da mesma sessão são
 * sempre a mesma requisição.
 *
 * <p>As operações de item <em>não</em> pedem {@code Idempotency-Key}: o §8 só a exige em {@code
 * POST /sales}, pagamentos, conclusão, cancelamento e operações de dinheiro, e repetir um item é um
 * gesto do operador (soma quantidade), não um retry de máquina. As três respondem
 * <strong>200</strong> com a {@link SaleDetailResponse}: a venda é o recurso da operação e os
 * totais vêm recalculados (BR-02, BR-12) sem uma segunda chamada. No path, {@code {itemId}} é o
 * <strong>productId</strong>: a identidade do item no agregado é o produto (decisão do 802) e o
 * nome do §9.3 foi mantido.
 *
 * <p>Sessão sem caixa vinculado é 403 e caixa sem sessão aberta é 409 {@code
 * CASH_SESSION_REQUIRED}, ambos do caso de uso. O 201 da abertura é <em>sem</em> {@code Location}:
 * a rota de leitura da venda só nasce no passo 812, e o corpo já traz o id — apontar para uma rota
 * inexistente seria um contrato falso.
 */
@Path(SalesResource.PATH)
public class SalesResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/sales";

  @Inject CreateSaleUseCase createSaleUseCase;

  /** Inclusão de item (passo 808): o bipe vira item na venda do caixa da sessão. */
  @Inject AddSaleItemUseCase addSaleItemUseCase;

  /** Troca de quantidade (passo 809a): a rota que a expõe nasce neste passo. */
  @Inject ChangeSaleItemQuantityUseCase changeSaleItemQuantityUseCase;

  /** Remoção de item (passo 809a): a rota que a expõe nasce neste passo. */
  @Inject RemoveSaleItemUseCase removeSaleItemUseCase;

  /** Idempotência da abertura (§8, passo 807): a chave identifica o gesto de abrir a venda. */
  @Inject IdempotencyGuard idempotencyGuard;

  /** O ator da requisição: o caixa vinculado e o operador saem daqui, não do cliente (BR-11). */
  @Inject OperationContext operationContext;

  /**
   * Abre a venda no caixa da sessão autenticada e devolve 201 com o cabeçalho da venda nascida. Sem
   * vínculo de caixa o caso de uso recusa com 403 e sem sessão de caixa aberta, 409 {@code
   * CASH_SESSION_REQUIRED} — a API não antecipa nenhuma das duas checagens.
   */
  @POST
  @RequirePermission(Permission.SALE_CREATE)
  @Produces(MediaType.APPLICATION_JSON)
  public Response create(@HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey) {
    return idempotencyGuard.execute(idempotencyKey, HttpMethod.POST, PATH, null, this::openSale);
  }

  /** Ação idempotente: abre a venda e monta o 201 com a projeção do caso de uso. */
  private Response openSale() {
    Sale sale =
        createSaleUseCase.execute(
            new CreateSaleCommand(operationContext.cashRegisterId(), operationContext.userId()));
    return Response.status(Response.Status.CREATED).entity(toResponse(sale)).build();
  }

  /**
   * Inclui o item na venda aberta do caixa da sessão (passo 809b) e devolve 200 com a venda
   * inteira. O produto vem do corpo — barcode <em>bruto</em> ou id — e quem o resolve é o caso de
   * uso (BR-14); o caixa da sessão sai do {@code OperationContext}, nunca do corpo (BR-11).
   *
   * <p>Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de outro caixa é 403 {@code
   * ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}; sem barcode e sem
   * produto é 400 {@code VALIDATION_ERROR}; produto inexistente é 404 {@code PRODUCT_NOT_FOUND} e
   * inativo, 422 {@code PRODUCT_INACTIVE} — todos do caso de uso, que a API não antecipa. Item do
   * produto que já está na venda é somado na mesma linha, mantendo o snapshot (BR-01).
   */
  @POST
  @Path("/{id}/items")
  @RequirePermission(Permission.SALE_CREATE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse addItem(@PathParam("id") UUID id, @Valid SaleItemRequest request) {
    Sale sale =
        addSaleItemUseCase.execute(
            new AddSaleItemCommand(
                id,
                operationContext.cashRegisterId(),
                request.barcode(),
                request.productId(),
                request.quantity()));
    return toDetailResponse(sale);
  }

  /**
   * Troca a quantidade do item da venda aberta (passo 809b) e devolve 200 com a venda inteira e os
   * totais recalculados. O {@code {itemId}} do path é o <strong>productId</strong> — a identidade
   * do item no agregado é o produto (decisão do 802) — e a quantidade do corpo é a absoluta, nunca
   * um delta.
   *
   * <p>Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de outro caixa é 403 {@code
   * ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}; produto que não está na
   * venda é 404 {@code SALE_ITEM_NOT_FOUND}; quantidade ausente ou não positiva é 400 {@code
   * VALIDATION_ERROR} — da forma, validada antes do caso de uso rodar.
   */
  @PATCH
  @Path("/{id}/items/{itemId}")
  @RequirePermission(Permission.SALE_CREATE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse changeItemQuantity(
      @PathParam("id") UUID id,
      @PathParam("itemId") UUID itemId,
      @Valid SaleItemQuantityRequest request) {
    Sale sale =
        changeSaleItemQuantityUseCase.execute(
            new ChangeSaleItemQuantityCommand(
                id, operationContext.cashRegisterId(), itemId, request.quantity()));
    return toDetailResponse(sale);
  }

  /**
   * Remove o item da venda aberta (passo 809b) e devolve 200 com a venda inteira e os totais
   * recalculados. O {@code {itemId}} do path é o <strong>productId</strong> — a identidade do item
   * no agregado é o produto (decisão do 802) — e o corpo é vazio: remover não recebe quantidade nem
   * motivo.
   *
   * <p>Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de outro caixa é 403 {@code
   * ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}; produto que não está na
   * venda é 404 {@code SALE_ITEM_NOT_FOUND} — todos do caso de uso.
   */
  @DELETE
  @Path("/{id}/items/{itemId}")
  @RequirePermission(Permission.SALE_CREATE)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse removeItem(@PathParam("id") UUID id, @PathParam("itemId") UUID itemId) {
    Sale sale =
        removeSaleItemUseCase.execute(
            new RemoveSaleItemCommand(id, operationContext.cashRegisterId(), itemId));
    return toDetailResponse(sale);
  }

  private static SaleResponse toResponse(Sale sale) {
    return new SaleResponse(
        sale.id(),
        sale.number(),
        sale.status(),
        sale.cashSessionId(),
        sale.cashRegisterId(),
        sale.operatorUserId(),
        sale.subtotal(),
        sale.discountAmount(),
        sale.total(),
        sale.itemCount(),
        sale.createdAt());
  }

  /** A venda inteira como as rotas de item a devolvem; os itens vêm na ordem de inclusão. */
  private static SaleDetailResponse toDetailResponse(Sale sale) {
    return new SaleDetailResponse(
        sale.id(),
        sale.number(),
        sale.status(),
        sale.cashSessionId(),
        sale.cashRegisterId(),
        sale.operatorUserId(),
        sale.customerId(),
        sale.subtotal(),
        sale.discountAmount(),
        sale.total(),
        sale.itemCount(),
        sale.createdAt(),
        sale.completedAt(),
        sale.items().stream().map(SalesResource::toItemResponse).toList());
  }

  /** Item com o snapshot do momento da inclusão (BR-01) — nada de entidade JPA em JSON. */
  private static SaleItemResponse toItemResponse(SaleItem item) {
    return new SaleItemResponse(
        item.productId(),
        item.barcode(),
        item.name(),
        item.unit(),
        item.unitPrice(),
        item.quantity(),
        item.lineTotal());
  }
}
