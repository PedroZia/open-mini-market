import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Categoria do contrato (`GET /categories`): array simples, sem paginação (§9.3). */
export type CategoryResponse = components['schemas']['CategoryResponse'];

/**
 * Lista todas as categorias (ativas e desativadas), na ordem do servidor. É a leitura que a
 * retaguarda usa no filtro/coluna de produtos — `product.read` cobre (passo 1204a); o CRUD de
 * categorias é o passo 1205.
 */
export function listCategories(): Promise<CategoryResponse[]> {
  return api.get<CategoryResponse[]>('/api/v1/categories');
}
