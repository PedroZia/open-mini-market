package com.minimarket.sales.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Histórico de vendas com filtros e paginação (passo 812, §9.3). O caso de uso valida os parâmetros
 * e aplica o teto de {@code size}; os filtros (período sobre {@code created_at}, status, sessão de
 * caixa e operador) e a ordenação {@code created_at desc} são da porta {@link SaleStore}. Leitura
 * pura, sem {@code @Transactional}: não grava nada — mesma escolha do {@code ListCustomersUseCase}.
 *
 * <p>A visibilidade não é filtrada aqui: a rota exige {@code report.read} (§4.5), então quem chega
 * ao caso de uso enxerga a loja inteira — o OPERADOR não tem a permissão e recebe 403 no porteiro
 * da rota.
 */
@ApplicationScoped
public class ListSalesUseCase {

  /** Teto de {@code size} do §9.1: valores maiores são limitados, não recusados. */
  private static final int MAX_SIZE = 100;

  @Inject SaleStore saleStore;

  /**
   * {@code size} acima do teto é limitado a {@link #MAX_SIZE}; {@code page} negativo ou {@code
   * size} menor que 1 → 400 {@code VALIDATION_ERROR}. A página sai com o total de itens e o total
   * de páginas calculado sobre o {@code size} efetivo.
   */
  public SalePage execute(ListSalesQuery query) {
    requireValidPage(query.page());
    requireValidSize(query.size());
    int limitedSize = Math.min(query.size(), MAX_SIZE);

    List<SaleSummary> items =
        saleStore.search(
            query.from(),
            query.to(),
            query.status(),
            query.cashSessionId(),
            query.operatorUserId(),
            query.page(),
            limitedSize);
    long totalItems =
        saleStore.count(
            query.from(),
            query.to(),
            query.status(),
            query.cashSessionId(),
            query.operatorUserId());
    int totalPages = (int) ((totalItems + limitedSize - 1) / limitedSize);
    return new SalePage(items, query.page(), limitedSize, totalItems, totalPages);
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
}
