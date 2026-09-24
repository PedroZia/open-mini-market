package com.minimarket.catalog.api;

import com.minimarket.catalog.application.BarcodeResolution;
import com.minimarket.catalog.application.ChangeProductPriceCommand;
import com.minimarket.catalog.application.ChangeProductPriceUseCase;
import com.minimarket.catalog.application.CreateProductCommand;
import com.minimarket.catalog.application.CreateProductUseCase;
import com.minimarket.catalog.application.DisableProductUseCase;
import com.minimarket.catalog.application.EnableProductUseCase;
import com.minimarket.catalog.application.GetProductByBarcodeUseCase;
import com.minimarket.catalog.application.GetProductUseCase;
import com.minimarket.catalog.application.ListProductsUseCase;
import com.minimarket.catalog.application.ProductPage;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.catalog.application.UpdateProductCommand;
import com.minimarket.catalog.application.UpdateProductUseCase;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.QueryParams;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.UUID;

/**
 * Produtos (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta — zero
 * regra de negócio aqui.
 *
 * <p>A escrita exige {@code product.write}: sem a permissão o interceptor do {@code
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar. A edição
 * (passo 410) exige ainda o {@code If-Match} com a versão lida no detalhe — é o contrato do lock
 * otimista (§9.4) —, a alteração de preço (passo 411) exige {@code price.write} e o motivo no corpo
 * e o ciclo de vida (passo 412) desativa e reativa com os mesmos 200 do detalhe.
 *
 * <p>O 201 devolve o produto como o banco o guardou (barcode normalizado, preço em escala 2, {@code
 * active}, timestamps e {@code version}) — os mesmos valores que o detalhe de 408 mostrará.
 */
@Path(ProductsResource.PATH)
public class ProductsResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/products";

  @Inject CreateProductUseCase createProductUseCase;

  @Inject ListProductsUseCase listProductsUseCase;

  @Inject GetProductUseCase getProductUseCase;

  @Inject GetProductByBarcodeUseCase getProductByBarcodeUseCase;

  @Inject UpdateProductUseCase updateProductUseCase;

  @Inject ChangeProductPriceUseCase changeProductPriceUseCase;

  @Inject DisableProductUseCase disableProductUseCase;

  @Inject EnableProductUseCase enableProductUseCase;

  @Context UriInfo uriInfo;

  /**
   * Cria o produto e devolve 201 com o {@code Location} dele. Categoria inexistente → 404 {@code
   * CATEGORY_NOT_FOUND}; barcode já usado por produto vivo → 409 {@code BARCODE_ALREADY_EXISTS};
   * unidade fora da whitelist ou preço inválido → 400 {@code VALIDATION_ERROR} (do caso de uso,
   * quando escapa da validação de forma).
   */
  @POST
  @RequirePermission(Permission.PRODUCT_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response create(@Valid CreateProductRequest request) {
    ProductSummary created =
        createProductUseCase
            .execute(
                new CreateProductCommand(
                    request.name(),
                    request.barcode(),
                    request.description(),
                    request.categoryId(),
                    request.unit(),
                    request.price(),
                    request.minQuantity()))
            .product();
    return Response.created(uriInfo.getAbsolutePathBuilder().path(created.id().toString()).build())
        .entity(toResponse(created))
        .build();
  }

  /**
   * Lista paginada com busca no nome, filtro de categoria e de status (§9.1 e §9.3). {@code sort}
   * aceita {@code name}, {@code price} ou {@code createdAt}, com {@code ,asc|desc} opcional
   * (default {@code name,asc}); {@code size} acima de 100 é limitado. Parâmetro fora da regra → 400
   * {@code VALIDATION_ERROR} do caso de uso. Filtro tipado inválido ({@code categoryId}, {@code
   * active}, {@code page}, {@code size}) → 400 {@code VALIDATION_ERROR} com {@code errors[]}, nunca
   * o 404 do conversor implícito (passo 1007).
   */
  @GET
  @RequirePermission(Permission.PRODUCT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<ProductResponse> list(
      @QueryParam("search") String search,
      @QueryParam("categoryId") String categoryId,
      @QueryParam("active") String active,
      @QueryParam("sort") String sort,
      @QueryParam("page") @DefaultValue("0") String page,
      @QueryParam("size") @DefaultValue("20") String size) {
    ProductPage products =
        listProductsUseCase.execute(
            search,
            QueryParams.uuidOf(categoryId, "categoryId"),
            QueryParams.booleanOf(active, "active"),
            sort,
            QueryParams.intOf(page, "page"),
            QueryParams.intOf(size, "size"));
    return new PageResponse<>(
        products.items().stream().map(ProductsResource::toResponse).toList(),
        products.page(),
        products.size(),
        products.totalItems(),
        products.totalPages());
  }

  /**
   * Detalhe do produto com todos os campos do contrato (§9.3). Id inexistente, produto
   * soft-deletado ou desativado → 404 {@code PRODUCT_NOT_FOUND}; quem decide isso é o caso de uso.
   */
  @GET
  @Path("/{id}")
  @RequirePermission(Permission.PRODUCT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public ProductResponse get(@PathParam("id") UUID id) {
    return toResponse(getProductUseCase.execute(id));
  }

  /**
   * Edita nome, categoria, unidade, descrição e quantidade mínima (§9.3, passo 410) — preço (411),
   * barcode (imutável) e status (412) não passam por aqui. O {@code If-Match} é obrigatório e leva
   * a versão que o cliente leu no detalhe: ausente ou em branco → 428 {@code IF_MATCH_REQUIRED};
   * versão velha → 409 {@code CONCURRENT_MODIFICATION} (§8 do plano); id desconhecido, produto
   * desativado ou soft-deletado → 404 {@code PRODUCT_NOT_FOUND}; unidade fora da whitelist ou
   * categoria inexistente → 400/404 do caso de uso.
   */
  @PUT
  @Path("/{id}")
  @RequirePermission(Permission.PRODUCT_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public ProductResponse update(
      @PathParam("id") UUID id,
      @HeaderParam("If-Match") String ifMatch,
      @Valid UpdateProductRequest request) {
    return toResponse(
        updateProductUseCase.execute(
            new UpdateProductCommand(
                id,
                expectedVersion(ifMatch),
                request.name(),
                request.categoryId(),
                request.unit(),
                request.description(),
                request.minQuantity())));
  }

  /**
   * Altera o preço com motivo obrigatório (passo 411) — é a única forma de mudar preço: o {@code
   * PUT} de cadastro não o aceita. Exige {@code price.write} (OPERADOR não tem) e devolve o {@link
   * ProductResponse} com o preço e o {@code version} novos; o antes/depois fica na auditoria
   * ({@code PRODUCT_PRICE_CHANGED}). Id desconhecido, produto desativado ou soft-deletado → 404
   * {@code PRODUCT_NOT_FOUND}; motivo em branco ou preço negativo → 400 {@code VALIDATION_ERROR};
   * preço igual ao atual é no-op e responde 200 sem evento.
   */
  @PATCH
  @Path("/{id}/price")
  @RequirePermission(Permission.PRICE_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public ProductResponse changePrice(
      @PathParam("id") UUID id, @Valid ChangeProductPriceRequest request) {
    return toResponse(
        changeProductPriceUseCase.execute(
            new ChangeProductPriceCommand(id, request.price(), request.reason())));
  }

  /**
   * Desativa o produto (passo 412): sai do catálogo sem perder histórico e libera o barcode para
   * outro produto, como o soft delete faz no índice único parcial. Exige {@code product.write} e
   * devolve 200 com o {@link ProductResponse} já {@code active=false}; id desconhecido ou produto
   * já desativado → 404 {@code PRODUCT_NOT_FOUND}, como no disable de usuário (passo 112).
   */
  @POST
  @Path("/{id}/disable")
  @RequirePermission(Permission.PRODUCT_WRITE)
  @Produces(MediaType.APPLICATION_JSON)
  public ProductResponse disable(@PathParam("id") UUID id) {
    return toResponse(disableProductUseCase.execute(id));
  }

  /**
   * Reativa o produto desativado (passo 412): volta à busca padrão e ao bipe. Exige {@code
   * product.write} e devolve 200 com o {@link ProductResponse} já {@code active=true}; id
   * desconhecido → 404 {@code PRODUCT_NOT_FOUND}; produto já ativo é no-op e responde 200 sem
   * evento. Se outro produto vivo já tomou o barcode liberado na desativação → 409 {@code
   * BARCODE_ALREADY_EXISTS} e o produto continua desativado (a checagem é do banco, no flush do
   * caso de uso).
   */
  @POST
  @Path("/{id}/enable")
  @RequirePermission(Permission.PRODUCT_WRITE)
  @Produces(MediaType.APPLICATION_JSON)
  public ProductResponse enable(@PathParam("id") UUID id) {
    return toResponse(enableProductUseCase.execute(id));
  }

  /**
   * Bipe do PDV (passo 409, estendido no 1104b3): resolve o produto pelo código lido — GTIN, código
   * interno digitado ou etiqueta de balança (BR-14) — com a normalização do caso de uso, e devolve
   * a {@code quantity} sugerida quando a etiqueta embute peso ou preço. Produto inativo,
   * soft-deletado ou código desconhecido → 404 {@code PRODUCT_NOT_FOUND}; etiqueta malformada → 422
   * {@code INVALID_INTERNAL_BARCODE} do parser. O segmento literal {@code barcode} tem prioridade
   * sobre {@code /{id}} no JAX-RS: um código não-UUID nunca cai no detalhe de 408.
   */
  @GET
  @Path("/barcode/{barcode}")
  @RequirePermission(Permission.PRODUCT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public ProductBarcodeResponse getByBarcode(@PathParam("barcode") String barcode) {
    return toBarcodeResponse(getProductByBarcodeUseCase.execute(barcode));
  }

  private static ProductResponse toResponse(ProductSummary product) {
    return new ProductResponse(
        product.id(),
        product.name(),
        product.barcode(),
        product.description(),
        product.categoryId(),
        product.unit(),
        product.price(),
        product.minQuantity(),
        product.active(),
        product.version(),
        product.createdAt(),
        product.updatedAt());
  }

  /**
   * Só os campos do bipe; o resto do cadastro fica no detalhe (passo 408). A {@code quantity} vem
   * da resolução (1104b3): nula fora da etiqueta de balança.
   */
  private static ProductBarcodeResponse toBarcodeResponse(BarcodeResolution resolution) {
    ProductSummary product = resolution.product();
    return new ProductBarcodeResponse(
        product.id(),
        product.barcode(),
        product.name(),
        product.price(),
        product.unit(),
        resolution.quantity());
  }

  /**
   * A versão esperada do {@code If-Match} (passo 410). O cabeçalho é opcional no protocolo, mas
   * obrigatório aqui: ausente ou em branco → 428 {@code IF_MATCH_REQUIRED}. Aceita as formas que os
   * clientes mandam — {@code 13}, {@code "13"} e o ETag fraco {@code W/"13"} — e qualquer outra
   * coisa que não seja um número não negativo → 400 {@code VALIDATION_ERROR}.
   */
  private static long expectedVersion(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new BusinessException(
          ErrorCode.IF_MATCH_REQUIRED,
          "envie o cabeçalho If-Match com a versão lida no detalhe do produto");
    }
    String value = ifMatch.trim();
    if (value.startsWith("W/")) {
      value = value.substring(2).trim();
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1).trim();
    }
    try {
      long version = Long.parseLong(value);
      if (version < 0) {
        throw invalidIfMatch();
      }
      return version;
    } catch (NumberFormatException notAVersion) {
      throw invalidIfMatch();
    }
  }

  private static BusinessException invalidIfMatch() {
    return new BusinessException(
        ErrorCode.VALIDATION_ERROR, "If-Match deve ser a versão do produto, ex.: 3");
  }
}
