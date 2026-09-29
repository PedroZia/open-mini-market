import { useQuery } from '@tanstack/react-query';
import {
  getCashSession,
  getCashSessionSummary,
  getCurrentCashSession,
  listCashRegisters,
} from '../api/cashApi';

/** Prefixo do cache de caixa: as consultas da feature vivem todas sob ele. */
export const cashQueryKey = ['cash'] as const;

/**
 * Caixas da loja (1210a). `staleTime: 0` porque a situação de um caixa muda a cada abertura,
 * movimento ou fechamento — a lista é leitura de operação, não retrato de catálogo.
 */
export function useCashRegisters() {
  return useQuery({
    queryKey: [...cashQueryKey, 'registers'],
    queryFn: listCashRegisters,
    staleTime: 0,
  });
}

/** Chave da sessão atual do caixa — filha de `cashQueryKey`, então uma invalidação a alcança. */
export function currentSessionQueryKey(cashRegisterId: string) {
  return [...cashQueryKey, 'current-session', cashRegisterId] as const;
}

/**
 * Sessão atual do caixa (1210a). Sem sessão aberta o servidor responde 404 `CASH_SESSION_NOT_OPEN`
 * — é estado normal do caixa, não falha: sem `retry`, o aviso "sem sessão aberta" aparece direto em
 * vez de depois de três tentativas; o botão "Tentar de novo" cobre a falha transitória.
 */
export function useCurrentCashSession(cashRegisterId: string) {
  return useQuery({
    queryKey: currentSessionQueryKey(cashRegisterId),
    queryFn: () => getCurrentCashSession(cashRegisterId),
    staleTime: 0,
    retry: false,
  });
}

/** Chave do detalhe da sessão; separada do resumo porque são duas leituras do contrato. */
export function cashSessionQueryKey(sessionId: string) {
  return [...cashQueryKey, 'session', sessionId] as const;
}

/** Chave do resumo do fechamento da sessão. */
export function cashSessionSummaryQueryKey(sessionId: string) {
  return [...cashQueryKey, 'summary', sessionId] as const;
}

/**
 * Detalhe da sessão (1210a): leitura de conferência — `staleTime: 0` para a tela mostrar o que o
 * servidor tem agora, nunca o retrato que ficou no cache (BR-12).
 */
export function useCashSession(sessionId: string) {
  return useQuery({
    queryKey: cashSessionQueryKey(sessionId),
    queryFn: () => getCashSession(sessionId),
    staleTime: 0,
  });
}

/** Resumo do fechamento da sessão (1210a), com a mesma leitura fresca do detalhe. */
export function useCashSessionSummary(sessionId: string) {
  return useQuery({
    queryKey: cashSessionSummaryQueryKey(sessionId),
    queryFn: () => getCashSessionSummary(sessionId),
    staleTime: 0,
  });
}
