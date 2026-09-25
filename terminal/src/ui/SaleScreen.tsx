import { Box, Text, useInput, useStdout } from 'ink';
import { useEffect, useRef, useState, type Dispatch } from 'react';

import type { SendFailure, TerminalApi } from '../api/terminalApi';
import { formatAmount } from '../core/money';
import type { Action } from '../core/reducer';
import { touchedItem } from '../core/sale';
import { createScanner, type Scanner } from '../core/scanner';
import type { SaleItemView, SaleOpenState, ScanIntent } from '../core/state';
import { inputChars } from './scannerInput';

/**
 * Tela de venda (passos 1108 e 1109, §11.3): o layout principal do operador — cabeçalho com caixa,
 * operador e hora, lista dos últimos itens com o último destacado, painel de totais e barra de
 * status com os atalhos — e a operação principal, o bipe que vira item da venda.
 *
 * A tela não calcula nada (BR-12): subtotal, desconto e total saem de `state.sale`, como o servidor
 * mandou; antes do primeiro bipe a venda é `null` e a tela mostra a lista vazia com zeros de
 * exibição. A hora entra por prop para o desenho ser determinístico no teste.
 *
 * O nome da loja não está no estado (§11.2), então o cabeçalho mostra o que o reducer tem — caixa e
 * operador; quando a sessão carregar a loja (`GET /auth/me`), ela entra aqui, sem inchar o reducer
 * por causa de um rótulo.
 *
 * O layout assume 80×24 (§11.3): a janela mostra os últimos `MAX_ITEM_ROWS` itens — o que fica
 * acima vira uma linha com a contagem —, o feedback é uma única linha (só uma notícia por vez) e
 * nenhuma linha passa de 80 colunas.
 *
 * O leitor é alimentado pelo `useInput` (a tela não tem formulário): o chunk do Ink é quebrado em
 * caracteres com `performance.now()` por caractere, como no autoteste do F11 (1104c), porque o Ink
 * pode entregar a rajada inteira de uma vez, terminador incluso. §11.3 desliga o leitor em modais:
 * aqui isso é por construção — o overlay do autoteste (1108) **desmonta** este corpo, então não há
 * o que desligar enquanto ele está à vista.
 *
 * O envio é disparado pelo próprio bipe (a fila local guarda a ordem) e não por um efeito sobre
 * `pendingScan`: o retry é sob demanda no ENTER, então não existe laço de efeito para se defender.
 */
export type SaleScreenProps = {
  /** Estado do reducer: operador, caixa e a venda como o servidor devolveu (1103). */
  state: SaleOpenState;
  /** Hora do cabeçalho: `new Date()` no app, data fixa no teste. */
  now: Date;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>;
};

/** Itens visíveis: o que sobra das 24 linhas depois de cabeçalho, totais, feedback e status. */
const MAX_ITEM_ROWS = 10;

/** Atalhos da operação (§11.3) em três linhas que cabem nas 80 colunas. */
const SHORTCUT_ROWS = [
  'F1 Ajuda · F2 Preço · F3 Cancelar item · F4 Cancelar venda · F5 Desconto',
  'F6 Cliente · F7 Sangria · F8 Suprimento · F9 Pagamento · F10 Fechar caixa',
  'F11 Autoteste do leitor · F12 Trocar operador · ↑↓ itens · +/- qtd · DEL remove',
];

/** Falha transitória do envio: o bipe fica na fila e o ENTER refaz (§11.3, retry manual). */
const SEND_FAILURE_NOTICE = 'falha ao enviar o bipe — ENTER tenta de novo';

/** Estado do rodapé: o desfecho do último bipe, em uma linha só para o frame caber nas 24. */
type Feedback =
  | { kind: 'success'; text: string }
  | { kind: 'notice'; text: string }
  | { kind: 'failure'; text: string };

export function SaleScreen({ state, now, api, dispatch }: SaleScreenProps) {
  const { stdout } = useStdout();
  /** Buffer do leitor: um por tela, com o timing medido no wiring. */
  const scannerRef = useRef<Scanner | null>(null);
  /** Bipes fechados aguardando envio, na ordem em que chegaram: nenhum bipe se perde. */
  const queueRef = useRef<ScanIntent[]>([]);
  /** Um envio por vez; quem já está enviando pega a fila atualizada, não empilha chamadas. */
  const sendingRef = useRef(false);
  /** Estado da última render: o `pump` roda fora do render e lê o id da venda daqui. */
  const latest = useRef(state);
  const [feedback, setFeedback] = useState<Feedback | null>(null);

  useEffect(() => {
    latest.current = state;
  });

  /** Buffer do leitor: um por tela, com o timing medido no wiring (1104a). */
  function scanner(): Scanner {
    scannerRef.current ??= createScanner();
    return scannerRef.current;
  }

  useInput((input, key) => {
    // com uma falha de envio à vista, o ENTER refaz a chamada do bipe que ficou na fila
    if (key.return && feedback?.kind === 'failure') {
      void pump();
      return;
    }

    for (const char of inputChars(input, key)) {
      const event = scanner().feed(char, performance.now());
      if (event === null) {
        continue;
      }

      // o bipe vira intenção no reducer (1103) e entra na fila do envio
      const scan: ScanIntent = { barcode: event.barcode, quantity: event.quantity };
      queueRef.current.push(scan);
      dispatch({ type: 'barcodeScanned', barcode: scan.barcode, quantity: scan.quantity });
      void pump();
    }
  });

  /**
   * Envia a fila de bipes, um por vez, na ordem em que chegaram:
   *
   * - sem venda criada, abre a venda primeiro (`POST /sales`, 201) e registra o id no reducer — é o
   *   item que faz a venda existir de fato; o id fica no estado para o próximo bipe reutilizar
   *   (inclusive quando a venda nasceu vazia por causa de um 404);
   * - cada bipe vira `POST /sales/{id}/items` com o código **bruto** e a quantidade do
   *   multiplicador (BR-14); dois bipes do mesmo produto viram duas chamadas e quem soma é o
   *   servidor (BR-01);
   * - sucesso: `saleUpdated` (limpa o pendente), linha de confirmação e bell;
   * - 404/422: aviso na tela e o bipe é consumido (`scanDismissed`) sem mexer na venda;
   * - falha transitória (rede, timeout, 5xx): o bipe fica no topo da fila e o ENTER refaz;
   * - falha bloqueante (403/409/400/contrato): vai para a tela de erro com a venda preservada.
   */
  async function pump(): Promise<void> {
    if (sendingRef.current) {
      return; // já tem envio em andamento: ele pega o que está na fila
    }

    sendingRef.current = true;

    try {
      let saleId = latest.current.sale?.id ?? null;

      for (;;) {
        const scan = queueRef.current[0];
        if (scan === undefined) {
          return;
        }

        // o bipe que está indo à API é o pendente do reducer
        dispatch({ type: 'barcodeScanned', barcode: scan.barcode, quantity: scan.quantity });

        if (saleId === null) {
          const created = await api.createSale();
          if (!created.ok) {
            sendFailed(created);
            return;
          }

          saleId = created.sale.id;
          dispatch({ type: 'saleUpdated', sale: created.sale });
        }

        const added = await api.addSaleItem(saleId, scan);

        if (added.ok) {
          queueRef.current.shift();
          setFeedback({
            kind: 'success',
            text: describeAdded(touchedItem(latest.current.sale, added.sale)),
          });
          dispatch({ type: 'saleUpdated', sale: added.sale });
          stdout.write('\u0007'); // bell: o produto entrou na venda
          continue;
        }

        if (added.kind === 'notFound') {
          queueRef.current.shift();
          setFeedback({
            kind: 'notice',
            text: `produto não encontrado: ${added.barcode} — cadastro rápido ainda não disponível`,
          });
          dispatch({ type: 'scanDismissed' });
          continue;
        }

        if (added.kind === 'rejected') {
          queueRef.current.shift();
          setFeedback({ kind: 'notice', text: added.message });
          dispatch({ type: 'scanDismissed' });
          continue;
        }

        sendFailed(added);
        return;
      }
    } finally {
      sendingRef.current = false;
    }
  }

  /** Falha de envio: transitória segura o bipe para o retry; bloqueante vai para a tela de erro. */
  function sendFailed(failure: SendFailure): void {
    if (failure.kind === 'retryable') {
      setFeedback({ kind: 'failure', text: SEND_FAILURE_NOTICE });
      return;
    }

    dispatch({ type: 'apiFailed', problem: failure.problem });
  }

  const sale = state.sale;
  const items = sale?.items ?? [];
  const visible = items.slice(-MAX_ITEM_ROWS);
  const hidden = items.length - visible.length;

  return (
    <Box flexDirection="column">
      <Text bold>PDV minimercado · {state.register.name}</Text>
      <Text>
        Operador: {state.operator.name} · {formatTime(now)}
      </Text>
      <Text> </Text>
      {hidden === 0 ? null : <Text dimColor>… {hidden} itens acima</Text>}
      {items.length === 0 ? (
        <Text dimColor>bipar o primeiro item para iniciar a venda</Text>
      ) : null}
      {visible.map((item, index) => (
        <ItemRow key={index} item={item} last={index === visible.length - 1} />
      ))}
      <Text> </Text>
      <Text>Subtotal: {formatAmount(sale?.subtotal ?? 0)}</Text>
      <Text>Desconto: {formatAmount(sale?.discountAmount ?? 0)}</Text>
      <Text bold>TOTAL: {formatAmount(sale?.total ?? 0)}</Text>
      {feedback === null ? null : <FeedbackRow feedback={feedback} />}
      {SHORTCUT_ROWS.map((row) => (
        <Text key={row} dimColor>
          {row}
        </Text>
      ))}
    </Box>
  );
}

/** Rodapé: verde no bipe aceito, amarelo no aviso (404/422) e vermelho na falha que pede retry. */
function FeedbackRow({ feedback }: { feedback: Feedback }) {
  const color =
    feedback.kind === 'failure' ? 'red' : feedback.kind === 'notice' ? 'yellow' : 'green';

  return (
    <Text color={color} wrap="truncate-end">
      {feedback.text}
    </Text>
  );
}

/** Confirmação do bipe: o item que o servidor devolveu, com nome, quantidade e valor (BR-12). */
function describeAdded(item: SaleItemView | null): string {
  if (item === null) {
    return 'item adicionado';
  }

  return `adicionado: ${formatQuantity(item.quantity)} x ${item.name} — ${formatAmount(item.lineTotal)}`;
}

/** Linha de um item: o último da lista vai destacado (§11.3). */
function ItemRow({ item, last }: { item: SaleItemView; last: boolean }) {
  return (
    <Text color={last ? 'cyan' : undefined} bold={last} wrap="truncate-end">
      {last ? '›' : ' '} {formatQuantity(item.quantity)} x {item.name} — {formatAmount(item.lineTotal)}
    </Text>
  );
}

/** Hora do cabeçalho em pt-BR, sempre com dois dígitos: `14:32:05`. */
function formatTime(now: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

/** Quantidade em pt-BR: inteira como `2`, fracionária como `0,750` (a de kg vem do servidor). */
function formatQuantity(quantity: number): string {
  return Number.isInteger(quantity) ? String(quantity) : quantity.toFixed(3).replace('.', ',');
}
