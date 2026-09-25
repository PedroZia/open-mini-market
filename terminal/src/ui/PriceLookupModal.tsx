import { Box, Text, useInput } from 'ink';
import { useRef, useState } from 'react';

import type {
  ProductOption,
  SendFailure,
  StockBalanceView,
  TerminalApi,
} from '../api/terminalApi';
import { resolveKey } from '../core/keys';
import { formatAmount } from '../core/money';
import type { ApiProblem } from '../core/state';

/**
 * Consulta de preço (F2, passo 1116): modal bloqueante que o shell abre **no lugar** do corpo da
 * venda, como os demais overlays — a venda sai de cena, então a rajada do leitor não vira item, e
 * no contexto `priceLookup` do mapa (§11.3) só o ESC do canal cru do shell atua: os demais atalhos
 * ficam bloqueados e o ESC fecha sem chamar a API.
 *
 * O termo é o que o operador digitou — código **bruto** ou trecho do nome (BR-14) — e o ENTER
 * consulta: primeiro o servidor decide se é um código (`GET /products/barcode/{termo}`) e, quando
 * ele não conhece (404/422), o mesmo termo vale como nome (`GET /products?search=`). Não
 * conhecido em nenhum dos dois → aviso, sem criar venda nem mexer na venda aberta: **consulta não
 * é venda**, e nenhuma chamada de mutação sai daqui.
 *
 * O produto encontrado é exibido com o preço e a unidade, e o saldo vem do módulo de estoque
 * (`GET /stock/{productId}`, passo 704) — quantidade, mínimo e o aviso de estoque baixo são do
 * servidor (BR-12), a TUI só exibe. Na lista (busca por nome), as setas escolhem (clamp nas pontas)
 * e o ENTER consulta o selecionado; a lista fica à vista com o detalhe para o operador conferir o
 * saldo de outro resultado sem digitar de novo.
 *
 * As recusas do servidor (403 sem `product.read`/`stock.read`, 404 do produto que sumiu) ficam **no
 * próprio modal**, sem fechá-lo; rede/5xx mostram o aviso de retry e o ENTER refaz a consulta
 * (recomeçando pelo código, que é idempotente); a falha bloqueante (400/contrato) vai para a tela
 * de erro pela mão do shell.
 */

export type PriceLookupModalProps = {
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Falha bloqueante: o shell fecha o modal e leva o problema ao reducer (`apiFailed`). */
  onFailed: (problem: ApiProblem) => void;
};

/** Produto consultado com o saldo que o servidor devolveu: o que o detalhe exibe. */
type LookupDetail = {
  product: ProductOption;
  /** Saldo da loja e aviso de estoque baixo, calculados pelo servidor (BR-12). */
  stock: StockBalanceView;
};

const FORM_HINT = 'digite o código de barras ou o nome e ENTER consulta';
const MISSING_TERM = 'informe o código de barras ou o nome do produto';
const NO_RESULTS = 'nenhum produto encontrado';

const BARCODE_RETRY = 'falha ao consultar — ENTER tenta de novo';
const SEARCH_RETRY = 'falha ao buscar — ENTER tenta de novo';
const STOCK_RETRY = 'falha ao consultar o saldo — ENTER tenta de novo';

const CONSULTING = 'consultando…';
const KEY_HINT_FORM = 'ENTER consulta · ESC fecha';
const KEY_HINT_LIST = '↑↓ escolhe · ENTER consulta · ESC fecha';

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: 'hint' | 'rejected' | 'retry'; text: string };

/** O que sobra do desfecho da API depois do sucesso: recusa do modal, retry manual ou falha bloqueante. */
type Refusal =
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

export function PriceLookupModal({ api, onFailed }: PriceLookupModalProps) {
  const [term, setTerm] = useState('');
  /**
   * Espelho do termo para o ENTER: o callback do `useInput` é o do último render, e a rajada do
   * leitor pode encher o campo e mandar o terminador em dois eventos `data` seguidos.
   */
  const termRef = useRef('');
  /**
   * Resultados da última busca por nome (`null` = nada listado): editar o termo limpa a lista, então
   * o ENTER nunca consulta um resultado que não é mais do que está escrito no campo.
   */
  const [results, setResults] = useState<ProductOption[] | null>(null);
  /** Seleção na lista: clamp nas pontas, como na lista da venda (1108/1110). */
  const [cursor, setCursor] = useState(0);
  /** Produto com o saldo do servidor (`null` enquanto a consulta não chegou ao detalhe). */
  const [detail, setDetail] = useState<LookupDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);

  const index = results === null ? 0 : Math.min(cursor, Math.max(results.length - 1, 0));
  const chosen = results?.[index] ?? null;

  useInput((input, key) => {
    // requisição em andamento: ENTER repetido não dispara duas consultas
    if (busy) {
      return;
    }

    const keyName = resolveKey(input, key);

    // ESC é do shell (canal cru), como nos demais modais: fecha sem passar por aqui
    if (keyName === 'ESC') {
      return;
    }

    if (keyName === 'ENTER') {
      // com a lista à vista o ENTER consulta o selecionado; sem lista, consulta o que está no campo
      // (pelo ref, não pelo render)
      if (chosen !== null) {
        void loadStock(chosen);
      } else {
        void consult(termRef.current);
      }

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

    // o terminador do leitor pode vir colado no texto: não é texto nem consulta nada (como no 1107)
    const typed = input.replace(/[\r\n]/g, '');

    if (typed !== '') {
      writeTerm(termRef.current + typed);
    }
  });

  /**
   * Escreve no campo de consulta: o estado desenha e o ref guarda o valor corrente. Mexer no termo
   * invalida a lista e o detalhe antigos — o ENTER volta a consultar em vez de repetir o de antes.
   */
  function writeTerm(next: string): void {
    termRef.current = next;
    setTerm(next);
    setResults(null);
    setDetail(null);
    setMessage(null);
  }

  /**
   * ENTER no campo: o código vai **bruto** ao servidor (BR-14) e é ele quem decide o que o termo é.
   * Código conhecido → saldo; 404/422 dele → o mesmo termo vale como nome; o resto é recusa/falha.
   */
  async function consult(query: string): Promise<void> {
    const trimmed = query.trim();

    if (trimmed === '') {
      setMessage({ kind: 'hint', text: MISSING_TERM });
      return;
    }

    setMessage(null);
    setDetail(null);
    setResults(null);
    setBusy(true);

    const outcome = await api.resolveBarcode(trimmed);

    setBusy(false);

    if (outcome.ok) {
      await loadStock({
        id: outcome.product.id,
        name: outcome.product.name,
        price: outcome.product.price,
        unit: outcome.product.unit,
      });
      return;
    }

    if (outcome.kind === 'notFound') {
      await search(trimmed);
      return;
    }

    refuse(outcome, BARCODE_RETRY);
  }

  /** Busca por nome no servidor (404): quem interpreta o termo é ele, a TUI só transporta (BR-12). */
  async function search(query: string): Promise<void> {
    setMessage(null);
    setBusy(true);

    const outcome = await api.searchProducts(query);

    setBusy(false);

    if (outcome.ok) {
      setResults(outcome.products);
      setCursor(0);
      // lista vazia: o aviso fica no lugar da dica, e o ENTER consulta de novo
      setMessage(outcome.products.length === 0 ? { kind: 'hint', text: NO_RESULTS } : null);
      return;
    }

    refuse(outcome, SEARCH_RETRY);
  }

  /** Saldo do produto escolhido (`GET /stock/{productId}`): quantidade e aviso são do servidor (BR-12). */
  async function loadStock(product: ProductOption): Promise<void> {
    setMessage(null);
    setBusy(true);

    const outcome = await api.productStock(product.id);

    setBusy(false);

    if (outcome.ok) {
      setDetail({ product, stock: outcome.stock });
      return;
    }

    refuse(outcome, STOCK_RETRY);
  }

  /**
   * Recusa (403/404) fica no modal com o texto do servidor — o operador consulta outro termo sem
   * perder o campo; a transitória (rede/5xx) pede o retry manual; o resto bloqueia na tela de erro
   * pela mão do shell.
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
      <Text bold>Consulta de preço (F2)</Text>
      <Text> </Text>
      <Text>Busca: {term}</Text>
      {busy ? (
        <Text dimColor>{CONSULTING}</Text>
      ) : message !== null ? (
        <MessageRow message={message} />
      ) : detail === null && results === null ? (
        <Text dimColor>{FORM_HINT}</Text>
      ) : null}
      {detail === null ? null : <DetailRows detail={detail} />}
      {results === null
        ? null
        : results.map((option, position) => (
            <ResultRow key={option.id} option={option} selected={position === index} />
          ))}
      <Text> </Text>
      <Text dimColor>{results === null ? KEY_HINT_FORM : KEY_HINT_LIST}</Text>
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

/** Detalhe da consulta: produto, preço e o saldo do servidor, com o alerta de estoque baixo. */
function DetailRows({ detail }: { detail: LookupDetail }) {
  const { product, stock } = detail;
  const balance = `Saldo: ${formatQuantity(stock.quantity)} · mínimo ${formatQuantity(stock.minQuantity)}`;

  return (
    <>
      <Text bold wrap="truncate-end">
        Produto: {product.name}
      </Text>
      <Text>
        Preço: {formatAmount(product.price)} · {product.unit}
      </Text>
      <Text color={stock.lowStock ? 'yellow' : undefined}>
        {stock.lowStock ? `${balance} · ESTOQUE BAIXO` : balance}
      </Text>
    </>
  );
}

/** Linha de resultado: o selecionado vai destacado; o preço é o do servidor (BR-12). */
function ResultRow({ option, selected }: { option: ProductOption; selected: boolean }) {
  return (
    <Text color={selected ? 'cyan' : undefined} bold={selected} wrap="truncate-end">
      {selected ? '›' : ' '} {option.name} — {formatAmount(option.price)}
    </Text>
  );
}

/** Quantidade em pt-BR, como a lista da venda (1108): inteira como `2`, fracionária como `0,750`. */
function formatQuantity(quantity: number): string {
  return Number.isInteger(quantity) ? String(quantity) : quantity.toFixed(3).replace('.', ',');
}
