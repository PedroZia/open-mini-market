import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  getSalesSummary,
  listLowStockReport,
  type LowStockReportQuery,
  type SalesSummaryQuery,
} from '../api/reportsApi';

/** Prefixo do cache dos relatórios (1212b); as chaves filhas são as duas leituras do módulo. */
export const reportsQueryKey = ['reports'] as const;

/**
 * Resumo de vendas do período corrente (1212a). `enabled` desliga a consulta em dois casos: no
 * relatório, enquanto `from`/`to` não estão completos (o servidor devolveria 400 sem eles); no
 * dashboard, quando a sessão não tem `report.read` (o OPERADOR não consulta relatórios).
 */
export function useSalesSummary(query: SalesSummaryQuery, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsQueryKey, 'sales-summary', query],
    queryFn: () => getSalesSummary(query),
    enabled,
  });
}

/**
 * Página do relatório de estoque baixo (1212a). `placeholderData: keepPreviousData` mantém a página
 * anterior na tela enquanto a nova chega — trocar de página não pisca o estado de carregando; o
 * `DataTable` marca a transição com "Atualizando…".
 */
export function useLowStockReport(query: LowStockReportQuery) {
  return useQuery({
    queryKey: [...reportsQueryKey, 'low-stock', query],
    queryFn: () => listLowStockReport(query),
    placeholderData: keepPreviousData,
  });
}
