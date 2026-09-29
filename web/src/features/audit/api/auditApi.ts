import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type AuditEventResponse = components['schemas']['AuditEventResponse'];
export type PageResponseAuditEventResponse =
  components['schemas']['PageResponseAuditEventResponse'];
export type OperationSource = components['schemas']['OperationSource'];

/**
 * Filtros de `GET /audit-events` (passo 1001), como o contrato os aceita (§9.3): todos opcionais e
 * combináveis; `from` inclusivo e `to` exclusivo, ambos em ISO-8601 com offset. `sort` aceita só
 * `occurredat` (`,asc|desc` opcional) — o default do servidor é `occurredat,desc`.
 */
export interface AuditEventQuery {
  /** Tipo da entidade auditada (ex.: `SALE`); ausente = todos. */
  entityType?: string | undefined;
  /** Id da entidade auditada, em UUID; ausente = todas. */
  entityId?: string | undefined;
  /** Autor da operação, em UUID; ausente = todos. */
  actorUserId?: string | undefined;
  /** Código da ação (ex.: `SALE_COMPLETED`); ausente = todas. */
  action?: string | undefined;
  /** Sessão de caixa da operação, em UUID; ausente = todas. */
  cashSessionId?: string | undefined;
  /** Início do período (inclusivo), ISO-8601 com offset; ausente = sem piso. */
  from?: string | undefined;
  /** Fim do período (exclusivo), ISO-8601 com offset; ausente = sem teto. */
  to?: string | undefined;
  /** `occurredat,asc|desc` — a única chave da whitelist do recurso. */
  sort?: string | undefined;
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/audit-events';

/**
 * Consulta paginada do log (`GET /audit-events`, exige `audit.read` no servidor). Só os filtros
 * preenchidos viajam na URL: campo em branco não vira `?entityType=` e um `sort` fora da whitelist
 * nunca sai daqui (a coluna do `DataTable` só declara `occurredat`).
 */
export function listAuditEvents(
  query: AuditEventQuery = {},
): Promise<PageResponseAuditEventResponse> {
  const params = new URLSearchParams();
  setIfFilled(params, 'entityType', query.entityType);
  setIfFilled(params, 'entityId', query.entityId);
  setIfFilled(params, 'actorUserId', query.actorUserId);
  setIfFilled(params, 'action', query.action);
  setIfFilled(params, 'cashSessionId', query.cashSessionId);
  setIfFilled(params, 'from', query.from);
  setIfFilled(params, 'to', query.to);
  setIfFilled(params, 'sort', query.sort);
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseAuditEventResponse>(search === '' ? PATH : `${PATH}?${search}`);
}

/** Põe o parâmetro só quando o texto tem conteúdo — filtro vazio não polui a query. */
function setIfFilled(params: URLSearchParams, name: string, value: string | undefined): void {
  if (value !== undefined && value.trim() !== '') {
    params.set(name, value.trim());
  }
}
