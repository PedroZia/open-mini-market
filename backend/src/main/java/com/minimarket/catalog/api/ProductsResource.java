package com.minimarket.catalog.api;

import com.minimarket.catalog.application.CreateProductCommand;
import com.minimarket.catalog.application.CreateProductUseCase;
import com.minimarket.catalog.application.GetProductUseCase;
import com.minimarket.catalog.application.ListProductsUseCase;
import com.minimarket.catalog.application.ProductPage;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar.
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
   * {@code VALIDATION_ERROR} do caso de uso.
   */
  @GET
  @RequirePermission(Permission.PRODUCT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<ProductResponse> list(
      @QueryParam("search") String search,
      @QueryParam("categoryId") UUID categoryId,
      @QueryParam("active") Boolean active,
      @QueryParam("sort") String sort,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size) {
    ProductPage products =
        listProductsUseCase.execute(search, categoryId, active, sort, page, size);
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
}
