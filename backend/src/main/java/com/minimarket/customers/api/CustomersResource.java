package com.minimarket.customers.api;

import com.minimarket.customers.application.CreateCustomerCommand;
import com.minimarket.customers.application.CreateCustomerUseCase;
import com.minimarket.customers.application.CustomerPage;
import com.minimarket.customers.application.CustomerSummary;
import com.minimarket.customers.application.DisableCustomerUseCase;
import com.minimarket.customers.application.GetCustomerUseCase;
import com.minimarket.customers.application.ListCustomersUseCase;
import com.minimarket.customers.application.UpdateCustomerCommand;
import com.minimarket.customers.application.UpdateCustomerUseCase;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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
 * Clientes (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta — zero
 * regra de negócio aqui.
 *
 * <p>A leitura exige {@code customer.read} e a escrita {@code customer.write}. Diferente do
 * catálogo (onde o OPERADOR só lê), a matriz de §4.5 dá as duas permissões ao OPERADOR: o caixa
 * cadastra cliente na venda. Não há {@code If-Match} no PUT (decisão do passo): o lock otimista do
 * repositório é o backstop. Não há rota de reativação: cliente desativado não volta.
 *
 * <p>O 201 devolve o cliente como o banco o guardou (CPF e telefone já normalizados, {@code
 * active}, timestamps e {@code version}) — os mesmos valores que o detalhe mostrará.
 */
@Path(CustomersResource.PATH)
public class CustomersResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/customers";

  @Inject CreateCustomerUseCase createCustomerUseCase;

  @Inject ListCustomersUseCase listCustomersUseCase;

  @Inject GetCustomerUseCase getCustomerUseCase;

  @Inject UpdateCustomerUseCase updateCustomerUseCase;

  @Inject DisableCustomerUseCase disableCustomerUseCase;

  @Context UriInfo uriInfo;

  /**
   * Cria o cliente e devolve 201 com o {@code Location} dele. CPF informado e inválido → 400 {@code
   * VALIDATION_ERROR}; CPF já usado por cliente vivo → 409 {@code TAX_ID_ALREADY_EXISTS}.
   */
  @POST
  @RequirePermission(Permission.CUSTOMER_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response create(@Valid CustomerRequest request) {
    CustomerSummary created =
        createCustomerUseCase.execute(
            new CreateCustomerCommand(
                request.name(),
                request.taxId(),
                request.phone(),
                request.email(),
                request.notes()));
    return Response.created(uriInfo.getAbsolutePathBuilder().path(created.id().toString()).build())
        .entity(toResponse(created))
        .build();
  }

  /**
   * Lista paginada com busca no nome (trecho) e CPF/telefone (dígitos exatos) — o §9.3 define só
   * {@code search}/{@code page}/{@code size}, sem {@code sort}. {@code size} acima de 100 é
   * limitado; parâmetro fora da regra → 400 {@code VALIDATION_ERROR} do caso de uso.
   */
  @GET
  @RequirePermission(Permission.CUSTOMER_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<CustomerResponse> list(
      @QueryParam("search") String search,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size) {
    CustomerPage customers = listCustomersUseCase.execute(search, page, size);
    return new PageResponse<>(
        customers.items().stream().map(CustomersResource::toResponse).toList(),
        customers.page(),
        customers.size(),
        customers.totalItems(),
        customers.totalPages());
  }

  /**
   * Detalhe do cliente. Id inexistente ou cliente desativado → 404 {@code CUSTOMER_NOT_FOUND}: como
   * não há reativação, os dois são o mesmo caso.
   */
  @GET
  @Path("/{id}")
  @RequirePermission(Permission.CUSTOMER_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public CustomerResponse get(@PathParam("id") UUID id) {
    return toResponse(getCustomerUseCase.execute(id));
  }

  /**
   * Substitui os cinco campos editáveis (§9.3). Sem {@code If-Match}: o lock otimista do
   * repositório responde 409 {@code CONCURRENT_MODIFICATION} se outra requisição gravar no meio. Id
   * desconhecido ou cliente desativado → 404 {@code CUSTOMER_NOT_FOUND}; CPF inválido → 400 {@code
   * VALIDATION_ERROR}; CPF de outro cliente vivo → 409 {@code TAX_ID_ALREADY_EXISTS}. Sem mudança
   * efetiva é no-op e responde 200 sem evento.
   */
  @PUT
  @Path("/{id}")
  @RequirePermission(Permission.CUSTOMER_WRITE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public CustomerResponse update(@PathParam("id") UUID id, @Valid CustomerRequest request) {
    return toResponse(
        updateCustomerUseCase.execute(
            new UpdateCustomerCommand(
                id,
                request.name(),
                request.taxId(),
                request.phone(),
                request.email(),
                request.notes())));
  }

  /**
   * Desativa o cliente (§9.3): sai da busca sem perder histórico e libera o CPF para outro cliente,
   * como o soft delete faz no índice único parcial. Id desconhecido ou cliente já desativado → 404
   * {@code CUSTOMER_NOT_FOUND}, como no disable de usuário e de produto.
   */
  @POST
  @Path("/{id}/disable")
  @RequirePermission(Permission.CUSTOMER_WRITE)
  @Produces(MediaType.APPLICATION_JSON)
  public CustomerResponse disable(@PathParam("id") UUID id) {
    return toResponse(disableCustomerUseCase.execute(id));
  }

  private static CustomerResponse toResponse(CustomerSummary customer) {
    return new CustomerResponse(
        customer.id(),
        customer.name(),
        customer.taxId(),
        customer.phone(),
        customer.email(),
        customer.notes(),
        customer.active(),
        customer.version(),
        customer.createdAt(),
        customer.updatedAt());
  }
}
