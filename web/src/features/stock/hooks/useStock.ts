import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getStock, listStock, type StockQuery } from '../api/stockApi';

/**
 * Prefixo do cache de estoque. As mutações do 1206b invalidam por ele, alcançando lista **e**
 * detalhe — quem diz o saldo é o servidor, nunca o que o cliente montou.
 */
export const stockQueryKey = ['stock'] as const;

/** Chave do detalhe do estoque — filha de `stockQueryKey`, então a invalidação a alcança. */
function stockDetailQueryKey(productId: string) {
  return [...stockQueryKey, 'detail', productId] as const;
}

/**
 * Lista de saldos do filtro corrente (1206a). `placeholderData: keepPreviousData` mantém a página
 * anterior na tela enquanto a nova chega — trocar página ou filtro não pisca o estado de
 * carregando; o `DataTable` marca a transição com "Atualizando…".
 */
export function useStockList(query: StockQuery) {
  return useQuery({
    queryKey: [...stockQueryKey, query],
    queryFn: () => listStock(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * Detalhe do estoque do produto (1206a): o resumo desta tela e, no 1206b, os movimentos que vêm na
 * mesma resposta. `staleTime: 0` porque saldo é leitura de operação — a tela mostra o valor do
 * servidor, não o que ficou no cache.
 */
export function useStockDetail(productId: string) {
  return useQuery({
    queryKey: stockDetailQueryKey(productId),
    queryFn: () => getStock(productId),
    staleTime: 0,
  });
}
