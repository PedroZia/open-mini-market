import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { formatMoney } from '../../../shared/lib/money';
import {
  isCashSessionAlreadyClosed,
  isCashSessionNotOpen,
  isSessionHasOpenSales,
} from '../../../shared/lib/problem';
import { showToast, type ToastTone } from '../../../shared/ui/Toast';
import {
  closeCashSession,
  getCashSession,
  getCashSessionSummary,
  getCurrentCashSession,
  listCashRegisters,
  recordSupply,
  recordWithdrawal,
  type CashMovementRequest,
  type CashMovementResponse,
  type CloseCashSessionRequest,
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

/**
 * Recarrega o caixa quando a escrita falha por conflito de estado (1210b): sessão que fechou,
 * caixa sem sessão aberta ou venda em andamento. A mensagem continua no modal; o que sai da tela é
 * o estado velho — o resumo inválido é lido do servidor de novo.
 */
function reloadOnStateConflict(queryClient: QueryClient, error: unknown): void {
  if (
    isCashSessionNotOpen(error) ||
    isCashSessionAlreadyClosed(error) ||
    isSessionHasOpenSales(error)
  ) {
    void queryClient.invalidateQueries({ queryKey: cashQueryKey });
  }
}

/** O recado da sangria: acima do esperado vira aviso, nunca erro — o movimento foi gravado (BR-12). */
function withdrawalToast(movement: CashMovementResponse): { text: string; tone: ToastTone } {
  if (movement.aboveExpected !== true) {
    return { text: 'Sangria registrada.', tone: 'success' };
  }
  const expected = movement.expectedAfter;
  return expected === undefined
    ? {
        text: 'Sangria registrada acima do esperado. Confira o dinheiro em caixa.',
        tone: 'warning',
      }
    : {
        text: `Sangria registrada acima do esperado: o esperado em caixa agora é ${formatMoney(expected)}.`,
        tone: 'warning',
      };
}

/**
 * Sangria (1210b, `cash.withdrawal` no servidor): sucesso avisa no toast e invalida o prefixo
 * `cash` — lista, sessão atual, detalhe e resumo saem do servidor de novo, porque o esperado mudou
 * (BR-12). O erro fica no modal (o toast global só o repetiria) e o conflito de estado relê a tela.
 */
export function useRecordWithdrawal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { cashRegisterId: string; body: CashMovementRequest }) =>
      recordWithdrawal(variables.cashRegisterId, variables.body),
    meta: { suppressErrorToast: true },
    onSuccess: (movement) => {
      void queryClient.invalidateQueries({ queryKey: cashQueryKey });
      const { text, tone } = withdrawalToast(movement);
      showToast(text, tone);
    },
    onError: (error: unknown) => {
      reloadOnStateConflict(queryClient, error);
    },
  });
}

/**
 * Suprimento (1210b, `cash.supply` no servidor): mesma forma e mesmo ciclo da sangria — o
 * `aboveExpected` do corpo é sempre `false`, então o sucesso é só confirmação.
 */
export function useRecordSupply() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { cashRegisterId: string; body: CashMovementRequest }) =>
      recordSupply(variables.cashRegisterId, variables.body),
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: cashQueryKey });
      showToast('Suprimento registrado.', 'success');
    },
    onError: (error: unknown) => {
      reloadOnStateConflict(queryClient, error);
    },
  });
}

/**
 * Fechamento (1210b, `cash.close` no servidor): confere o contado e fecha a sessão; sucesso avisa e
 * invalida o prefixo `cash` — a sessão atual passa a responder 404 `CASH_SESSION_NOT_OPEN` e a tela
 * mostra o caixa sem sessão aberta. O 409 `SESSION_HAS_OPEN_SALES` mantém o modal com o recado e
 * relê o estado, porque a venda aberta nasceu fora da tela.
 */
export function useCloseCashSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { cashRegisterId: string; body: CloseCashSessionRequest }) =>
      closeCashSession(variables.cashRegisterId, variables.body),
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: cashQueryKey });
      showToast('Caixa fechado.', 'success');
    },
    onError: (error: unknown) => {
      reloadOnStateConflict(queryClient, error);
    },
  });
}
