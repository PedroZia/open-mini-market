import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type SaleSummaryResponse = components['schemas']['SaleSummaryResponse'];
export type PageResponseSaleSummaryResponse =
  components['schemas']['PageResponseSaleSummaryResponse'];
export type SaleStatus = components['schemas']['SaleStatus'];
export type UserResponse = components['schemas']['UserResponse'];
export type PageResponseUserResponse = components['schemas']['PageResponseUserResponse'];

/**
 * Filtros de `GET /sales` (passo 813), como o contrato os aceita: `from` inclusivo e `to` exclusivo,
 * ambos em ISO-8601 com offset; `status` da união da venda e `operatorUserId` em UUID. O recurso
 * **não tem `sort`** (§9.3): a lista sempre vem na ordem do servidor.
 */
export interface SaleQuery {
  /** Início do período (inclusivo), ISO-8601 com offset; ausente = sem piso. */
  from?: string | undefined;
  /** Fim do período (exclusivo), ISO-8601 com offset; ausente = sem teto. */
  to?: string | undefined;
  /** Situação; ausente = todos os status. */
  status?: SaleStatus | undefined;
  /** Operador da venda; ausente = todos. */
  operatorUserId?: string | undefined;
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/sales';
const USERS_PATH = '/api/v1/users';

/** Teto de `size` do servidor (§9.1): o picker de operador não pagina, pede o máximo de uma vez. */
const OPERATOR_PAGE_SIZE = 100;

/**
 * Histórico paginado (`GET /sales`, exige `report.read` no servidor — é visão de loja, não do caixa,
 * §9.4). Só os filtros informados viajam na URL: período em branco não vira `?from=`.
 */
export function listSales(query: SaleQuery = {}): Promise<PageResponseSaleSummaryResponse> {
  const params = new URLSearchParams();
  if (query.from !== undefined) {
    params.set('from', query.from);
  }
  if (query.to !== undefined) {
    params.set('to', query.to);
  }
  if (query.status !== undefined) {
    params.set('status', query.status);
  }
  if (query.operatorUserId !== undefined && query.operatorUserId !== '') {
    params.set('operatorUserId', query.operatorUserId);
  }
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseSaleSummaryResponse>(search === '' ? PATH : `${PATH}?${search}`);
}

/**
 * Operadores para o filtro da lista (`GET /users`, exige `user.read`): a tela só chama com a
 * permissão na sessão, então o servidor nunca recusa por aqui. Uma página cheia basta para o picker
 * — não há busca nem paginação própria nesta lista.
 */
export function listOperators(): Promise<PageResponseUserResponse> {
  return api.get<PageResponseUserResponse>(`${USERS_PATH}?size=${OPERATOR_PAGE_SIZE}`);
}
