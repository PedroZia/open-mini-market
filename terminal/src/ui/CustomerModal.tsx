import { Box, Text, useInput } from 'ink';
import { useRef, useState } from 'react';

import type { CustomerOption, SendFailure, TerminalApi } from '../api/terminalApi';
import { resolveKey } from '../core/keys';
import type { ApiProblem, SaleView } from '../core/state';

/**
 * Cliente na venda (F6, passo 1112): modal bloqueante que o shell abre **no lugar** do corpo da
 * venda, como o desconto (1111) — o overlay desmonta a venda, então a rajada do leitor não vira
 * item, e no contexto `customer` do mapa (§11.3) só o ESC do canal cru do shell atua: os demais
 * atalhos ficam bloqueados e o ESC fecha sem chamar a API. Como aqui há campo de busca, o que o
 * leitor mandar cai nele (o leitor é teclado): a rajada vira termo, nunca item — e o terminador
 * colado no texto não busca nem vincula (como no campo do 1107), quem busca/vincula é o ENTER.
 *
 * O fluxo é de **busca**, não de cadastro: o operador digita nome (trecho) ou CPF e o ENTER procura
 * no servidor (`GET /customers`, passo 502) — quem decide o que é nome e o que é CPF é ele, nunca
 * esta tela (BR-12). Os resultados ficam à vista com nome e CPF; as setas escolhem (clamp nas
 * pontas) e o ENTER vincula o selecionado (`PUT /sales/{id}/customer`, passo 811b). Com cliente
 * vinculado o modal mostra o atual e o DEL remove (`DELETE`; sem vínculo, o DEL só avisa).
 *
 * O nome que o cabeçalho mostra é a **seleção local**: o `SaleDetailResponse` devolve o
 * `customerId`, não o nome, então o shell guarda o par `{id, name}` da busca e o vínculo real
 * continua sendo o do servidor. As recusas do servidor — 404 `CUSTOMER_NOT_FOUND`, 422
 * `CUSTOMER_INACTIVE`, 403 sem permissão — ficam **no próprio modal**, sem fechá-lo; rede/5xx
 * mostram o aviso de retry e o ENTER (ou o DEL) refaz a mesma chamada; a falha bloqueante (404
 * `SALE_NOT_FOUND`, 409, contrato) vai para a tela de erro pela mão do shell.
 */

export type CustomerModalProps = {
  /** Venda aberta que recebe o cliente; o id vem do estado do shell (1103). */
  saleId: string;
  /** Cliente vinculado agora, como a busca local o conhece; `null` na venda anônima. */
  customer: CustomerOption | null;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Vinculou/removeu: o shell registra a venda e o cliente da seleção (`null` ao remover), e fecha. */
  onUpdated: (sale: SaleView, customer: CustomerOption | null) => void;
  /** Falha bloqueante: o shell fecha o modal e leva o problema ao reducer (`apiFailed`). */
  onFailed: (problem: ApiProblem) => void;
};

/** Dica do campo: o termo é o do servidor (trecho do nome ou dígitos do CPF), não um formato da TUI. */
const SEARCH_HINT = 'digite o nome ou o CPF e ENTER busca';
const MISSING_TERM = 'informe o nome ou o CPF do cliente';
const NO_RESULTS = 'nenhum cliente encontrado';
const NO_CUSTOMER = 'nenhum cliente vinculado para remover';

const SEARCH_RETRY = 'falha ao buscar — ENTER tenta de novo';
const LINK_RETRY = 'falha ao vincular — ENTER tenta de novo';
const UNLINK_RETRY = 'falha ao remover — DEL tenta de novo';

const SENDING = 'enviando…';
const KEY_HINT = '↑↓ escolhe · ENTER busca/vincula · DEL remove · ESC fecha';

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: 'hint' | 'rejected' | 'retry'; text: string };

/** O que sobra do desfecho da API depois do sucesso: recusa do modal, retry manual ou falha bloqueante. */
type Refusal =
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

export function CustomerModal({ saleId, customer, api, onUpdated, onFailed }: CustomerModalProps) {
  const [term, setTerm] = useState('');
  /**
   * Espelho do termo para o ENTER: o callback do `useInput` é o do último render, e a rajada do
   * leitor pode encher o campo e mandar o terminador em dois eventos `data` seguidos.
   */
  const termRef = useRef('');
  /**
   * Resultados da última busca (`null` = nada buscado ainda): editar o termo limpa a lista, então o
   * ENTER nunca vincula um resultado que não é mais do que está escrito no campo.
   */
  const [results, setResults] = useState<CustomerOption[] | null>(null);
  /** Seleção na lista: clamp nas pontas, como na lista da venda (1108/1110). */
  const [cursor, setCursor] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);

  const index = results === null ? 0 : Math.min(cursor, Math.max(results.length - 1, 0));
  const chosen = results?.[index] ?? null;

  useInput((input, key) => {
    // requisição em andamento: ENTER/DEL repetido não dispara duas chamadas
    if (busy) {
      return;
    }

    const keyName = resolveKey(input, key);

    // ESC é do shell (canal cru), como no desconto: fecha sem passar por aqui
    if (keyName === 'ESC') {
      return;
    }

    if (keyName === 'ENTER') {
      // sem resultado à vista o ENTER busca o que está no campo (pelo ref, não pelo render); com a
      // lista à vista, vincula o selecionado
      if (chosen === null) {
        void search(termRef.current);
      } else {
        void link(chosen);
      }

      return;
    }

    if (keyName === 'DEL') {
      void unlink();
      return;
    }

    if (keyName === 'UP' || keyName === 'DOWN') {
      moveCursor(keyName === 'UP' ? -1 : 1);
      return;
    }

    if (keyName === 'BACKSPACE') {
      writeTerm(termRef.current.slice(0, -1));
      return;
    }

    // o terminador do leitor pode vir colado no texto: não é texto nem busca nada (como no 1107)
    const typed = input.replace(/[\r\n]/g, '');

    if (typed !== '') {
      writeTerm(termRef.current + typed);
    }
  });

  /**
   * Escreve no campo de busca: o estado desenha e o ref guarda o valor corrente. Mexer no termo
   * invalida a lista antiga — o ENTER volta a buscar em vez de vincular o que não é mais do campo.
   */
  function writeTerm(next: string): void {
    termRef.current = next;
    setTerm(next);
    setResults(null);
    setMessage(null);
  }

  /** Busca o termo no servidor: quem interpreta nome/CPF é ele (502), a TUI só transporta (BR-12). */
  async function search(query: string): Promise<void> {
    const trimmed = query.trim();

    if (trimmed === '') {
      setMessage({ kind: 'hint', text: MISSING_TERM });
      return;
    }

    setMessage(null);
    setBusy(true);

    const outcome = await api.searchCustomers(trimmed);

    setBusy(false);

    if (outcome.ok) {
      setResults(outcome.customers);
      setCursor(0);
      // lista vazia: o aviso fica no lugar da dica, e o ENTER busca de novo
      setMessage(outcome.customers.length === 0 ? { kind: 'hint', text: NO_RESULTS } : null);
      return;
    }

    refuse(outcome, SEARCH_RETRY);
  }

  /** Vincula o cliente escolhido: o vínculo é do servidor; o nome, a anotação local que o shell guarda. */
  async function link(option: CustomerOption): Promise<void> {
    setMessage(null);
    setBusy(true);

    const outcome = await api.linkCustomer(saleId, option.id);

    setBusy(false);

    if (outcome.ok) {
      onUpdated(outcome.sale, option); // o shell guarda venda e cliente e tira o modal de cena
      return;
    }

    refuse(outcome, LINK_RETRY);
  }

  /** DEL: remove o cliente da venda; sem vínculo não há o que remover e nada vai à API. */
  async function unlink(): Promise<void> {
    if (customer === null) {
      setMessage({ kind: 'hint', text: NO_CUSTOMER });
      return;
    }

    setMessage(null);
    setBusy(true);

    const outcome = await api.unlinkCustomer(saleId);

    setBusy(false);

    if (outcome.ok) {
      onUpdated(outcome.sale, null); // venda anônima de novo: o shell esquece o nome junto
      return;
    }

    refuse(outcome, UNLINK_RETRY);
  }

  /**
   * Recusa (403/404/422) fica no modal com a mensagem do servidor — o operador corrige ali mesmo, sem
   * perder o termo nem a lista; a transitória (rede/5xx) pede o retry manual na mesma tecla; o resto
   * bloqueia na tela de erro pela mão do shell.
   */
  function refuse(outcome: Refusal, retryNotice: string): void {
    if (outcome.kind === 'rejected') {
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    if (outcome.kind === 'retryable') {
      setMessage({ kind: 'retry', text: retryNotice });
      return;
    }

    onFailed(outcome.problem);
  }

  /** Setas na lista: clamp nas pontas, como na lista da venda; sem lista, não há o que mover. */
  function moveCursor(delta: number): void {
    if (results === null || results.length === 0) {
      return;
    }

    setCursor((current) => Math.min(Math.max(current + delta, 0), results.length - 1));
  }

  return (
    <Box flexDirection="column">
      <Text bold>Cliente na venda (F6)</Text>
      <Text> </Text>
      {customer === null ? null : <Text>Cliente atual: {customer.name}</Text>}
      <Text>Busca: {term}</Text>
      {busy ? (
        <Text dimColor>{SENDING}</Text>
      ) : message !== null ? (
        <MessageRow message={message} />
      ) : results === null ? (
        <Text dimColor>{SEARCH_HINT}</Text>
      ) : null}
      {results === null
        ? null
        : results.map((option, position) => (
            <ResultRow key={option.id} option={option} selected={position === index} />
          ))}
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

/** Linha de resultado: o selecionado vai destacado; o CPF entra quando o cadastro tem um. */
function ResultRow({ option, selected }: { option: CustomerOption; selected: boolean }) {
  const taxId = formatTaxId(option.taxId);

  return (
    <Text color={selected ? 'cyan' : undefined} bold={selected} wrap="truncate-end">
      {selected ? '›' : ' '} {option.name}
      {taxId === '' ? null : ` — ${taxId}`}
    </Text>
  );
}

/** CPF como o cadastro o guarda (só dígitos, 502a): a máscara é da apresentação, com os 11 dígitos. */
function formatTaxId(taxId: string | null): string {
  const digits = taxId ?? '';

  return digits.length === 11
    ? `${digits.slice(0, 3)}.${digits.slice(3, 6)}.${digits.slice(6, 9)}-${digits.slice(9)}`
    : digits;
}
