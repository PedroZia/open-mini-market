import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isSaleAlreadyCompleted } from '../../../shared/lib/problem';
import { showToast } from '../../../shared/ui/Toast';
import {
  cancelSale,
  getSale,
  listOperators,
  listSaleAuditEvents,
  listSales,
  type SaleCancelRequest,
  type SaleQuery,
} from '../api/salesApi';

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

/** Chave do detalhe da venda — filha de `salesQueryKey`, então a invalidação a alcança. */
export function saleDetailQueryKey(saleId: string) {
  return [...salesQueryKey, 'detail', saleId] as const;
}

/**
 * Detalhe da venda (1209b). `staleTime: 0` porque é leitura de investigação: os totais e o status
 * que a tela mostra são os do servidor, nunca um retrato velho do cache (BR-12).
 */
export function useSaleDetail(saleId: string) {
  return useQuery({
    queryKey: saleDetailQueryKey(saleId),
    queryFn: () => getSale(saleId),
    staleTime: 0,
  });
}

/** Chave da trilha de auditoria da venda — também sob o prefixo, para o cancelamento relê-la. */
export function saleAuditQueryKey(saleId: string) {
  return [...salesQueryKey, 'audit', saleId] as const;
}

/**
 * Trilha de auditoria da venda, só quando a sessão tem `audit.read` (`enabled`): sem a permissão a
 * query nem sai — o servidor recusaria a rota. `staleTime: 0` porque a trilha é o rastro da
 * operação: depois de um cancelamento ela precisa vir do servidor de novo.
 */
export function useSaleAuditEvents(saleId: string, enabled: boolean) {
  return useQuery({
    queryKey: saleAuditQueryKey(saleId),
    queryFn: () => listSaleAuditEvents(saleId),
    enabled,
    staleTime: 0,
  });
}

/**
 * Cancelamento (`sale.cancel`): sucesso avisa no toast e invalida o prefixo `sales` — lista, detalhe
 * e trilha saem do servidor de novo. O erro fica no modal (o toast global só o repetiria); o 409
 * `SALE_ALREADY_COMPLETED` (venda concluída antes do clique) também invalida, porque a situação na
 * tela estava velha.
 */
export function useCancelSale() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { saleId: string; body: SaleCancelRequest }) =>
      cancelSale(variables.saleId, variables.body),
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: salesQueryKey });
      showToast('Venda cancelada.', 'success');
    },
    onError: (error: unknown) => {
      if (isSaleAlreadyCompleted(error)) {
        void queryClient.invalidateQueries({ queryKey: salesQueryKey });
      }
    },
  });
}
