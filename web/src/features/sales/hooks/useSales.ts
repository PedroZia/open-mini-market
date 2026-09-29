import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { listOperators, listSales, type SaleQuery } from '../api/salesApi';

/** Prefixo do cache de vendas. */
export const salesQueryKey = ['sales'] as const;

/** Chave das opções de operador do filtro; vive sob o prefixo para uma invalidação reler tudo. */
export const operatorsQueryKey = ['sales', 'operators'] as const;

/**
 * Página do histórico de vendas do filtro corrente (1209a). `placeholderData: keepPreviousData`
 * mantém a página anterior na tela enquanto a nova chega — trocar página ou filtro não pisca o
 * estado de carregando; o `DataTable` marca a transição com "Atualizando…".
 */
export function useSales(query: SaleQuery) {
  return useQuery({
    queryKey: [...salesQueryKey, query],
    queryFn: () => listSales(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * Operadores para o filtro, só quando a sessão tem `user.read` (`enabled`): sem a permissão a query
 * nem sai, e a lista mostra o id curto do operador em vez do nome — o servidor recusaria a rota.
 */
export function useOperators(enabled: boolean) {
  return useQuery({
    queryKey: operatorsQueryKey,
    queryFn: listOperators,
    enabled,
  });
}
