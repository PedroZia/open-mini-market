import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type SalesSummaryResponse = components['schemas']['SalesSummaryResponse'];
export type SalesSummaryGroupResponse = components['schemas']['SalesSummaryGroupResponse'];
export type LowStockItemResponse = components['schemas']['LowStockItemResponse'];
export type PageResponseLowStockItemResponse =
  components['schemas']['PageResponseLowStockItemResponse'];

/**
 * Dimensões do agrupamento do resumo (1212a): o `groupBy` viaja em minúsculas — `day` (dia UTC),
 * `operator` (id do operador) e `paymentMethod` (nome da forma). A whitelist é fechada no servidor:
 * valor fora dela vira 400, então a tela só oferece o que o contrato promete.
 */
export type SalesSummaryGroupBy = 'day' | 'operator' | 'paymentMethod';

/**
 * Filtros de `GET /reports/sales-summary` (1212a): `from` inclusivo e `to` exclusivo sobre o
 * `completed_at`, ambos em ISO-8601 com offset. O período é **obrigatório** no servidor — a tela só
 * dispara a consulta com os dois preenchidos (`enabled`) — e `groupBy` é obrigatório sempre.
 */
export interface SalesSummaryQuery {
  /** Início do período (inclusivo), ISO-8601 com offset. */
  from?: string | undefined;
  /** Fim do período (exclusivo), ISO-8601 com offset. */
  to?: string | undefined;
  /** Dimensão do agrupamento. */
  groupBy: SalesSummaryGroupBy;
}

/** Filtros de `GET /reports/low-stock` (1212a), com a mesma paginação da lista de estoque. */
export interface LowStockReportQuery {
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/reports';

/**
 * Resumo das vendas concluídas do período (`GET /reports/sales-summary`, exige `report.read`): os
 * totais gerais (`salesCount`, `total`, `ticketAverage`) e os grupos da dimensão pedida. Os três
 * campos gerais já vêm calculados pelo servidor — a tela **não** soma os buckets (BR-12).
 */
export function getSalesSummary(query: SalesSummaryQuery): Promise<SalesSummaryResponse> {
  const params = new URLSearchParams({ groupBy: query.groupBy });
  if (query.from !== undefined) {
    params.set('from', query.from);
  }
  if (query.to !== undefined) {
    params.set('to', query.to);
  }
  return api.get<SalesSummaryResponse>(`${PATH}/sales-summary?${params.toString()}`);
}

/**
 * Página do relatório de estoque baixo (`GET /reports/low-stock`, exige `report.read`): os produtos
 * com saldo no mínimo configurado ou abaixo, em ordem de nome como a lista de estoque.
 */
export function listLowStockReport(
  query: LowStockReportQuery = {},
): Promise<PageResponseLowStockItemResponse> {
  const params = new URLSearchParams();
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseLowStockItemResponse>(
    search === '' ? `${PATH}/low-stock` : `${PATH}/low-stock?${search}`,
  );
}
