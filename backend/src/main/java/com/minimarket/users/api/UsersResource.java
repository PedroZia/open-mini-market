package com.minimarket.users.api;

import com.minimarket.shared.api.PageResponse;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserResult;
import com.minimarket.users.application.CreateUserUseCase;
import com.minimarket.users.application.DisableUserUseCase;
import com.minimarket.users.application.EnableUserUseCase;
import com.minimarket.users.application.GetUserUseCase;
import com.minimarket.users.application.ListUsersUseCase;
import com.minimarket.users.application.ResetPasswordUseCase;
import com.minimarket.users.application.UpdateUserUseCase;
import com.minimarket.users.application.UserPage;
import com.minimarket.users.application.UserSummary;
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
 * Usuários (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta — zero
 * regra de negócio aqui. A rota fica fora da autenticação até a Fase 3 (passo 307).
 */
@Path(UsersResource.PATH)
public class UsersResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/users";

  @Inject CreateUserUseCase createUserUseCase;

  @Inject ListUsersUseCase listUsersUseCase;

  @Inject GetUserUseCase getUserUseCase;

  @Inject UpdateUserUseCase updateUserUseCase;

  @Inject DisableUserUseCase disableUserUseCase;

  @Inject EnableUserUseCase enableUserUseCase;

  @Inject ResetPasswordUseCase resetPasswordUseCase;

  @Context UriInfo uriInfo;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response create(@Valid CreateUserRequest request) {
    CreateUserResult created =
        createUserUseCase.execute(
            new CreateUserCommand(
                request.username(),
                request.displayName(),
                request.password(),
                request.roleCodes()));
    return Response.created(uriInfo.getAbsolutePathBuilder().path(created.id().toString()).build())
        .entity(
            new UserResponse(
                created.id(),
                created.username(),
                created.displayName(),
                created.roles(),
                CreateUserUseCase.STATUS_ACTIVE,
                false))
        .build();
  }

  /** Lista paginada com busca em username/display_name e filtro de status (§9.1 e §9.3). */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<UserResponse> list(
      @QueryParam("search") String search,
      @QueryParam("active") Boolean active,
      @QueryParam("sort") String sort,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size) {
    UserPage users = listUsersUseCase.execute(search, active, sort, page, size);
    return new PageResponse<>(
        users.items().stream().map(UsersResource::toResponse).toList(),
        users.page(),
        users.size(),
        users.totalItems(),
        users.totalPages());
  }

  private static UserResponse toResponse(UserSummary user) {
    return new UserResponse(
        user.id(),
        user.username(),
        user.displayName(),
        user.roles(),
        user.status(),
        user.mustChangePassword());
  }

  /** Detalhe do usuário; inexistente ou soft-deletado → 404 {@code USER_NOT_FOUND}. */
  @GET
  @Path("/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse get(@PathParam("id") UUID id) {
    return toResponse(getUserUseCase.execute(id));
  }

  /**
   * PUT substitui o nome de exibição e o conjunto de papéis; username e senha não mudam por aqui.
   * Id inexistente → 404 {@code USER_NOT_FOUND}; papel desconhecido → 400 {@code UNKNOWN_ROLE} sem
   * gravar nada.
   */
  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse update(@PathParam("id") UUID id, @Valid UpdateUserRequest request) {
    return toResponse(updateUserUseCase.execute(id, request.displayName(), request.roleCodes()));
  }

  /**
   * Desativa o usuário sem apagar histórico (§9.3): devolve 200 com o {@code UserResponse} já
   * {@code DISABLED}. Id inexistente ou já desativado → 404 {@code USER_NOT_FOUND}; último ADMIN
   * ativo → 409 {@code CONFLICT} (a regra é do caso de uso).
   */
  @POST
  @Path("/{id}/disable")
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse disable(@PathParam("id") UUID id) {
    return toResponse(disableUserUseCase.execute(id));
  }

  /** Reativa o usuário desativado (§9.3); id inexistente → 404 {@code USER_NOT_FOUND}. */
  @POST
  @Path("/{id}/enable")
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse enable(@PathParam("id") UUID id) {
    return toResponse(enableUserUseCase.execute(id));
  }

  /**
   * Reset de senha por ADMIN (passo 113): define a senha temporária e devolve o usuário com {@code
   * mustChangePassword=true}. Id inexistente ou soft-deletado → 404 {@code USER_NOT_FOUND}; senha
   * curta → 400 {@code VALIDATION_ERROR} com {@code errors[]}. Revogar as sessões é do passo 213.
   */
  @POST
  @Path("/{id}/password-reset")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse resetPassword(@PathParam("id") UUID id, @Valid ResetPasswordRequest request) {
    return toResponse(resetPasswordUseCase.execute(id, request.newPassword()));
  }
}
