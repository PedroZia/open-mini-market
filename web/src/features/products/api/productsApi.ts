import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type ProductResponse = components['schemas']['ProductResponse'];
export type PageResponseProductResponse = components['schemas']['PageResponseProductResponse'];

/** Busca, filtros, ordenação e paginação de `GET /products`, como o contrato os aceita (§9.3). */
export interface ProductQuery {
  /** Busca no nome; ausente ou em branco não vira parâmetro. */
  search?: string | undefined;
  /** Filtro de categoria; ausente = todas. */
  categoryId?: string | undefined;
  /** Situação; ausente = ativos e desativados juntos. */
  active?: boolean | undefined;
  /** `campo,asc|desc` da whitelist do servidor (`name`, `price`, `createdat`). */
  sort?: string | undefined;
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/products';

/**
 * Lista paginada com busca, filtros e ordenação. Só os parâmetros informados viajam na URL — filtro
 * vazio não vira `?search=` e um `sort` fora da whitelist nunca sai daqui (as colunas do
 * `DataTable` só declaram chaves aceitas).
 */
export function listProducts(query: ProductQuery = {}): Promise<PageResponseProductResponse> {
  const params = new URLSearchParams();
  if (query.search !== undefined && query.search.trim() !== '') {
    params.set('search', query.search);
  }
  if (query.categoryId !== undefined && query.categoryId !== '') {
    params.set('categoryId', query.categoryId);
  }
  if (query.active !== undefined) {
    params.set('active', String(query.active));
  }
  if (query.sort !== undefined && query.sort !== '') {
    params.set('sort', query.sort);
  }
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseProductResponse>(search === '' ? PATH : `${PATH}?${search}`);
}

/** Desativa o produto (`POST /products/{id}/disable`, passo 412) e devolve-o já inativo. */
export function disableProduct(id: string): Promise<ProductResponse> {
  return api.post<ProductResponse>(`${PATH}/${id}/disable`);
}

/** Reativa o produto (`POST /products/{id}/enable`, passo 412) e devolve-o já ativo. */
export function enableProduct(id: string): Promise<ProductResponse> {
  return api.post<ProductResponse>(`${PATH}/${id}/enable`);
}
