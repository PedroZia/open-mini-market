package com.minimarket.catalog.api;

import com.minimarket.catalog.application.CategorySummary;
import com.minimarket.catalog.application.CreateCategoryUseCase;
import com.minimarket.catalog.application.DeactivateCategoryUseCase;
import com.minimarket.catalog.application.ListCategoriesUseCase;
import com.minimarket.catalog.application.UpdateCategoryUseCase;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

/**
 * Categorias (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta — zero
 * regra de negócio aqui.
 *
 * <p>A leitura exige {@code product.read} (quem consulta produto precisa da categoria dele) e a
 * escrita exige {@code category.write}: sem a permissão o interceptor do {@code RequirePermission}
 * responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar.
 *
 * <p>A listagem é um array simples, sem paginação: o §9.3 não define {@code page}/{@code size} para
 * esta rota e o catálogo de categorias é pequeno — a ordenação vem do repositório.
 */
@Path(CategoriesResource.PATH)
public class CategoriesResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/categories";

  @Inject ListCategoriesUseCase listCategoriesUseCase;

  @Inject CreateCategoryUseCase createCategoryUseCase;

  @Inject UpdateCategoryUseCase updateCategoryUseCase;

  @Inject DeactivateCategoryUseCase deactivateCategoryUseCase;

  @Context UriInfo uriInfo;

  /** Categorias ativas e desativadas, ordenadas por {@code sortOrder} e nome. */
  @GET
  @RequirePermission(Permission.PRODUCT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public List<CategoryResponse> list() {
    return listCategoriesUseCase.execute().stream().map(CategoriesResource::toResponse).toList();
  }

  /**
   * Cria a categoria e devolve 201 com o {@code Location} dela. Pai inexistente → 404 {@code
   * CATEGORY_NOT_FOUND}; nome já em uso → 409 {@code CATEGORY_NAME_ALREADY_EXISTS}.
   */
  @POST
  @RequirePermission(Permission.CATEGORY_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response create(@Valid CategoryRequest request) {
    CategorySummary created =
        createCategoryUseCase.execute(request.name(), request.parentId(), sortOrderOf(request));
    return Response.created(uriInfo.getAbsolutePathBuilder().path(created.id().toString()).build())
        .entity(toResponse(created))
        .build();
  }

  /**
   * PUT substitui nome, pai e ordenação — não mexe no estado ativo. Id (ou pai) inexistente → 404
   * {@code CATEGORY_NOT_FOUND}; nome de outra categoria → 409 {@code CATEGORY_NAME_ALREADY_EXISTS}.
   */
  @PUT
  @Path("/{id}")
  @RequirePermission(Permission.CATEGORY_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public CategoryResponse update(@PathParam("id") UUID id, @Valid CategoryRequest request) {
    return toResponse(
        updateCategoryUseCase.execute(
            id, request.name(), request.parentId(), sortOrderOf(request)));
  }

  /**
   * DELETE desativa sem apagar a linha (§9.3, soft delete) e responde 204 sem corpo, como manda a
   * especificação do JAX-RS para método {@code void}. Id inexistente ou já desativado → 404 {@code
   * CATEGORY_NOT_FOUND}.
   */
  @DELETE
  @Path("/{id}")
  @RequirePermission(Permission.CATEGORY_WRITE)
  public void delete(@PathParam("id") UUID id) {
    deactivateCategoryUseCase.execute(id);
  }

  /** {@code sortOrder} é opcional no corpo: ausente vale o default da coluna (0). */
  private static int sortOrderOf(CategoryRequest request) {
    return request.sortOrder() == null ? 0 : request.sortOrder();
  }

  private static CategoryResponse toResponse(CategorySummary category) {
    return new CategoryResponse(
        category.id(),
        category.name(),
        category.parentId(),
        category.active(),
        category.sortOrder());
  }
}
