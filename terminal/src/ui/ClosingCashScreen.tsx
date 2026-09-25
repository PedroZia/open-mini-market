import { Box, Text, useInput } from 'ink';
import { useEffect, useRef, useState, type Dispatch } from 'react';

import type { CashSessionSummaryView, TerminalApi } from '../api/terminalApi';
import { centsToAmount, digitsToCents, formatAmount, formatBRL } from '../core/money';
import type { Action } from '../core/reducer';
import type { ClosingCashState } from '../core/state';

/**
 * Fechamento de caixa (F10, passo 1115): a tela do fim do turno. Ela busca o resumo do servidor
 * (`GET /cash-sessions/{id}/summary`, passos 612/909) — aberto, esperado, quebra das vendas por
 * forma de pagamento e sangrias/suprimentos —, o operador digita o **valor contado** com a máscara
 * de centavos (como a abertura do 1107 e a gaveta do 1114) e confirma duas vezes: o ENTER do
 * formulário só pede a confirmação, é o ENTER da confirmação que chama o `close`.
 *
 * A TUI não calcula nada (BR-12): o esperado é do resumo e a **diferença é a do servidor**, exibida
 * no bloco do fechamento depois do 200 — contado e esperado lado a lado, sem conta local (BR-12). A
 * `Idempotency-Key` é desta tela (§8): o retry do ENTER reusa a mesma chave e uma resposta perdida
 * vira replay, sem fechar duas vezes.
 *
 * Recusas ficam **na própria tela**, sem perder o que foi digitado: 403 sem `cash.close` (BR-10),
 * 400 do valor e o 409 `SESSION_HAS_OPEN_SALES` — venda em andamento no caixa, que o operador
 * resolve voltando com o ESC e cancelando a venda no F4 (o próprio texto da mensagem diz isso).
 * Rede/5xx mostram o aviso de retry e o mesmo ENTER refaz; qualquer outra falha bloqueia na tela de
 * erro pela mão do shell, que volta para cá e o resumo é relido.
 *
 * **Logout opcional:** com o caixa fechado o turno acabou, mas a sessão de login pode ser reusada
 * (troca de operador é o 1118). O ENTER encerra a sessão de verdade (`POST /auth/logout`, que
 * esquece o token mesmo se a revogação falhar) e volta ao login; **qualquer outra tecla** volta ao
 * login sem revogar — o token antigo morre sozinho no idle timeout (passo 206) e é substituído no
 * próximo login. O ESC não volta para a venda com o caixa já fechado: quem decide isso é o reducer.
 */

export type ClosingCashScreenProps = {
  /** Estado do reducer: operador, caixa, sessão e a venda preservada para o ESC (1103). */
  state: ClosingCashState;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>;
};

/** As cinco formas do contrato, na ordem em que a linha do resumo as mostra (passo 909). */
const METHODS = ['CASH', 'PIX', 'DEBIT', 'CREDIT', 'VOUCHER'] as const;

/** Rótulos pt-BR das formas; a chave do mapa continua sendo a do contrato. */
const METHOD_LABELS: Readonly<Record<string, string>> = {
  CASH: 'DINHEIRO',
  PIX: 'PIX',
  DEBIT: 'DÉBITO',
  CREDIT: 'CRÉDITO',
  VOUCHER: 'VOUCHER',
};

/** Máscara do valor, no mesmo espírito dos campos do 1107 e do 1114. */
const VALUE_HINT = 'digite o valor contado em centavos: 1000 vira R$ 10,00';
const MISSING_VALUE = 'informe o valor contado';
const LOADING = 'lendo o resumo do caixa…';
const SENDING = 'fechando o caixa…';
const RETRY_NOTICE = 'falha ao fechar o caixa — ENTER tenta de novo';
const KEY_HINT = 'ENTER confirma · ESC volta para a venda';
const CLOSED_HINT = 'ENTER encerra a sessão (logout) · qualquer outra tecla volta ao login';

/** Rodapé da tela: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: 'hint' | 'rejected' | 'retry'; text: string };

export function ClosingCashScreen({ state, api, dispatch }: ClosingCashScreenProps) {
  /** Resumo do servidor; `null` enquanto a leitura não voltou (com ele à vista o campo é liberado). */
  const [summary, setSummary] = useState<CashSessionSummaryView | null>(null);
  /** Dígitos do valor contado, sem máscara: `1000` é o estado; `R$ 10,00` é o que se vê. */
  const [digits, setDigits] = useState('');
  /** Formulário validado esperando o ENTER da confirmação; nada foi à API ainda. */
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  /** Trava da saída com logout: o ENTER repetido não revoga a sessão duas vezes. */
  const leaving = useRef(false);
  const [message, setMessage] = useState<Message | null>(null);
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de um segundo fechamento; muda o pedido (ou dá recusa), chave nova.
   */
  const closingKey = useRef<string | null>(null);

  useEffect(() => {
    let live = true;

    void (async () => {
      const outcome = await api.cashSessionSummary(state.sessionId);

      // o ESC pode ter tirado a tela de cena (ou o caixa já fechou): sem tela, sem despacho
      if (!live) {
        return;
      }

      if (outcome.ok) {
        setSummary(outcome.summary);
        return;
      }

      dispatch({ type: 'apiFailed', problem: outcome.problem });
    })();

    return () => {
      live = false;
    };
  }, [api, state.sessionId, dispatch]);

  useInput((input, key) => {
    // caixa fechado: a diferença está à vista e a única saída é o login (com ou sem logout)
    if (state.closing !== null) {
      if (leaving.current) {
        return;
      }

      void leave(key.return);
      return;
    }

    // requisição em andamento: o ENTER repetido não fecha duas vezes e o ESC não abandona o envio
    if (busy) {
      return;
    }

    // ESC volta para a venda com ela preservada: quem decide é o reducer (`cancel`), como no
    // pagamento (1113) — o shell não executa os atalhos de `confirm`/`cancel`, só as intenções
    if (key.escape) {
      dispatch({ type: 'cancel' });
      return;
    }

    // resumo a caminho: o campo de valor só existe com o esperado do servidor à vista
    if (summary === null) {
      return;
    }

    // confirmação: os campos estão travados e a única tecla é o ENTER que chama a API
    if (confirming) {
      if (key.return) {
        void send();
      }

      return;
    }

    if (key.ctrl || key.meta) {
      return;
    }

    if (key.backspace || key.delete) {
      setMessage(null);
      closingKey.current = null;
      setDigits((current) => current.slice(0, -1));
      return;
    }

    // o terminador do leitor pode vir colado no texto: não é texto nem confirma (como no 1107)
    const typed = input.replace(/[\r\n]/g, '');
    // a confirmação vai com os dígitos que este mesmo chunk acrescentou, sem esperar o re-render
    const nextDigits = digits + typed.replace(/\D/g, '');

    if (nextDigits !== digits) {
      setMessage(null);
      closingKey.current = null; // mudou o pedido: a chave do retry anterior não vale
      setDigits(nextDigits);
    }

    if (key.return) {
      askConfirmation(nextDigits);
    }
  });

  /** ENTER do formulário: sem valor contado não há confirmação — a dica fica na tela. */
  function askConfirmation(valueDigits: string): void {
    if (valueDigits === '') {
      setMessage({ kind: 'hint', text: MISSING_VALUE });
      return;
    }

    setMessage(null);
    setConfirming(true);
  }

  /**
   * ENTER da confirmação: manda o contado (centavos → reais) e mostra a conferência do servidor. A
   * recusa volta ao formulário com a mensagem (a venda em andamento tem a sua, do `close`); a
   * transitória fica na confirmação com o aviso de retry e a mesma chave.
   */
  async function send(): Promise<void> {
    setMessage(null);
    setBusy(true);

    closingKey.current ??= crypto.randomUUID();
    const outcome = await api.closeCashSession(
      state.register.id,
      { countedAmount: centsToAmount(digitsToCents(digits)) },
      closingKey.current,
    );

    setBusy(false);

    if (outcome.ok) {
      closingKey.current = null;
      dispatch({ type: 'cashCloseSucceeded', closing: outcome.closing });
      return;
    }

    if (outcome.kind === 'rejected') {
      // recusa não fechou nada: volta ao formulário com o valor digitado e o que fazer na mensagem
      closingKey.current = null;
      setConfirming(false);
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    if (outcome.kind === 'retryable') {
      // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
      setMessage({ kind: 'retry', text: RETRY_NOTICE });
      return;
    }

    dispatch({ type: 'apiFailed', problem: outcome.problem });
  }

  /** Saída do caixa fechado: o ENTER revoga a sessão (logout) e as demais teclas só voltam ao login. */
  async function leave(withLogout: boolean): Promise<void> {
    leaving.current = true;

    if (withLogout) {
      await api.logout();
    }

    dispatch({ type: 'cashClosed' });
  }

  // caixa fechado: a conferência do servidor fica no lugar do formulário até o operador sair
  if (state.closing !== null) {
    const closing = state.closing;

    return (
      <Box flexDirection="column">
        <Text bold>Fechamento de caixa (F10)</Text>
        <Text>
          Operador: {state.operator.name} · Caixa: {state.register.name}
        </Text>
        <Text> </Text>
        <Text color="green" bold>
          caixa fechado
        </Text>
        <Text>
          Esperado: {formatAmount(closing.expectedAmount)} · Contado:{' '}
          {formatAmount(closing.countedAmount)}
        </Text>
        <Text color={closing.differenceAmount === 0 ? 'green' : 'red'} bold wrap="truncate-end">
          Diferença (servidor): {formatAmount(closing.differenceAmount)} —{' '}
          {differenceNote(closing.differenceAmount)}
        </Text>
        <Text> </Text>
        <Text dimColor>{CLOSED_HINT}</Text>
      </Box>
    );
  }

  const amount = formatBRL(digitsToCents(digits));

  if (confirming) {
    return (
      <Box flexDirection="column">
        <Text bold>Fechamento de caixa (F10)</Text>
        <Text> </Text>
        <Text>Valor contado: {amount}</Text>
        {summary === null ? null : <Text>Esperado: {formatAmount(summary.expectedAmount)}</Text>}
        <Text> </Text>
        <Text color="yellow" bold wrap="truncate-end">
          fechar o caixa com {amount}? ENTER confirma · ESC volta para a venda
        </Text>
        {busy ? (
          <Text dimColor>{SENDING}</Text>
        ) : message === null ? null : (
          <MessageRow message={message} />
        )}
      </Box>
    );
  }

  return (
    <Box flexDirection="column">
      <Text bold>Fechamento de caixa (F10)</Text>
      <Text>
        Operador: {state.operator.name} · Caixa: {state.register.name}
      </Text>
      <Text> </Text>
      {summary === null ? (
        <Text dimColor>{LOADING}</Text>
      ) : (
        <>
          <Text>Aberto: {formatAmount(summary.openingAmount)}</Text>
          <Text bold>Esperado: {formatAmount(summary.expectedAmount)}</Text>
          <Text>Vendas por forma de pagamento:</Text>
          {METHODS.map((method) => (
            <Text key={method} wrap="truncate-end">
              {' '}
              {METHOD_LABELS[method]}: {formatAmount(summary.paymentsByMethod[method] ?? 0)}
            </Text>
          ))}
          <Text>
            Sangrias: {formatAmount(summary.totalsByType.WITHDRAWAL ?? 0)} · Suprimentos:{' '}
            {formatAmount(summary.totalsByType.SUPPLY ?? 0)}
          </Text>
          <Text> </Text>
          <Text>
            Valor contado: {digits === '' ? <Text dimColor>{amount}</Text> : amount}
          </Text>
          {digits === '' ? <Text dimColor>{VALUE_HINT}</Text> : null}
          {message === null ? null : <MessageRow message={message} />}
        </>
      )}
      <Text> </Text>
      <Text dimColor>{KEY_HINT}</Text>
    </Box>
  );
}

/** Rodapé: amarelo na falha transitória (retry) e vermelho na dica e na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  return (
    <Text color={message.kind === 'retry' ? 'yellow' : 'red'} wrap="truncate-end">
      {message.text}
    </Text>
  );
}

/**
 * Leitura da diferença **do servidor** (BR-12): só compara com zero para dizer sobra/falta — somar,
 * subtrair ou arredondar continua sendo do servidor.
 */
function differenceNote(differenceAmount: number): string {
  if (differenceAmount === 0) {
    return 'o caixa confere';
  }

  return differenceAmount > 0 ? 'sobra dinheiro na gaveta' : 'falta dinheiro na gaveta';
}
