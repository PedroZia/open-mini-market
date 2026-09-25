import { Box, Text, useInput } from 'ink';
import { useRef, useState } from 'react';

import type { CashMovementKind, CashMovementView, TerminalApi } from '../api/terminalApi';
import { centsToAmount, digitsToCents, formatAmount, formatBRL } from '../core/money';
import type { ApiProblem } from '../core/state';

/**
 * Sangria (F7) e suprimento (F8), passo 1114: modal bloqueante que o shell abre **no lugar** do
 * corpo da venda, como o desconto (1111) e o cliente (1112) — o overlay desmonta a venda, então a
 * rajada do leitor não vira item enquanto ele está à vista, e no contexto `withdrawal`/`supply` do
 * mapa (§11.3) só o ESC do canal cru do shell atua: os demais atalhos ficam bloqueados e o ESC
 * cancela sem chamar a API. Como aqui há formulário, o que o leitor mandar cai no campo em foco (o
 * leitor é teclado) e o terminador colado no texto não confirma nada, como no campo do 1107.
 *
 * Diferente do desconto e do cliente, a gaveta **não** depende da venda: o shell abre o modal mesmo
 * antes do primeiro bipe, porque sangrar e suprir são operações do caixa aberto (BR-10).
 *
 * A TUI não calcula dinheiro nenhum (BR-12): ela manda valor e motivo (`POST .../withdrawals` ou
 * `.../supplies`, passos 609/610) e mostra o movimento que o servidor gravou, com o esperado antes e
 * depois da mesma transação. Sangria acima do esperado **não** é bloqueada por ele: volta com
 * `aboveExpected = true` e vira alerta visível aqui (609). O ENTER do formulário só **confirma**
 * ("confirmar sangria de R$ X?") — é o ENTER da confirmação que chama a API —, e o ENTER depois do
 * sucesso fecha o modal. Formulário vazio (valor ou motivo) não chama nada e mostra a dica.
 *
 * Recusas do servidor — 403 sem `cash.withdrawal`/`cash.supply` (BR-10), 400 do valor/motivo e
 * 404/409 da sessão do caixa que mudou por fora — ficam **no próprio modal**, que volta ao
 * formulário com o que foi digitado; rede/5xx mostram o aviso de retry e o ENTER refaz a chamada com
 * a **mesma** `Idempotency-Key` (retry não sangra duas vezes, §8); a falha bloqueante (contrato) vai
 * para a tela de erro pela mão do shell.
 */

export type CashMovementModalProps = {
  /** Caixa da sessão (`{id}` das rotas de sangria/suprimento, passos 609/610): vem do shell. */
  registerId: string;
  /** Qual movimento o operador pediu — muda rótulos e rota; o corpo e a resposta são os mesmos. */
  kind: CashMovementKind;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** ENTER depois do sucesso: o shell tira o modal de cena. */
  onClosed: () => void;
  /** Falha bloqueante: o shell fecha o modal e leva o problema ao reducer (`apiFailed`). */
  onFailed: (problem: ApiProblem) => void;
};

/** Campos do formulário, no ciclo do TAB. */
type Field = 'amount' | 'reason';

/** Textos do movimento: título, frases com artigo e o rótulo das mensagens, por F7/F8. */
const TEXTS: Readonly<
  Record<
    CashMovementKind,
    { title: string; label: string; article: 'da' | 'do'; registered: string; retry: string }
  >
> = {
  withdrawal: {
    title: 'Sangria (F7)',
    label: 'sangria',
    article: 'da',
    registered: 'sangria registrada',
    retry: 'falha ao registrar a sangria — ENTER tenta de novo',
  },
  supply: {
    title: 'Suprimento (F8)',
    label: 'suprimento',
    article: 'do',
    registered: 'suprimento registrado',
    retry: 'falha ao registrar o suprimento — ENTER tenta de novo',
  },
};

/** Máscara do valor, no mesmo espírito do campo da abertura de caixa (1107) e do desconto (1111). */
const VALUE_HINT = 'digite o valor em centavos: 1000 vira R$ 10,00';
const KEY_HINT = 'TAB troca o campo · ENTER confirma · ESC cancela';
const SENDING = 'enviando…';

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: 'hint' | 'rejected' | 'retry'; text: string };

export function CashMovementModal({
  registerId,
  kind,
  api,
  onClosed,
  onFailed,
}: CashMovementModalProps) {
  const texts = TEXTS[kind];
  /** Dígitos do valor, sem máscara: `1000` é o estado; `R$ 10,00` é o que se vê. */
  const [digits, setDigits] = useState('');
  const [reason, setReason] = useState('');
  const [field, setField] = useState<Field>('amount');
  /** Formulário validado esperando o ENTER da confirmação; nada foi à API ainda. */
  const [confirming, setConfirming] = useState(false);
  /** Movimento que o servidor gravou: com ele à vista o modal só espera o ENTER que o fecha. */
  const [movement, setMovement] = useState<CashMovementView | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de uma segunda sangria; muda o pedido (ou dá recusa), chave nova.
   */
  const movementKey = useRef<string | null>(null);

  useInput((input, key) => {
    // requisição em andamento: ENTER repetido não dispara outra chamada
    if (busy) {
      return;
    }

    // ESC é do shell (canal cru), como no desconto: cancela sem passar por aqui
    if (key.escape || key.ctrl || key.meta) {
      return;
    }

    // sucesso à vista: o ENTER fecha o modal — não há mais nada a confirmar
    if (movement !== null) {
      if (key.return) {
        onClosed();
      }

      return;
    }

    // confirmação: os campos estão travados e a única tecla é o ENTER que chama a API
    if (confirming) {
      if (key.return) {
        void send();
      }

      return;
    }

    if (key.tab) {
      setField((current) => (current === 'amount' ? 'reason' : 'amount'));
      return;
    }

    if (key.backspace || key.delete) {
      setMessage(null);
      movementKey.current = null;
      if (field === 'amount') {
        setDigits((current) => current.slice(0, -1));
      } else {
        setReason((current) => current.slice(0, -1));
      }

      return;
    }

    // o terminador do leitor pode vir colado no texto: não é texto nem confirma (como no 1107)
    const typed = input.replace(/[\r\n]/g, '');
    // a confirmação vai com os dígitos que este mesmo chunk acrescentou, sem esperar o re-render
    const nextDigits = field === 'amount' ? digits + typed.replace(/\D/g, '') : digits;
    const nextReason = field === 'reason' ? reason + typed : reason;

    if (nextDigits !== digits || nextReason !== reason) {
      setMessage(null);
      movementKey.current = null; // mudou o pedido: a chave do retry anterior não vale
      setDigits(nextDigits);
      setReason(nextReason);
    }

    if (key.return) {
      askConfirmation(nextDigits, nextReason);
    }
  });

  /** ENTER do formulário: sem valor ou sem motivo não há confirmação — a dica fica no modal. */
  function askConfirmation(valueDigits: string, reasonText: string): void {
    if (valueDigits === '') {
      setMessage({ kind: 'hint', text: `informe o valor ${texts.article} ${texts.label}` });
      return;
    }

    if (reasonText.trim() === '') {
      setMessage({ kind: 'hint', text: `informe o motivo ${texts.article} ${texts.label}` });
      return;
    }

    setMessage(null);
    setConfirming(true);
  }

  /**
   * ENTER da confirmação: manda valor (centavos → reais) e motivo como o operador os digitou e
   * mostra o movimento do servidor. A recusa volta ao formulário com a mensagem; a transitória
   * fica na confirmação com o aviso de retry e a mesma chave.
   */
  async function send(): Promise<void> {
    setMessage(null);
    setBusy(true);

    movementKey.current ??= crypto.randomUUID();
    const intent = { amount: centsToAmount(digitsToCents(digits)), reason };
    const outcome =
      kind === 'withdrawal'
        ? await api.withdrawCash(registerId, intent, movementKey.current)
        : await api.supplyCash(registerId, intent, movementKey.current);

    setBusy(false);

    if (outcome.ok) {
      movementKey.current = null;
      setMovement(outcome.movement); // o shell fecha com o ENTER do operador
      return;
    }

    if (outcome.kind === 'rejected') {
      // recusa não é gravada no servidor: volta ao formulário com a mensagem e o que foi digitado
      movementKey.current = null;
      setConfirming(false);
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    if (outcome.kind === 'retryable') {
      // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
      setMessage({ kind: 'retry', text: texts.retry });
      return;
    }

    onFailed(outcome.problem);
  }

  if (movement !== null) {
    return (
      <Box flexDirection="column">
        <Text bold>{texts.title}</Text>
        <Text> </Text>
        <Text color="green">
          {texts.registered}: {formatAmount(movement.amount)}
        </Text>
        <Text>esperado antes: {formatAmount(movement.expectedBefore)}</Text>
        <Text bold>esperado agora: {formatAmount(movement.expectedAfter)}</Text>
        {movement.aboveExpected ? (
          <Text color="red" bold wrap="truncate-end">
            ATENÇÃO: {texts.label} acima do esperado — confira a gaveta
          </Text>
        ) : null}
        <Text> </Text>
        <Text dimColor>ENTER fecha</Text>
      </Box>
    );
  }

  /** Valor dos dígitos em centavos e em reais: `1000` → `R$ 10,00` na tela e `10` no corpo. */
  const cents = digitsToCents(digits);
  const amount = centsToAmount(cents);

  if (confirming) {
    return (
      <Box flexDirection="column">
        <Text bold>{texts.title}</Text>
        <Text> </Text>
        <Text>Valor: {formatAmount(amount)}</Text>
        <Text>Motivo: {reason}</Text>
        <Text> </Text>
        <Text color="yellow" bold wrap="truncate-end">
          {`confirmar ${texts.label} de ${formatAmount(amount)}? ENTER confirma · ESC cancela`}
        </Text>
        {busy ? (
          <Text dimColor>{SENDING}</Text>
        ) : message === null ? null : (
          <MessageRow message={message} />
        )}
      </Box>
    );
  }

  const amountText = formatBRL(cents);

  return (
    <Box flexDirection="column">
      <Text bold>{texts.title}</Text>
      <Text> </Text>
      <Text>
        {field === 'amount' ? '›' : ' '} Valor:{' '}
        {digits === '' ? <Text dimColor>{amountText}</Text> : amountText}
      </Text>
      {digits === '' ? <Text dimColor>{VALUE_HINT}</Text> : null}
      <Text>
        {field === 'reason' ? '›' : ' '} Motivo: {reason}
      </Text>
      {message === null ? null : <MessageRow message={message} />}
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
