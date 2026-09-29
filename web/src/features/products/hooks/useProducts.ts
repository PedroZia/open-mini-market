import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  disableProduct,
  enableProduct,
  listProducts,
  type ProductQuery,
  type ProductResponse,
} from '../api/productsApi';

/** Prefixo do cache da lista de produtos; a mutação de situação invalida tudo o que é lista. */
export const productsQueryKey = ['products'] as const;

/**
 * Lista de produtos do filtro corrente (1204a). `placeholderData: keepPreviousData` mantém a
 * página anterior na tela enquanto a nova chega — trocar página ou filtro não pisca o estado de
 * carregando; o `DataTable` marca a transição com "Atualizando…".
 */
export function useProducts(query: ProductQuery) {
  return useQuery({
    queryKey: [...productsQueryKey, query],
    queryFn: () => listProducts(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * Desativa/reativa com a lista invalidada: quem diz o estado final é o servidor, então a tela relê
 * a página corrente em vez de remendar o cache com o produto devolvido.
 */
function useProductStatusMutation(mutationFn: (id: string) => Promise<ProductResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
  });
}

/** Desativa o produto (ação que exige `product.write` no servidor). */
export function useDisableProduct() {
  return useProductStatusMutation(disableProduct);
}

/** Reativa o produto (ação que exige `product.write` no servidor). */
export function useEnableProduct() {
  return useProductStatusMutation(enableProduct);
}
