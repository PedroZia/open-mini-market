import { Box, Text, useInput } from 'ink';
import { useState, type Dispatch } from 'react';

import type { TerminalApi } from '../api/terminalApi';
import { centsToAmount, digitsToCents, formatBRL } from '../core/money';
import type { Action } from '../core/reducer';
import type { OpeningCashState } from '../core/state';

/**
 * Abertura de caixa (passo 1107): o operador informa o fundo de troco e o servidor abre a sessão
 * (`POST /cash-registers/{id}/open`, 201 `CashSessionResponse`). O campo é mascarado em centavos —
 * os dígitos viram reais (`1250` → `R$ 12,50`) — e a tela não calcula nada (BR-12): envia o valor
 * digitado e segue com a sessão que o servidor devolveu.
 *
 * Caixa já aberto (409 `CASH_REGISTER_ALREADY_OPEN`) não é falha: a tela busca a sessão existente
 * (`GET /cash-registers/{id}/current-session`), avisa e espera o ENTER para seguir para a venda com
 * ela — sem reabrir nada. Se nem a sessão corrente puder ser lida, aí sim é falha bloqueante.
 *
 * A tela não decide o destino: relata os fatos ao reducer (1103) — `cashOpened` leva para a venda e
 * `apiFailed` vai para a tela de erro, que volta para cá.
 */

export type OpeningCashScreenProps = {
  /** Estado do reducer: operador e caixa escolhidos no login (1106). */
  state: OpeningCashState;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>;
};

/** Caixa já aberto: o aviso que a tela dá quando o servidor responde 409. */
const ALREADY_OPEN_NOTICE = 'caixa já está aberto — seguindo para a venda com a sessão existente';

export function OpeningCashScreen({ state, api, dispatch }: OpeningCashScreenProps) {
  /** Dígitos do campo, sem máscara: `1250` é o estado; `R$ 12,50` é o que se vê. */
  const [digits, setDigits] = useState('');
  const [busy, setBusy] = useState(false);
  /** Sessão existente do caixa já aberto aguardando o ENTER; `null` enquanto o campo está ativo. */
  const [alreadyOpenSessionId, setAlreadyOpenSessionId] = useState<string | null>(null);
  /** Aviso local do formulário (valor vazio); a falha da API vai para a tela de erro. */
  const [hint, setHint] = useState<string | null>(null);

  useInput((input, key) => {
    // requisição em andamento: ENTER repetido não dispara duas aberturas
    if (busy) {
      return;
    }

    if (alreadyOpenSessionId !== null) {
      if (key.return) {
        dispatch({ type: 'cashOpened', sessionId: alreadyOpenSessionId });
      }
      return;
    }

    if (key.return) {
      void submit();
      return;
    }

    if (key.backspace || key.delete) {
      setHint(null);
      setDigits((current) => current.slice(0, -1));
      return;
    }

    if (key.ctrl || key.meta) {
      return;
    }

    // a máscara é de dígitos: letra, sinal ou espaço não entram no campo
    const typed = input.replace(/\D/g, '');
    if (typed === '') {
      return;
    }

    setHint(null);
    setDigits((current) => current + typed);
  });

  async function submit(): Promise<void> {
    if (digits === '') {
      setHint('informe o valor de abertura');
      return;
    }

    setHint(null);
    setBusy(true);

    const outcome = await api.openCashRegister(
      state.register.id,
      centsToAmount(digitsToCents(digits)),
    );

    if (outcome.ok) {
      setBusy(false);
      dispatch({ type: 'cashOpened', sessionId: outcome.sessionId });
      return;
    }

    if (outcome.kind === 'alreadyOpen') {
      const current = await api.currentCashSession(state.register.id);
      setBusy(false);

      if (!current.ok) {
        // o caixa estava aberto e a sessão não pôde ser lida: sem sessão não há venda — bloqueia
        dispatch({ type: 'apiFailed', problem: current.problem });
        return;
      }

      setAlreadyOpenSessionId(current.sessionId);
      return;
    }

    setBusy(false);
    dispatch({ type: 'apiFailed', problem: outcome.problem });
  }

  const amount = formatBRL(digitsToCents(digits));

  return (
    <Box flexDirection="column">
      <Text bold>Abertura de caixa</Text>
      <Text>
        Operador: {state.operator.name} · Caixa: {state.register.name}
      </Text>
      <Text> </Text>
      <Text>
        Fundo de troco: {digits === '' ? <Text dimColor>{amount}</Text> : amount}
      </Text>
      {digits === '' ? <Text dimColor>digite o valor de abertura: 1250 vira R$ 12,50</Text> : null}
      {hint === null ? null : <Text color="red">{hint}</Text>}
      {alreadyOpenSessionId === null ? null : (
        <Text color="yellow">Aviso: {ALREADY_OPEN_NOTICE}</Text>
      )}
      {busy ? <Text dimColor>abrindo...</Text> : null}
      <Text> </Text>
      <Text dimColor>
        {alreadyOpenSessionId === null ? 'BACKSPACE corrige · ENTER abre o caixa' : 'ENTER continua'}
      </Text>
    </Box>
  );
}
