package com.minimarket.reports.application;

import com.minimarket.inventory.application.ListStockUseCase;
import com.minimarket.inventory.application.StockItemSummary;
import com.minimarket.inventory.application.StockPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Relatório de estoque baixo (passo 1212a, §9.3): a página dos produtos com saldo no mínimo
 * configurado ou abaixo, em ordem de nome como a lista de estoque. O caso de uso <em>reusa</em> a
 * regra do inventário — injeta o {@code ListStockUseCase} com {@code lowStock=true} (comunicação
 * entre módulos via {@code application}, §2.2) e mapeia a projeção para o shape enxuto do
 * relatório; a expressão de estoque baixo continua num lugar só e nada muda em {@code inventory}.
 *
 * <p>A validação de {@code page}/{@code size} e o teto de 100 também são do {@code
 * ListStockUseCase}, o mesmo das listagens: página negativa ou {@code size} menor que 1 → 400
 * {@code VALIDATION_ERROR}. Leitura pura, sem {@code @Transactional}: não grava nada.
 */
@ApplicationScoped
public class ListLowStockUseCase {

  @Inject ListStockUseCase listStockUseCase;

  /** {@code search} é nulo — o relatório é a lista de estoque baixo inteira, só paginada. */
  public LowStockPage execute(int page, int size) {
    StockPage stock = listStockUseCase.execute(null, Boolean.TRUE, page, size);
    return new LowStockPage(
        stock.items().stream().map(ListLowStockUseCase::toItem).toList(),
        stock.page(),
        stock.size(),
        stock.totalItems(),
        stock.totalPages());
  }

  private static LowStockItem toItem(StockItemSummary item) {
    return new LowStockItem(
        item.productId(),
        item.name(),
        item.barcode(),
        item.unit(),
        item.quantity(),
        item.minQuantity());
  }
}
