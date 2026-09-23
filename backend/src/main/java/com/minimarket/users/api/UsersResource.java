package com.minimarket.users.api;

import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserResult;
import com.minimarket.users.application.CreateUserUseCase;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Usuários (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta — zero
 * regra de negócio aqui. A rota fica fora da autenticação até a Fase 3 (passo 307).
 */
@Path(UsersResource.PATH)
public class UsersResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/users";

  @Inject CreateUserUseCase createUserUseCase;

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
                CreateUserUseCase.STATUS_ACTIVE))
        .build();
  }
}
