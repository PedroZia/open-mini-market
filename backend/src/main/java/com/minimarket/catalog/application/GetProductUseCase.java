package com.minimarket.catalog.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Detalhe do produto (§9.3 do plano): lê pela porta {@link ProductStore} — que enxerga o
 * soft-deletado — e traduz em 404 com o código estável {@code PRODUCT_NOT_FOUND} tanto o id
 * desconhecido quanto o produto fora do catálogo ({@code deletedAt} preenchido ou {@code active}
 * falso). Leitura pura, sem {@code @Transactional}: não grava nada — mesma escolha do {@code
 * GetUserUseCase} e do {@link ListProductsUseCase}.
 */
@ApplicationScoped
public class GetProductUseCase {

  @Inject ProductStore productStore;

  public ProductSummary execute(UUID id) {
    return productStore
        .findById(id)
        .filter(product -> product.deletedAt() == null && product.active())
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(id)));
  }
}
