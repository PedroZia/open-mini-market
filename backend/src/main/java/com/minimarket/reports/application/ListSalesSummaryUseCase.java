package com.minimarket.reports.application;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.FieldValidationException;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resumo de vendas do período para o dashboard e os relatórios (passo 1212a, §9.3). O caso de uso
 * valida o período e resolve a dimensão do {@code groupBy} — string do cliente nunca chega ao SQL —
 * e a loja é a configurada ({@code minimarket.store.default-code}), nunca um parâmetro do cliente,
 * como no {@code ListStockUseCase}. Leitura pura, sem {@code @Transactional}: não grava nada.
 *
 * <p>O período é {@code from} inclusivo e {@code to} exclusivo sobre o {@code completed_at} (a
 * semântica de faturamento, §9.1) e os dois são obrigatórios — falta de campo ou período vazio é
 * 400 {@code VALIDATION_ERROR} com {@code errors[]} citando o campo. A base são as vendas {@code
 * COMPLETED}: venda aberta ou cancelada fica fora, mesmo com pagamento aprovado gravado.
 */
@ApplicationScoped
public class ListSalesSummaryUseCase {

  @Inject SalesSummaryQueryStore salesSummaryQueryStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * {@code groupBy} fora da whitelist, {@code from}/{@code to} ausentes ou período vazio ({@code
   * from} não anterior a {@code to}) → 400 {@code VALIDATION_ERROR} citando o campo. O ticket médio
   * sai na escala 2 com {@code HALF_UP} — zero quando o período não tem venda.
   */
  public SalesSummaryView execute(String groupBy, Instant from, Instant to) {
    requirePeriod(from, to);
    SalesSummaryGroupBy grouping = SalesSummaryGroupBy.fromParam(groupBy);
    Store store = currentStore();

    SalesTotals totals = salesSummaryQueryStore.totals(store.id(), from, to);
    List<SalesSummaryGroup> groups =
        switch (grouping) {
          case DAY -> salesSummaryQueryStore.byDay(store.id(), from, to);
          case OPERATOR -> salesSummaryQueryStore.byOperator(store.id(), from, to);
          case PAYMENT_METHOD -> salesSummaryQueryStore.byPaymentMethod(store.id(), from, to);
        };
    return new SalesSummaryView(
        from, to, grouping, totals.salesCount(), totals.total(), totals.ticketAverage(), groups);
  }

  private static void requirePeriod(Instant from, Instant to) {
    if (from == null) {
      throw new FieldValidationException("from", "é obrigatório");
    }
    if (to == null) {
      throw new FieldValidationException("to", "é obrigatório");
    }
    if (!from.isBefore(to)) {
      throw new FieldValidationException("from", "deve ser anterior a to");
    }
  }

  /** Relatório é visão de loja; a loja atual vem da configuração, não do cliente. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }
}
