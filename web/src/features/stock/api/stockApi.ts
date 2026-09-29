import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type StockItemResponse = components['schemas']['StockItemResponse'];
export type StockDetailResponse = components['schemas']['StockDetailResponse'];
export type PageResponseStockItemResponse = components['schemas']['PageResponseStockItemResponse'];

/**
 * Busca, filtro e paginação de `GET /stock` (passo 704), como o contrato os aceita (§9.3). O
 * recurso **não** tem ordenação, então não há `sort` para pedir.
 */
export interface StockQuery {
  /** Busca por trecho do nome ou barcode exato; ausente ou em branco não vira parâmetro. */
  search?: string | undefined;
  /** Só os saldos no mínimo ou abaixo; ausente = todos os produtos. */
  lowStock?: boolean | undefined;
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/stock';

/**
 * Lista paginada dos saldos. Só os parâmetros informados viajam na URL — filtro vazio não vira
 * `?search=` e quantidade de página fora da regra fica com o 400 do servidor.
 */
export function listStock(query: StockQuery = {}): Promise<PageResponseStockItemResponse> {
  const params = new URLSearchParams();
  if (query.search !== undefined && query.search.trim() !== '') {
    params.set('search', query.search);
  }
  if (query.lowStock !== undefined) {
    params.set('lowStock', String(query.lowStock));
  }
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseStockItemResponse>(search === '' ? PATH : `${PATH}?${search}`);
}

/**
 * Detalhe do estoque do produto (`GET /stock/{productId}`, passo 704): o saldo da loja e os
 * últimos movimentos. Produto inexistente ou desativado responde 404 `PRODUCT_NOT_FOUND`.
 */
export function getStock(productId: string): Promise<StockDetailResponse> {
  return api.get<StockDetailResponse>(`${PATH}/${productId}`);
}
