package com.minimarket.catalog.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Lista as categorias (§9.3 do plano). Leitura pura, sem {@code @Transactional}: não grava nada e a
 * consulta é uma ida só ao banco — mesma escolha do {@code ListUsersUseCase}. A ordem ({@code
 * sort_order}, depois nome) é da porta {@link CategoryStore}; o MVP devolve a lista inteira porque
 * o §9.3 não define paginação para esta rota.
 */
@ApplicationScoped
public class ListCategoriesUseCase {

  @Inject CategoryStore categoryStore;

  /** Todas as categorias da loja, ativas e desativadas, na ordem da porta. */
  public List<CategorySummary> execute() {
    return categoryStore.findAll();
  }
}
