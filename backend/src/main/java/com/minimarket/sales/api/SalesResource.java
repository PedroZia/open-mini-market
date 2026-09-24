package com.minimarket.sales.api;

import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.sales.application.AddSaleItemCommand;
import com.minimarket.sales.application.AddSaleItemUseCase;
import com.minimarket.sales.application.ApplyDiscountCommand;
import com.minimarket.sales.application.ApplyDiscountUseCase;
import com.minimarket.sales.application.CancelSaleCommand;
import com.minimarket.sales.application.CancelSaleUseCase;
import com.minimarket.sales.application.ChangeSaleItemQuantityCommand;
import com.minimarket.sales.application.ChangeSaleItemQuantityUseCase;
import com.minimarket.sales.application.CreateSaleCommand;
import com.minimarket.sales.application.CreateSaleUseCase;
import com.minimarket.sales.application.GetSaleCommand;
import com.minimarket.sales.application.GetSaleUseCase;
import com.minimarket.sales.application.LinkCustomerCommand;
import com.minimarket.sales.application.LinkCustomerUseCase;
import com.minimarket.sales.application.ListSalesQuery;
import com.minimarket.sales.application.ListSalesUseCase;
import com.minimarket.sales.application.RemoveDiscountCommand;
import com.minimarket.sales.application.RemoveDiscountUseCase;
import com.minimarket.sales.application.RemoveSaleItemCommand;
import com.minimarket.sales.application.RemoveSaleItemUseCase;
import com.minimarket.sales.application.SalePage;
import com.minimarket.sales.application.SaleSummary;
import com.minimarket.sales.application.UnlinkCustomerCommand;
import com.minimarket.sales.application.UnlinkCustomerUseCase;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.Permission;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
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
 *
 * <p>O desconto e o cliente (passo 811b) seguem o mesmo desenho: {@code PUT} aplica e {@code
 * DELETE} tira, e os quatro respondem 200 com a {@link SaleDetailResponse} recalculada (BR-02,
 * BR-12). As rotas de desconto exigem {@code sale.discount.apply} (BR-04) — desfazer um desconto é
 * tão sensível quanto concedê-lo, a mesma escolha do caso de uso do 810 — e as de cliente, {@code
 * sale.create}: vincular cliente é operar a venda, como incluir item. Nenhuma das quatro pede
 * {@code Idempotency-Key} (§8 só a exige em {@code POST /sales}, pagamentos, conclusão,
 * cancelamento e dinheiro): repetir o gesto é do operador, e o {@code DELETE} sem o que tirar é
 * no-op (200, sem evento).
 *
 * <p>O cancelamento (passo 813) é o outro {@code POST} idempotente da venda: exige {@code
 * sale.cancel} e o header {@code Idempotency-Key} (§8) e devolve 200 com a venda cancelada —
 * status, motivo, autor e instante no mesmo {@link SaleDetailResponse} das demais operações. Só a
 * venda {@code OPEN} transita: a já cancelada é no-op (200, sem evento) e a concluída, 409 {@code
 * SALE_ALREADY_COMPLETED}.
 *
 * <p>A consulta (passo 812) tem duas rotas: o detalhe {@code GET /sales/{id}} devolve a venda
 * inteira — o mesmo {@link SaleDetailResponse} das operações acima, com itens, desconto e cliente —
 * e o histórico {@code GET /sales} devolve a página do envelope padrão. O detalhe <em>não</em> usa
 * {@code @RequirePermission}: o §4.5 não tem permissão de leitura de venda e a posse (BR-11, §9.4)
 * é de quem opera o caixa; a permissão de gestão {@code report.read} entra como bypass — resolvida
 * aqui pelo {@link AuthorizationService} e passada ao caso de uso, como o 305 faz com o porteiro
 * declarativo. Assim o OPERADOR lê a venda do seu caixa e recebe 403 {@code ACCESS_DENIED} na do
 * outro, enquanto o GERENTE lê qualquer uma. O histórico exige {@code report.read} na rota: é visão
 * de loja, não do caixa. Pagamentos ficam na Fase 9 — a tabela ainda não existe — então o detalhe
 * não tem {@code paidAmount}/{@code changeAmount}.
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

  /** Desconto da venda (passo 810): a rota que o expõe nasce no 811b. */
  @Inject ApplyDiscountUseCase applyDiscountUseCase;

  /** Remoção do desconto (passo 810): a rota que a expõe nasce no 811b. */
  @Inject RemoveDiscountUseCase removeDiscountUseCase;

  /** Vínculo de cliente (passo 811a): a rota que o expõe nasce neste passo. */
  @Inject LinkCustomerUseCase linkCustomerUseCase;

  /** Desvínculo de cliente (passo 811a): a rota que o expõe nasce neste passo. */
  @Inject UnlinkCustomerUseCase unlinkCustomerUseCase;

  /** Cancelamento da venda aberta (passo 813): a rota que o expõe nasce neste passo. */
  @Inject CancelSaleUseCase cancelSaleUseCase;

  /** Idempotência da abertura (§8, passo 807): a chave identifica o gesto de abrir a venda. */
  @Inject IdempotencyGuard idempotencyGuard;

  /** Consulta de venda (passo 812): a rota que a expõe nasce neste passo. */
  @Inject GetSaleUseCase getSaleUseCase;

  /** Histórico de vendas (passo 812): a rota que o expõe nasce neste passo. */
  @Inject ListSalesUseCase listSalesUseCase;

  /** O ator da requisição: o caixa vinculado e o operador saem daqui, não do cliente (BR-11). */
  @Inject OperationContext operationContext;

  /**
   * Permissões efetivas da sessão (passo 305): é daqui que sai o bypass de gestão do detalhe — a
   * leitura não passa pelo porteiro declarativo, então quem pergunta é o resource.
   */
  @Inject AuthorizationService authorizationService;

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

  /**
   * Aplica o desconto na venda aberta (passo 811b) e devolve 200 com a venda inteira e o total
   * recalculado. O tipo e o valor chegam como o operador os digitou e quem calcula o desconto e o
   * total é o servidor (BR-03, BR-12); o motivo é obrigatório (BR-04).
   *
   * <p>Sem {@code sale.discount.apply} o interceptor do {@code RequirePermission} responde 403
   * {@code ACCESS_DENIED} antes de o corpo do método rodar — é o que separa o OPERADOR (que opera a
   * venda, mas não desconta) do GERENTE. Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de
   * outro caixa é 403 {@code ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code
   * SALE_NOT_OPEN}; acima do limite da loja é 422 {@code DISCOUNT_LIMIT_EXCEEDED}. Tipo, valor ou
   * motivo ausentes são 400 {@code VALIDATION_ERROR} da forma, validada antes do caso de uso — que
   * repete as três checagens como backstop.
   */
  @PUT
  @Path("/{id}/discount")
  @RequirePermission(Permission.SALE_DISCOUNT_APPLY)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse applyDiscount(
      @PathParam("id") UUID id, @Valid SaleDiscountRequest request) {
    Sale sale =
        applyDiscountUseCase.execute(
            new ApplyDiscountCommand(
                id,
                operationContext.cashRegisterId(),
                request.type(),
                request.value(),
                request.reason()));
    return toDetailResponse(sale);
  }

  /**
   * Tira o desconto da venda aberta (passo 811b) e devolve 200 com a venda inteira e o total de
   * volta ao subtotal (BR-02/BR-03). Exige a mesma permissão de quem aplica, {@code
   * sale.discount.apply}: desfazer um desconto é tão sensível quanto concedê-lo.
   *
   * <p>Venda sem desconto é no-op (200, sem evento), então repetir o {@code DELETE} é inofensivo.
   * Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de outro caixa é 403 {@code
   * ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN} — todos do caso de uso.
   */
  @DELETE
  @Path("/{id}/discount")
  @RequirePermission(Permission.SALE_DISCOUNT_APPLY)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse removeDiscount(@PathParam("id") UUID id) {
    Sale sale =
        removeDiscountUseCase.execute(
            new RemoveDiscountCommand(id, operationContext.cashRegisterId()));
    return toDetailResponse(sale);
  }

  /**
   * Vincula o cliente à venda aberta (passo 811b) e devolve 200 com a venda inteira. A venda guarda
   * um cliente por vez — vincular outro substitui o anterior — e o cliente precisa estar ativo.
   *
   * <p>Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de outro caixa é 403 {@code
   * ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN} — do caso de uso; {@code
   * customerId} ausente é 400 {@code VALIDATION_ERROR} da forma; cliente inexistente é 404 {@code
   * CUSTOMER_NOT_FOUND} e desativado, 422 {@code CUSTOMER_INACTIVE}.
   */
  @PUT
  @Path("/{id}/customer")
  @RequirePermission(Permission.SALE_CREATE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse linkCustomer(
      @PathParam("id") UUID id, @Valid SaleCustomerRequest request) {
    Sale sale =
        linkCustomerUseCase.execute(
            new LinkCustomerCommand(id, operationContext.cashRegisterId(), request.customerId()));
    return toDetailResponse(sale);
  }

  /**
   * Desvincula o cliente da venda aberta (passo 811b) e devolve 200 com a venda anônima. Venda sem
   * cliente vinculado é no-op (200, sem evento), então repetir o {@code DELETE} é inofensivo.
   *
   * <p>Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda de outro caixa é 403 {@code
   * ACCESS_DENIED}; venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN} — todos do caso de uso.
   */
  @DELETE
  @Path("/{id}/customer")
  @RequirePermission(Permission.SALE_CREATE)
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse unlinkCustomer(@PathParam("id") UUID id) {
    Sale sale =
        unlinkCustomerUseCase.execute(
            new UnlinkCustomerCommand(id, operationContext.cashRegisterId()));
    return toDetailResponse(sale);
  }

  /**
   * Cancela a venda aberta do caixa da sessão (passo 813) e devolve 200 com a venda cancelada —
   * status {@code CANCELLED}, motivo, autor e instante. Desistir não apaga nada: a venda e os itens
   * ficam no histórico.
   *
   * <p>Exige {@code sale.cancel} (BR-04/§4.5): o OPERADOR opera a venda, mas não a cancela — sem a
   * permissão o interceptor do {@code RequirePermission} responde 403 {@code ACCESS_DENIED} antes
   * de o corpo do método rodar, e o caso de uso repete a checagem como backstop. A posse é da
   * guarda (BR-11, §9.4), como nas operações de item, desconto e cliente: venda de outro caixa é
   * 403 {@code ACCESS_DENIED} e venda inexistente, 404 {@code SALE_NOT_FOUND}.
   *
   * <p>É operação de dinheiro/estado idempotente por contrato (§8): o {@link IdempotencyGuard}
   * exige o header {@code Idempotency-Key} (sem ele, 400 {@code IDEMPOTENCY_KEY_REQUIRED}) e o
   * retry com a mesma chave devolve a resposta gravada com {@code Idempotency-Replayed: true}, sem
   * cancelar de novo. Chave nova na venda já cancelada também é 200: o caso de uso trata o estado
   * como no-op (sem evento); venda concluída é 409 {@code SALE_ALREADY_COMPLETED} — o pagamento
   * aconteceu e a correção dela é o estorno da Fase 13. Motivo ausente ou em branco é 400 {@code
   * VALIDATION_ERROR} da forma, validada antes do caso de uso.
   */
  @POST
  @Path("/{id}/cancel")
  @RequirePermission(Permission.SALE_CANCEL)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response cancel(
      @PathParam("id") UUID id,
      @HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey,
      @Valid SaleCancelRequest request) {
    return idempotencyGuard.execute(
        idempotencyKey,
        HttpMethod.POST,
        PATH + "/" + id + "/cancel",
        request,
        () -> Response.ok(toDetailResponse(cancelSale(id, request))).build());
  }

  /** Ação idempotente: cancela a venda e monta o 200 com a venda como ela ficou. */
  private Sale cancelSale(UUID id, SaleCancelRequest request) {
    return cancelSaleUseCase.execute(
        new CancelSaleCommand(
            id, operationContext.cashRegisterId(), request.reason(), operationContext.userId()));
  }

  /**
   * Detalhe da venda (passo 812): a venda inteira — cabeçalho, itens, desconto e cliente. Sem
   * {@code @RequirePermission}: o §4.5 não tem permissão de leitura de venda e a visibilidade é da
   * guarda do caso de uso — a sessão lê a venda do seu caixa (BR-11, §9.4) e quem tem {@code
   * report.read} lê a de qualquer caixa (bypass de gestão, resolvido aqui e passado no comando).
   * {@code @Authenticated} mantém explícita a exigência de sessão da política global.
   *
   * <p>Venda inexistente é 404 {@code SALE_NOT_FOUND} para qualquer sessão; venda de outro caixa
   * sem a permissão de relatório é 403 {@code ACCESS_DENIED} — os dois do caso de uso. Venda
   * concluída é consultável: a leitura não exige {@code OPEN}.
   */
  @GET
  @Path("/{id}")
  @Authenticated
  @Produces(MediaType.APPLICATION_JSON)
  public SaleDetailResponse get(@PathParam("id") UUID id) {
    Sale sale =
        getSaleUseCase.execute(
            new GetSaleCommand(
                id,
                operationContext.cashRegisterId(),
                authorizationService.has(Permission.REPORT_READ)));
    return toDetailResponse(sale);
  }

  /**
   * Histórico da loja (passo 812): página do envelope padrão com os filtros do §9.3 — período
   * ({@code from} inclusivo, {@code to} exclusivo sobre {@code created_at}), status, sessão de
   * caixa e operador — em ordem {@code created_at desc}. Todos os filtros são opcionais e {@code
   * size} acima de 100 é limitado; {@code page} negativo ou {@code size} menor que 1 → 400 {@code
   * VALIDATION_ERROR} do caso de uso.
   *
   * <p>Exige {@code report.read}: é visão de loja, não do caixa — sem a permissão o interceptor
   * responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar (é o 403 do OPERADOR).
   */
  @GET
  @RequirePermission(Permission.REPORT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<SaleSummaryResponse> list(
      @QueryParam("from") Instant from,
      @QueryParam("to") Instant to,
      @QueryParam("status") SaleStatus status,
      @QueryParam("cashSessionId") UUID cashSessionId,
      @QueryParam("operatorUserId") UUID operatorUserId,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size) {
    SalePage sales =
        listSalesUseCase.execute(
            new ListSalesQuery(from, to, status, cashSessionId, operatorUserId, page, size));
    return new PageResponse<>(
        sales.items().stream().map(SalesResource::toSummaryResponse).toList(),
        sales.page(),
        sales.size(),
        sales.totalItems(),
        sales.totalPages());
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

  /**
   * A venda inteira como as rotas de item, desconto e cliente a devolvem; itens na ordem de
   * inclusão.
   */
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
        sale.discountType(),
        sale.discountValue(),
        sale.discountReason(),
        sale.discountAmount(),
        sale.total(),
        sale.itemCount(),
        sale.createdAt(),
        sale.completedAt(),
        sale.cancelReason(),
        sale.cancelledByUserId(),
        sale.cancelledAt(),
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

  /** Linha do histórico: o cabeçalho da projeção do 803, sem os itens (o detalhe tem os seus). */
  private static SaleSummaryResponse toSummaryResponse(SaleSummary sale) {
    return new SaleSummaryResponse(
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
        sale.completedAt());
  }
}
