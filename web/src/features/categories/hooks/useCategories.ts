import { useQuery } from '@tanstack/react-query';
import { listCategories } from '../api/categoriesApi';

/** Chave da lista de categorias no cache; o CRUD (1205) invalida por este prefixo. */
export const categoriesQueryKey = ['categories'] as const;

/**
 * Categorias para o filtro e a coluna da lista de produtos (1204a). O erro aqui não ganha estado
 * próprio: sem categorias a lista continua util (coluna e filtro ficam vazios) e o 403 da leitura
 * é mostrado pela própria tela de produtos, não por esta query.
 */
export function useCategories() {
  return useQuery({
    queryKey: categoriesQueryKey,
    queryFn: listCategories,
  });
}
