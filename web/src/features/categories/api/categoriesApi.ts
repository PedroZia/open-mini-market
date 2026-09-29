import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type CategoryResponse = components['schemas']['CategoryResponse'];
export type CategoryRequest = components['schemas']['CategoryRequest'];

const PATH = '/api/v1/categories';

/**
 * Lista todas as categorias (ativas e desativadas), na ordem do servidor (`sortOrder` e nome).
 * Array simples, sem paginação (§9.3): é a leitura do filtro/coluna de produtos e da tela de
 * gestão de categorias — `product.read` cobre.
 */
export function listCategories(): Promise<CategoryResponse[]> {
  return api.get<CategoryResponse[]>(PATH);
}

/**
 * Cadastra a categoria (`POST /categories`, passo 402b; exige `category.write`): o servidor valida
 * nome único e pai existente e devolve o registro como o banco o guardou.
 */
export function createCategory(body: CategoryRequest): Promise<CategoryResponse> {
  return api.post<CategoryResponse>(PATH, body);
}

/**
 * Edita nome, pai e ordenação (`PUT /categories/{id}`, passo 402b): substitui os três campos e não
 * mexe no estado ativo. O recurso não expõe `version` no contrato, então a escrita não leva
 * `If-Match` — o último a salvar vence. Categoria desativada continua editável.
 */
export function updateCategory(id: string, body: CategoryRequest): Promise<CategoryResponse> {
  return api.put<CategoryResponse>(`${PATH}/${id}`, body);
}

/**
 * Desativa sem apagar a linha (`DELETE /categories/{id}`, soft delete do §9.3) e responde 204 sem
 * corpo. Já desativada (ou id desconhecido) conta como inexistente: 404 `CATEGORY_NOT_FOUND`.
 */
export function deactivateCategory(id: string): Promise<void> {
  return api.delete<void>(`${PATH}/${id}`);
}
