package com.minimarket.catalog.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Desativa a categoria (passo 402b) sem apagar a linha: produtos e vendas continuam podendo
 * referenciá-la. Uma execução = uma transação (§2.2, regra 6).
 *
 * <p>A semântica é a do disable de usuário: categoria já desativada conta como inexistente — o
 * {@code DELETE} repetido responde 404 em vez de 204 vazio, para o cliente perceber que a linha já
 * estava fora de circulação.
 */
@ApplicationScoped
public class DeactivateCategoryUseCase {

  @Inject CategoryStore categoryStore;

  /** 404 {@code CATEGORY_NOT_FOUND} para id desconhecido ou categoria já desativada. */
  @Transactional
  public void execute(UUID id) {
    CategorySummary category = categoryStore.findById(id).orElseThrow(() -> notFound(id));
    if (!category.active()) {
      throw notFound(id);
    }
    categoryStore.deactivate(id);
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.CATEGORY_NOT_FOUND, "categoria %s não encontrada".formatted(id));
  }
}
