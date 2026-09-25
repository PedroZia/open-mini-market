import { Box, Text, useInput } from 'ink';
import { useRef, useState } from 'react';

import type { TerminalApi } from '../api/terminalApi';
import type { ApiProblem } from '../core/state';

/**
 * Cancelamento da venda (F4, passo 1115): modal bloqueante que o shell abre **no lugar** do corpo da
 * venda, como o desconto (1111), o cliente (1112) e a gaveta (1114) — o overlay desmonta a venda,
 * então a rajada do leitor não vira item enquanto ele está à vista, e no contexto `cancelSale` do
 * mapa (§11.3) só o ESC do canal cru do shell atua: os demais atalhos ficam bloqueados e o ESC
 * cancela sem chamar a API. Como aqui há formulário, o que o leitor mandar cai no campo (o leitor é
 * teclado) e o terminador colado no texto não confirma nada, como no campo do 1107.
 *
 * É a saída para a venda vazia ou errada: a venda `OPEN` do servidor bloqueia o fechamento do caixa
 * (409 `SESSION_HAS_OPEN_SALES`), então descartá-la é o que permite encerrar o turno. O motivo é
 * obrigatório (BR-04, `SaleCancelRequest`): vazio não chama a API e mostra a dica, e o ENTER do
 * formulário só **confirma** ("cancelar a venda em andamento?") — é o ENTER da confirmação que
 * chama o `POST /sales/{id}/cancel`, com a `Idempotency-Key` da tentativa (retry não cancela duas
 * vezes, §8; a já cancelada é no-op no servidor).
 *
 * Quem cancelou de fato é o servidor: com o 200, o shell registra `saleCancelled` no reducer (volta
 * à venda vazia) e esquece a anotação local do cliente — a venda cancelada não tem mais dono. As
 * recusas — 403 sem `sale.cancel` (BR-04: quem opera a venda não a cancela) e 400 do motivo — ficam
 * **no próprio modal**, que volta ao formulário com o que foi digitado; rede/5xx mostram o aviso de
 * retry e o mesmo ENTER refaz; a falha bloqueante (404/409/contrato) vai para a tela de erro pela
 * mão do shell.
 */

export type CancelSaleModalProps = {
  /** Venda aberta que será cancelada; o id vem do estado do shell (1103). */
  saleId: string;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Cancelada no servidor: o shell volta à venda vazia e tira o modal de cena. */
  onCancelled: () => void;
  /** Falha bloqueante: o shell fecha o modal e leva o problema ao reducer (`apiFailed`). */
  onFailed: (problem: ApiProblem) => void;
};

const MISSING_REASON = 'informe o motivo do cancelamento';
const CANCELING = 'cancelando…';
const RETRY_NOTICE = 'falha ao cancelar a venda — ENTER tenta de novo';
const KEY_HINT = 'ENTER confirma · ESC volta para a venda';

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: 'hint' | 'rejected' | 'retry'; text: string };

export function CancelSaleModal({ saleId, api, onCancelled, onFailed }: CancelSaleModalProps) {
  const [reason, setReason] = useState('');
  /** Motivo validado esperando o ENTER da confirmação; nada foi à API ainda. */
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de um segundo cancelamento; muda o pedido (ou dá recusa), chave nova.
   */
  const cancelKey = useRef<string | null>(null);

  useInput((input, key) => {
    // requisição em andamento: ENTER repetido não dispara outra chamada
    if (busy) {
      return;
    }

    // ESC é do shell (canal cru), como nos demais modais: cancela sem passar por aqui
    if (key.escape || key.ctrl || key.meta) {
      return;
    }

    // confirmação: os campos estão travados e a única tecla é o ENTER que chama a API
    if (confirming) {
      if (key.return) {
        void send();
      }

      return;
    }

    if (key.backspace || key.delete) {
      setMessage(null);
      cancelKey.current = null;
      setReason((current) => current.slice(0, -1));
      return;
    }

    // o terminador do leitor pode vir colado no texto: não é texto nem confirma (como no 1107)
    const typed = input.replace(/[\r\n]/g, '');
    // a confirmação vai com o motivo que este mesmo chunk acrescentou, sem esperar o re-render
    const nextReason = reason + typed;

    if (nextReason !== reason) {
      setMessage(null);
      cancelKey.current = null; // mudou o pedido: a chave do retry anterior não vale
      setReason(nextReason);
    }

    if (key.return) {
      askConfirmation(nextReason);
    }
  });

  /** ENTER do formulário: sem motivo não há confirmação — a dica fica no modal. */
  function askConfirmation(reasonText: string): void {
    if (reasonText.trim() === '') {
      setMessage({ kind: 'hint', text: MISSING_REASON });
      return;
    }

    setMessage(null);
    setConfirming(true);
  }

  /**
   * ENTER da confirmação: manda o motivo como o operador o digitou e o servidor cancela a venda. A
   * recusa volta ao formulário com a mensagem; a transitória fica na confirmação com o aviso de
   * retry e a mesma chave.
   */
  async function send(): Promise<void> {
    setMessage(null);
    setBusy(true);

    cancelKey.current ??= crypto.randomUUID();
    const outcome = await api.cancelSale(saleId, reason, cancelKey.current);

    setBusy(false);

    if (outcome.ok) {
      cancelKey.current = null;
      onCancelled(); // o shell zera a venda e tira o modal de cena
      return;
    }

    if (outcome.kind === 'rejected') {
      // recusa não cancelou nada no servidor: volta ao formulário com o motivo digitado
      cancelKey.current = null;
      setConfirming(false);
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    if (outcome.kind === 'retryable') {
      // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
      setMessage({ kind: 'retry', text: RETRY_NOTICE });
      return;
    }

    onFailed(outcome.problem);
  }

  if (confirming) {
    return (
      <Box flexDirection="column">
        <Text bold>Cancelar a venda (F4)</Text>
        <Text> </Text>
        <Text>Motivo: {reason}</Text>
        <Text> </Text>
        <Text color="yellow" bold wrap="truncate-end">
          cancelar a venda em andamento? ENTER confirma · ESC volta
        </Text>
        {busy ? (
          <Text dimColor>{CANCELING}</Text>
        ) : message === null ? null : (
          <MessageRow message={message} />
        )}
      </Box>
    );
  }

  return (
    <Box flexDirection="column">
      <Text bold>Cancelar a venda (F4)</Text>
      <Text> </Text>
      <Text>› Motivo: {reason}</Text>
      {message === null ? null : <MessageRow message={message} />}
      <Text> </Text>
      <Text dimColor>a venda em andamento será descartada · {KEY_HINT}</Text>
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
