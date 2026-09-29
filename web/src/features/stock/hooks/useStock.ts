import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { showToast } from '../../../shared/ui/Toast';
import {
  adjustStock,
  getStock,
  listStock,
  receiveStock,
  type StockAdjustmentRequest,
  type StockQuery,
  type StockReceiptRequest,
} from '../api/stockApi';

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

/**
 * Ajuste (705) e entrada (706) compartilham o ciclo da escrita (1206b): o erro fica no modal, que
 * o mostra no banner — o toast global só o repetiria — e o sucesso invalida o prefixo `stock`: o
 * saldo e o histórico saem do servidor, nunca do que o cliente montou (BR-12).
 */
function useStockOperationMutation<TVariables, TResult>(
  mutationFn: (variables: TVariables) => Promise<TResult>,
  successMessage: string,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: stockQueryKey });
      showToast(successMessage, 'success');
    },
  });
}

/** Ajuste manual com motivo (`stock.adjust` no servidor); delta zero e motivo vazio são 400. */
export function useAdjustStock() {
  return useStockOperationMutation(
    (variables: { productId: string; body: StockAdjustmentRequest }) =>
      adjustStock(variables.productId, variables.body),
    'Ajuste registrado.',
  );
}

/** Entrada de mercadoria (`stock.receive` no servidor); quantidade não positiva é 400. */
export function useReceiveStock() {
  return useStockOperationMutation(
    (variables: { productId: string; body: StockReceiptRequest }) =>
      receiveStock(variables.productId, variables.body),
    'Entrada registrada.',
  );
}
