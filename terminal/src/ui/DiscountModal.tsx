import { Box, Text, useInput } from 'ink';
import { useState } from 'react';

import type { DiscountType, TerminalApi } from '../api/terminalApi';
import { centsToAmount, digitsToCents, formatBRL } from '../core/money';
import type { ApiProblem, SaleView } from '../core/state';

/**
 * Desconto na venda (F5, passo 1111): modal bloqueante que o shell abre **no lugar** do corpo da
 * venda — como o autoteste do leitor (1108), o overlay desmonta a venda, então a rajada do leitor
 * não vira item enquanto ele está à vista, e no contexto `discount` do mapa (§11.3) só o ESC do
 * canal cru do shell atua: os demais atalhos ficam bloqueados e o ESC cancela sem chamar a API.
 * Como aqui há formulário, o que o leitor mandar cai no campo em foco (o leitor é teclado): o
 * terminador colado no texto não aplica nada, como no campo da abertura de caixa (1107).
 *
 * A TUI não calcula desconto nenhum (BR-12): manda tipo, valor e motivo ao servidor (`PUT
 * /sales/{id}/discount`, passos 810/811b) e recebe a venda inteira com subtotal, desconto e total
 * recalculados. Quem recusa é o servidor: limite da loja (422), permissão `sale.discount.apply`
 * (403) e forma/motivo (400) voltam como mensagem **no próprio modal**, sem fechá-lo, para o
 * operador corrigir e tentar de novo; rede/5xx mostram o aviso de retry e o ENTER refaz; uma falha
 * bloqueante (404/409/contrato) vai para a tela de erro pela mão do shell.
 *
 * Máscaras do formulário, no mesmo espírito do campo do 1107: VALOR trabalha em centavos — `1000`
 * vira `R$ 10,00` e vai como `10` no corpo; PERCENTUAL trabalha em dígitos inteiros — `10` vira
 * `10%`. Motivo é texto livre e obrigatório (BR-04): vazio não chama a API e mostra a dica. O TAB
 * percorre os campos (valor → motivo → tipo) e, com o foco no tipo, ←/→ alternam entre os dois —
 * os dois ficam à vista, com o ativo entre colchetes.
 */

export type DiscountModalProps = {
  /** Venda aberta que recebe o desconto; o id vem do estado do shell (1103). */
  saleId: string;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Aplicado: o shell registra a venda do servidor (`saleUpdated`) e fecha o modal. */
  onApplied: (sale: SaleView) => void;
  /** Falha bloqueante: o shell fecha o modal e leva o problema ao reducer (`apiFailed`). */
  onFailed: (problem: ApiProblem) => void;
};

/** Campos do formulário, na ordem em que o TAB os percorre. */
type Field = 'value' | 'reason' | 'type';

const FIELD_ORDER: readonly Field[] = ['value', 'reason', 'type'];

/** Dicas do valor por tipo: a máscara de cada um, na linha de baixo do campo. */
const VALUE_HINT = 'digite o valor em centavos: 1000 vira R$ 10,00';
const PERCENT_HINT = 'digite o percentual inteiro: 10 vira 10%';

/** Validação de forma, só do formulário: o limite da loja e o resto são do servidor (BR-12). */
const MISSING_VALUE = 'informe o valor do desconto';
const MISSING_REASON = 'informe o motivo do desconto';

const APPLYING = 'aplicando…';
const RETRY_NOTICE = 'falha ao aplicar o desconto — ENTER tenta de novo';
const KEY_HINT = 'TAB troca o campo · ←/→ no tipo · ENTER aplica · ESC cancela';

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: 'hint' | 'rejected' | 'retry'; text: string };

export function DiscountModal({ saleId, api, onApplied, onFailed }: DiscountModalProps) {
  const [type, setType] = useState<DiscountType>('VALUE');
  /** Dígitos do campo do valor, sem máscara: `1000` é o estado; `R$ 10,00` é o que se vê. */
  const [digits, setDigits] = useState('');
  const [reason, setReason] = useState('');
  const [field, setField] = useState<Field>('value');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);

  useInput((input, key) => {
    // requisição em andamento: ENTER repetido não aplica duas vezes
    if (busy) {
      return;
    }

    // ESC é do shell (canal cru), como no autoteste do F11: cancela sem passar por aqui
    if (key.escape || key.ctrl || key.meta) {
      return;
    }

    if (key.tab) {
      setField(nextField);
      return;
    }

    if (field === 'type' && (key.leftArrow || key.rightArrow)) {
      setMessage(null);
      setType((current) => (current === 'VALUE' ? 'PERCENT' : 'VALUE'));
      return;
    }

    if (key.backspace || key.delete) {
      setMessage(null);
      if (field === 'value') {
        setDigits((current) => current.slice(0, -1));
      } else if (field === 'reason') {
        setReason((current) => current.slice(0, -1));
      }

      return;
    }

    // o terminador do leitor pode vir colado no texto: não é texto nem aplica (como no campo do 1107)
    const typed = input.replace(/[\r\n]/g, '');
    // o valor é enviado com os dígitos que este mesmo chunk acrescentou, sem esperar o re-render
    const nextDigits = field === 'value' ? digits + typed.replace(/\D/g, '') : digits;
    const nextReason = field === 'reason' ? reason + typed : reason;

    if (nextDigits !== digits || nextReason !== reason) {
      setMessage(null);
      setDigits(nextDigits);
      setReason(nextReason);
    }

    if (key.return) {
      void apply(type, nextDigits, nextReason);
    }
  });

  /**
   * Aplica o desconto com o que o servidor recusar ou aceitar: o valor vai como o operador o
   * digitou (centavos → reais no VALOR) e o motivo como texto; a venda recalculada é do servidor.
   */
  async function apply(kind: DiscountType, valueDigits: string, reasonText: string): Promise<void> {
    if (valueDigits === '') {
      setMessage({ kind: 'hint', text: MISSING_VALUE });
      return;
    }

    if (reasonText.trim() === '') {
      setMessage({ kind: 'hint', text: MISSING_REASON });
      return;
    }

    setMessage(null);
    setBusy(true);

    const outcome = await api.applyDiscount(saleId, {
      type: kind,
      value: discountValue(kind, valueDigits),
      reason: reasonText,
    });

    setBusy(false);

    if (outcome.ok) {
      onApplied(outcome.sale); // o shell guarda a venda e tira o modal de cena
      return;
    }

    if (outcome.kind === 'rejected') {
      // 403/422/400 ficam aqui: o operador lê a recusa do servidor e corrige sem perder o que digitou
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    if (outcome.kind === 'retryable') {
      setMessage({ kind: 'retry', text: RETRY_NOTICE });
      return;
    }

    onFailed(outcome.problem);
  }

  const maskedValue = masked(type, digits);

  return (
    <Box flexDirection="column">
      <Text bold>Desconto na venda (F5)</Text>
      <Text> </Text>
      <Text>
        {field === 'type' ? '›' : ' '} Tipo:{' '}
        <Text bold={type === 'VALUE'} dimColor={type !== 'VALUE'}>
          {type === 'VALUE' ? '[VALOR]' : 'VALOR'}
        </Text>
        {' · '}
        <Text bold={type === 'PERCENT'} dimColor={type !== 'PERCENT'}>
          {type === 'PERCENT' ? '[PERCENTUAL]' : 'PERCENTUAL'}
        </Text>
      </Text>
      <Text>
        {field === 'value' ? '›' : ' '} Valor:{' '}
        {digits === '' ? <Text dimColor>{maskedValue}</Text> : maskedValue}
      </Text>
      {digits === '' ? (
        <Text dimColor>{type === 'VALUE' ? VALUE_HINT : PERCENT_HINT}</Text>
      ) : null}
      <Text>
        {field === 'reason' ? '›' : ' '} Motivo: {reason}
      </Text>
      {busy ? (
        <Text dimColor>{APPLYING}</Text>
      ) : message === null ? null : (
        <MessageRow message={message} />
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

/** Próximo campo no ciclo do TAB: valor → motivo → tipo → valor. */
function nextField(field: Field): Field {
  const index = FIELD_ORDER.indexOf(field);
  return FIELD_ORDER[(index + 1) % FIELD_ORDER.length] ?? 'value';
}

/**
 * Valor do corpo da API: no VALOR, centavos → reais (`1000` → `10`, máscara do 1107); no
 * PERCENTUAL, os dígitos já são o inteiro (`10` → 10%). Nenhuma conta além da máscara (BR-12).
 */
function discountValue(type: DiscountType, digits: string): number {
  return type === 'VALUE' ? centsToAmount(digitsToCents(digits)) : digitsToCents(digits);
}

/** Máscara de exibição do valor digitado, por tipo: `R$ 12,50` no VALOR e `10%` no PERCENTUAL. */
function masked(type: DiscountType, digits: string): string {
  return type === 'VALUE' ? formatBRL(digitsToCents(digits)) : `${digitsToCents(digits)}%`;
}
