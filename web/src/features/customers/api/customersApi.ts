import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type CustomerResponse = components['schemas']['CustomerResponse'];
export type CustomerRequest = components['schemas']['CustomerRequest'];
export type PageResponseCustomerResponse = components['schemas']['PageResponseCustomerResponse'];

/**
 * Busca e paginação de `GET /customers` (passo 502a), como o contrato as aceita (§9.3). O recurso
 * **não** tem `sort` (a ordem é fixa por nome no servidor) nem filtro de situação: a busca já
 * exclui os desativados.
 */
export interface CustomerQuery {
  /** Nome (trecho), CPF ou telefone (dígitos); ausente ou em branco não vira parâmetro. */
  search?: string | undefined;
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/customers';

/**
 * Lista paginada com busca. Só os parâmetros informados viajam na URL — busca vazia não vira
 * `?search=` e uma página fora da regra fica com o 400 do servidor.
 */
export function listCustomers(query: CustomerQuery = {}): Promise<PageResponseCustomerResponse> {
  const params = new URLSearchParams();
  if (query.search !== undefined && query.search.trim() !== '') {
    params.set('search', query.search);
  }
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseCustomerResponse>(search === '' ? PATH : `${PATH}?${search}`);
}

/**
 * Detalhe do cliente (`GET /customers/{id}`, passo 502b): é dele que o formulário de edição é
 * semeado e relido depois de um conflito. Cliente desativado ou id desconhecido → 404
 * `CUSTOMER_NOT_FOUND`.
 */
export function getCustomer(id: string): Promise<CustomerResponse> {
  return api.get<CustomerResponse>(`${PATH}/${id}`);
}

/**
 * Cadastra o cliente (`POST /customers`, passo 502b; exige `customer.write`): o servidor normaliza
 * CPF e telefone, valida o CPF, recusa documento já usado por cliente vivo e audita a criação na
 * mesma transação. Devolve o registro como o banco o guardou.
 */
export function createCustomer(body: CustomerRequest): Promise<CustomerResponse> {
  return api.post<CustomerResponse>(PATH, body);
}

/**
 * Edita os cinco campos (`PUT /customers/{id}`, passo 502b): o contrato não expõe `If-Match`, então
 * a escrita não leva o lock otimista — o `@Version` do repositório é o backstop e responde 409
 * `CONCURRENT_MODIFICATION` se outra requisição gravar no meio.
 */
export function updateCustomer(id: string, body: CustomerRequest): Promise<CustomerResponse> {
  return api.put<CustomerResponse>(`${PATH}/${id}`, body);
}

/**
 * Desativa o cliente (`POST /customers/{id}/disable`, passo 502b): soft delete que o tira da busca
 * e libera o CPF, sem apagar histórico. Não há rota de reativação — desativado não volta; id
 * desconhecido ou já desativado → 404 `CUSTOMER_NOT_FOUND`.
 */
export function disableCustomer(id: string): Promise<CustomerResponse> {
  return api.post<CustomerResponse>(`${PATH}/${id}/disable`);
}
