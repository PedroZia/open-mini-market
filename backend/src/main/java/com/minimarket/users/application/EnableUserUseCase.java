package com.minimarket.users.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Reativa o usuário desativado (passo 112): o adaptador devolve {@code status = ACTIVE} e limpa
 * {@code deleted_at}, o que traz o usuário de volta à busca padrão. Usuário já ativo é no-op e
 * responde 200 igual. Uma execução = uma transação (§2.2, regra 6).
 */
@ApplicationScoped
public class EnableUserUseCase {

  @Inject UserStore userStore;

  /** 404 {@code USER_NOT_FOUND} quando o id não existe; id desativado é reativado normalmente. */
  @Transactional
  public UserSummary execute(UUID id) {
    return userStore
        .enable(id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id)));
  }
}
