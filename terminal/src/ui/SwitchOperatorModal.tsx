import { Box, Text, useInput } from 'ink';
import { useRef, useState } from 'react';

import type { TerminalApi } from '../api/terminalApi';
import type { ApiProblem } from '../core/state';

/**
 * Troca de operador (F12, passo 1118): confirmação shell-local aberta **no lugar** do corpo da
 * venda, como o desconto (1111), o cliente (1112), a gaveta (1114) e o cancelamento (1115) — o
 * overlay desmonta a venda, então a rajada do leitor não vira item enquanto ele está à vista, e no
 * contexto `switchOperator` do mapa (§11.3) só o ESC do canal cru do shell atua.
 *
 * A tela tem duas caras, decididas pela venda aberta: **sem itens** é uma confirmação leve — o F12
 * jamais encerra a sessão por engano e o ENTER troca direto; **com itens** bloqueia a troca
 * silenciosa e diz o que o ENTER faz ("há venda aberta — ENTER cancela a venda e troca de
 * operador"): o ENTER cancela a venda no servidor e só então encerra a sessão.
 *
 * O motivo do cancelamento é **fixo** — `troca de operador` (BR-04 exige um motivo, e o próprio
 * F12 já o explica): cancelar é a única saída da venda para o próximo operador entrar no caixa, e
 * digitar um motivo só adicionaria um passo a uma confirmação que o operador já leu. A
 * `Idempotency-Key` é desta tentativa: o retry reusa a mesma e uma resposta perdida vira replay no
 * servidor, sem um segundo cancelamento.
 *
 * A sessão de **login** termina (o `logout` revoga e esquece o token mesmo se a revogação falhar,
 * como no 1107), mas a sessão de **caixa continua aberta**: o próximo operador entra pelo login e
 * retoma o mesmo caixa (o `open` responde `CASH_REGISTER_ALREADY_OPEN` e o 1107 segue com a sessão
 * existente). Recusa do cancelamento (403 sem `sale.cancel`, 400 do motivo) fica **aqui**, com a
 * venda intacta: nada de troca silenciosa; rede/5xx avisam e o mesmo ENTER refaz; a falha
 * bloqueante (404/409/contrato) vai para a tela de erro pela mão do shell.
 */

export type SwitchOperatorModalProps = {
  /** Venda aberta a cancelar quando ela tem itens; `null` quando não há venda criada (1109). */
  saleId: string | null;
  /** Itens da venda aberta: com item o bloqueio é explícito; sem item a troca é direta. */
  itemCount: number;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Sessão encerrada: o shell limpa as anotações locais e volta ao login (o caixa segue aberto). */
  onSwitched: () => void;
  /** Falha bloqueante do cancelamento: o shell fecha o modal e leva o problema ao reducer. */
  onFailed: (problem: ApiProblem) => void;
};

/** Motivo fixo do cancelamento (BR-04): o F12 já diz por que a venda foi descartada. */
const SWITCH_REASON = 'troca de operador';
const SWITCHING = 'encerrando a sessão…';
const RETRY_NOTICE = 'falha ao cancelar a venda — ENTER tenta de novo';
const DIRECT_HINT = 'ENTER troca de operador · ESC volta';
const BLOCKED_HINT = 'ENTER cancela a venda e troca de operador · ESC volta';

/** Rodapé do modal: recusa do servidor ou falha transitória do cancelamento. */
type Message = { kind: 'rejected' | 'retry'; text: string };

export function SwitchOperatorModal({
  saleId,
  itemCount,
  api,
  onSwitched,
  onFailed,
}: SwitchOperatorModalProps) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de um segundo cancelamento.
   */
  const cancelKey = useRef<string | null>(null);
  const withItems = itemCount > 0 && saleId !== null;

  useInput((input, key) => {
    // requisição em andamento: ENTER repetido não cancela nem revoga duas vezes
    if (busy) {
      return;
    }

    // ESC é do shell (canal cru), como nos demais modais: fecha sem passar por aqui
    if (key.escape || key.ctrl || key.meta) {
      return;
    }

    if (key.return) {
      void switchOperator();
    }
  });

  /**
   * ENTER da confirmação: com venda aberta cancela primeiro (é o `cancelSale` que a libera) e só
   * depois encerra a sessão; sem venda, a troca é só o fim da sessão de login. A falha do `logout`
   * não prende a troca: o contrato da camada de API não rejeita e esquece o token local de qualquer
   * forma (1107) — a sessão órfã expira no idle timeout do servidor.
   */
  async function switchOperator(): Promise<void> {
    setMessage(null);
    setBusy(true);

    if (withItems && saleId !== null) {
      cancelKey.current ??= crypto.randomUUID();
      const outcome = await api.cancelSale(saleId, SWITCH_REASON, cancelKey.current);

      if (!outcome.ok) {
        setBusy(false);

        if (outcome.kind === 'rejected') {
          // recusa não cancelou nada: a venda continua e a troca não acontece
          cancelKey.current = null;
          setMessage({ kind: 'rejected', text: outcome.message });
          return;
        }

        if (outcome.kind === 'retryable') {
          // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
          setMessage({ kind: 'retry', text: RETRY_NOTICE });
          return;
        }

        onFailed(outcome.problem);
        return;
      }

      cancelKey.current = null;
    }

    await api.logout();
    onSwitched();
  }

  return (
    <Box flexDirection="column">
      <Text bold>Trocar operador (F12)</Text>
      <Text> </Text>
      {withItems ? (
        <Text color="yellow" bold wrap="truncate-end">
          há venda aberta com {itemCount} {itemCount === 1 ? 'item' : 'itens'} — a venda será
          cancelada
        </Text>
      ) : null}
      <Text color="yellow" bold wrap="truncate-end">
        {withItems ? BLOCKED_HINT : `a sessão será encerrada · ${DIRECT_HINT}`}
      </Text>
      {busy ? (
        <Text dimColor>{SWITCHING}</Text>
      ) : message === null ? null : (
        <MessageRow message={message} />
      )}
    </Box>
  );
}

/** Rodapé: amarelo na falha transitória (retry) e vermelho na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  return (
    <Text color={message.kind === 'retry' ? 'yellow' : 'red'} wrap="truncate-end">
      {message.text}
    </Text>
  );
}
