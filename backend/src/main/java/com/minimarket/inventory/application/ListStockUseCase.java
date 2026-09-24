package com.minimarket.inventory.application;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Lista os saldos de estoque com busca, filtro de estoque baixo e paginação (§9.3 do plano, passo
 * 704). O caso de uso valida os parâmetros e aplica o teto de {@code size}, como o {@code
 * ListProductsUseCase} do catálogo; a loja é a configurada ({@code minimarket.store.default-code}),
 * nunca um parâmetro do cliente — mesmo padrão do {@code StockService}. Leitura pura, sem
 * {@code @Transactional}: não grava nada.
 */
@ApplicationScoped
public class ListStockUseCase {

  /** Teto de {@code size} do §9.1: valores maiores são limitados, não recusados. */
  private static final int MAX_SIZE = 100;

  @Inject StockQueryStore stockQueryStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * {@code search} em branco = sem filtro textual; {@code lowStock} nulo = sem filtro; {@code size}
   * acima do teto é limitado a {@link #MAX_SIZE}; {@code page} negativo ou {@code size} menor que 1
   * → 400 {@code VALIDATION_ERROR}.
   */
  public StockPage execute(String search, Boolean lowStock, int page, int size) {
    requireValidPage(page);
    requireValidSize(size);
    int limitedSize = Math.min(size, MAX_SIZE);
    Store store = currentStore();

    List<StockItemSummary> items =
        stockQueryStore.search(store.id(), search, lowStock, page, limitedSize);
    long totalItems = stockQueryStore.count(store.id(), search, lowStock);
    int totalPages = (int) ((totalItems + limitedSize - 1) / limitedSize);
    return new StockPage(items, page, limitedSize, totalItems, totalPages);
  }

  private static void requireValidPage(int page) {
    if (page < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page deve ser maior ou igual a 0");
    }
  }

  private static void requireValidSize(int size) {
    if (size < 1) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "size deve ser maior ou igual a 1");
    }
  }

  /** Produto só existe dentro de uma loja; a loja atual vem da configuração, não do cliente. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }
}
