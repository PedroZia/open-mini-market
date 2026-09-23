package com.minimarket.users.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Detalhe do usuário (§9.3 do plano): lê pela porta {@link UserStore} e traduz a ausência em 404
 * com o código estável {@code USER_NOT_FOUND}. Usuário soft-deletado não existe para esta consulta
 * (filtro no adaptador); {@code DISABLED} é devolvido normalmente. Leitura pura, sem transação
 * própria — igual a {@link ListUsersUseCase}.
 */
@ApplicationScoped
public class GetUserUseCase {

  @Inject UserStore userStore;

  public UserSummary execute(UUID id) {
    return userStore
        .findSummaryById(id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id)));
  }
}
