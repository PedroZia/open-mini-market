import { Box, Text, useInput, useStdout } from 'ink';
import { useEffect, useRef, useState, type Dispatch } from 'react';

import type { SendFailure, SaleItemMutationOutcome, TerminalApi } from '../api/terminalApi';
import { resolveKey, resolveShortcut, type IntentName } from '../core/keys';
import { formatAmount } from '../core/money';
import type { Action } from '../core/reducer';
import { moveSelection, nextQuantity, selectionIndex, touchedItem } from '../core/sale';
import { createScanner, type Scanner } from '../core/scanner';
import type { SaleItemView, SaleOpenState, SaleView, ScanIntent } from '../core/state';
import { inputChars } from './scannerInput';

/**
 * Tela de venda (passos 1108 a 1110, §11.3): o layout principal do operador — cabeçalho com caixa,
 * operador e hora, lista dos últimos itens com o selecionado destacado, painel de totais e barra de
 * status com os atalhos — e a operação: o bipe que vira item, o `+`/`-` que corrige a quantidade e
 * o DEL que remove com confirmação.
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
 * acima vira uma linha com a contagem —, o rodapé é uma única linha (feedback, confirmação ou
 * "enviando…", nunca duas) e nenhuma linha passa de 80 colunas.
 *
 * O leitor é alimentado pelo `useInput` (a tela não tem formulário): o chunk do Ink é quebrado em
 * caracteres com `performance.now()` por caractere, como no autoteste do F11 (1104c), porque o Ink
 * pode entregar a rajada inteira de uma vez, terminador incluso. §11.3 desliga o leitor em modais:
 * no autoteste do F11 o overlay **desmonta** este corpo (1108); na confirmação do DEL (1110) o
 * modal é local e **captura a entrada** — o `useInput` principal fica inativo enquanto ela está à
 * vista, então a rajada do leitor não chega ao scanner e não vira item.
 *
 * O envio é disparado pelo próprio bipe (a fila local guarda a ordem) e não por um efeito sobre
 * `pendingScan`: o retry é sob demanda no ENTER, então não existe laço de efeito para se defender.
 *
 * A seleção da lista é **local da tela** (não vai ao reducer): `null` acompanha o último item — o
 * destaque do 1108 — e as setas fixam o índice, com clamp nas pontas (sem ciclo). `+`/`-` e DEL
 * agem no item selecionado; uma mutação por vez, para não correr com a quantidade (§11.3).
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

/** Itens visíveis: o que sobra das 24 linhas depois de cabeçalho, totais, rodapé e status. */
const MAX_ITEM_ROWS = 10;

/** Atalhos da operação (§11.3) em três linhas que cabem nas 80 colunas. */
const SHORTCUT_ROWS = [
  'F1 Ajuda · F2 Preço · F3 Cancelar item · F4 Cancelar venda · F5 Desconto',
  'F6 Cliente · F7 Sangria · F8 Suprimento · F9 Pagamento · F10 Fechar caixa',
  'F11 Autoteste do leitor · F12 Trocar operador · ↑↓ itens · +/- qtd · DEL remove',
];

/** Falha transitória do envio: o bipe fica na fila e o ENTER refaz (§11.3, retry manual). */
const SEND_FAILURE_NOTICE = 'falha ao enviar o bipe — ENTER tenta de novo';

/** Falha transitória da mutação: o item fica como está e a tecla refaz — não há fila de mutação. */
const QUANTITY_FAILURE_NOTICE = 'falha ao falar com o servidor — +/- tenta de novo';
const REMOVE_FAILURE_NOTICE = 'falha ao falar com o servidor — DEL tenta de novo';

/** `-` que zeraria a quantidade não vai à API: quem remove item é o DEL (com confirmação). */
const REMOVE_HINT = 'use DEL para remover o item';

/** Item que sumiu entre a leitura e a ação (404 `SALE_ITEM_NOT_FOUND`): a venda segue como está. */
const missingItem = (name: string) => `item já não está na venda: ${name}`;

/** Estado do rodapé: o desfecho da última operação, em uma linha só para o frame caber nas 24. */
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
  /** Mutação de item em voo (PATCH/DELETE): trava `+`/`-`/DEL e aparece como "enviando…". */
  const mutatingRef = useRef(false);
  /** Estado da última render: o `pump` roda fora do render e lê o id da venda daqui. */
  const latest = useRef(state);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [mutating, setMutating] = useState(false);
  /** Item selecionado: `null` acompanha o último; as setas fixam o índice (1110). */
  const [selected, setSelected] = useState<number | null>(null);
  /** Item esperando o ENTER da confirmação do DEL; `null` quando não há confirmação aberta. */
  const [confirmRemove, setConfirmRemove] = useState<SaleItemView | null>(null);

  useEffect(() => {
    latest.current = state;
  });

  /** Buffer do leitor: um por tela, com o timing medido no wiring (1104a). */
  function scanner(): Scanner {
    scannerRef.current ??= createScanner();
    return scannerRef.current;
  }

  const sale = state.sale;
  const items = sale?.items ?? [];
  const visible = items.slice(-MAX_ITEM_ROWS);
  const hidden = items.length - visible.length;
  const selectedIndex = selectionIndex(selected, items.length);
  const selectedItem = selectedIndex < 0 ? null : (items[selectedIndex] ?? null);

  /**
   * Teclado da venda (§11.3), resolvido pelo mapa de `core/keys` no contexto `saleOpen`: as setas
   * movem a seleção, `+`/`-` mexem na quantidade e o DEL abre a confirmação. As F1–F12 não passam
   * por aqui (o `useInput` do Ink não as entrega): elas chegam pelo canal cru do shell (1108) e as
   * intenções que ainda não têm comportamento são ignoradas. Tecla do mapa não é leitura: só o que
   * não resolve atalho é alimentado ao scanner.
   */
  useInput(
    (input, key) => {
      const keyName = resolveKey(input, key);
      const shortcut =
        keyName === null ? null : resolveShortcut(keyName, { screen: 'saleOpen', modal: null });

      if (shortcut !== null) {
        if (shortcut.type === 'intent') {
          handleIntent(shortcut.name);
        }

        return;
      }

      // com uma falha de envio à vista, o ENTER refaz a chamada do bipe que ficou na fila
      if (keyName === 'ENTER' && feedback?.kind === 'failure') {
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
    },
    { isActive: confirmRemove === null },
  );

  /**
   * Confirmação do DEL (1110): com o overlay à vista este é o único `useInput` ativo — o principal
   * fica inativo, então a rajada do leitor não chega ao scanner e não vira item. ESC fecha sem
   * chamar nada (o mapa devolve `closeModal` no contexto com modal); ENTER é o "sim" que só este
   * overlay entende; qualquer outra tecla morre aqui.
   */
  useInput(
    (input, key) => {
      const keyName = resolveKey(input, key);
      const shortcut =
        keyName === null
          ? null
          : resolveShortcut(keyName, { screen: 'saleOpen', modal: 'removeItemConfirm' });

      if (shortcut !== null) {
        if (shortcut.type === 'intent' && shortcut.name === 'closeModal') {
          setConfirmRemove(null);
        }

        return;
      }

      if (keyName === 'ENTER') {
        void removeConfirmedItem();
      }
    },
    { isActive: confirmRemove !== null },
  );

  /** O que cada intenção da venda faz na tela; as demais são de passos futuros (1111+). */
  function handleIntent(name: IntentName): void {
    if (name === 'itemUp') {
      // forma funcional: duas setas no mesmo tick (autorepeat, teste) compõem em vez de repetir
      setSelected((current) => moveSelection(current, -1, items.length));
      return;
    }

    if (name === 'itemDown') {
      setSelected((current) => moveSelection(current, 1, items.length));
      return;
    }

    if (name === 'quantityUp') {
      void changeQuantity(1);
      return;
    }

    if (name === 'quantityDown') {
      void changeQuantity(-1);
      return;
    }

    if (name === 'removeItem') {
      askRemove();
    }
  }

  /** Uma mutação por vez: com PATCH/DELETE ou um bipe em voo, `+`/`-`/DEL não disparam nada. */
  function busy(): boolean {
    return mutatingRef.current || sendingRef.current;
  }

  /**
   * `+`/`-` no item selecionado: a quantidade nova vai **absoluta** no `PATCH` (o `{itemId}` é o
   * `productId`, decisão do 802/809b) e quem recalcula a linha e os totais é o servidor (BR-02,
   * BR-12) — a resposta vira `saleUpdated`. O passo é por unidade: 1 em `UN`, 0,1 em `KG`
   * (granularidade de entrada da venda a granel, não cálculo — BR-12). `-` que levaria a ≤ 0 não
   * chama a API: quem remove é o DEL.
   */
  async function changeQuantity(direction: 1 | -1): Promise<void> {
    const item = selectedItem;

    if (item === null || busy()) {
      return;
    }

    const quantity = nextQuantity(item.quantity, item.unit, direction);

    if (quantity <= 0) {
      setFeedback({ kind: 'notice', text: REMOVE_HINT });
      return;
    }

    await runMutation(
      (saleId) => api.changeSaleItemQuantity(saleId, item.productId, quantity),
      item,
      (sale) => describeQuantity(touchedItem(latest.current.sale, sale)),
      QUANTITY_FAILURE_NOTICE,
    );
  }

  /** DEL: abre a confirmação do item selecionado — nada vai à API antes do ENTER (1110). */
  function askRemove(): void {
    if (selectedItem === null || busy()) {
      return;
    }

    setConfirmRemove(selectedItem);
  }

  /**
   * ENTER da confirmação: o `DELETE` do item que a confirmação guardou (ESC não chega aqui — o
   * overlay fecha sem chamar nada). A venda que volta já vem com os totais do servidor; remover o
   * último item deixa a venda vazia e a tela volta ao estado vazio do 1108.
   */
  async function removeConfirmedItem(): Promise<void> {
    const item = confirmRemove;
    setConfirmRemove(null);

    if (item === null || busy()) {
      return;
    }

    await runMutation(
      (saleId) => api.removeSaleItem(saleId, item.productId),
      item,
      () => `removido: ${item.name}`,
      REMOVE_FAILURE_NOTICE,
    );
  }

  /**
   * Uma mutação de item por vez: marca o "enviando…", chama a API e traduz o desfecho — sucesso vira
   * `saleUpdated` com a venda que o servidor recalculou, 404 avisa e a venda fica como está, o resto
   * é falha (transitória avisa e a tecla refaz; bloqueante vai para a tela de erro). O `finally`
   * libera a trava e drena a fila de bipes que chegou durante a chamada.
   */
  async function runMutation(
    send: (saleId: string) => Promise<SaleItemMutationOutcome>,
    item: SaleItemView,
    success: (sale: SaleView) => string,
    failureNotice: string,
  ): Promise<void> {
    const saleId = latest.current.sale?.id ?? null;

    if (saleId === null) {
      return;
    }

    mutatingRef.current = true;
    setMutating(true);

    try {
      const outcome = await send(saleId);

      if (outcome.ok) {
        dispatch({ type: 'saleUpdated', sale: outcome.sale });
        setFeedback({ kind: 'success', text: success(outcome.sale) });
        return;
      }

      if (outcome.kind === 'notFound') {
        setFeedback({ kind: 'notice', text: missingItem(item.name) });
        return;
      }

      sendFailed(outcome, failureNotice);
    } finally {
      mutatingRef.current = false;
      setMutating(false);
      void pump(); // bipes que chegaram durante a mutação esperam aqui
    }
  }

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
   * - falha bloqueante (403/409/400/contrato): vai para a tela de erro com a venda preservada;
   * - com uma mutação de item em voo a fila espera: o `finally` da mutação chama o `pump` de novo,
   *   então nenhum bipe se perde e a venda não é mexida por duas chamadas ao mesmo tempo.
   */
  async function pump(): Promise<void> {
    if (sendingRef.current || mutatingRef.current) {
      return; // já tem envio (ou mutação) em andamento: ele pega o que está na fila
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

  /** Falha de envio: transitória segura a operação para o retry; bloqueante vai para a tela de erro. */
  function sendFailed(failure: SendFailure, notice = SEND_FAILURE_NOTICE): void {
    if (failure.kind === 'retryable') {
      setFeedback({ kind: 'failure', text: notice });
      return;
    }

    dispatch({ type: 'apiFailed', problem: failure.problem });
  }

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
        <ItemRow
          key={item.productId}
          item={item}
          selected={hidden + index === selectedIndex}
        />
      ))}
      <Text> </Text>
      <Text>Subtotal: {formatAmount(sale?.subtotal ?? 0)}</Text>
      <Text>Desconto: {formatAmount(sale?.discountAmount ?? 0)}</Text>
      <Text bold>TOTAL: {formatAmount(sale?.total ?? 0)}</Text>
      {confirmRemove !== null ? (
        <Text color="yellow" bold wrap="truncate-end">
          remover {confirmRemove.name}? ENTER confirma · ESC cancela
        </Text>
      ) : mutating ? (
        <Text dimColor>enviando…</Text>
      ) : feedback === null ? null : (
        <FeedbackRow feedback={feedback} />
      )}
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

/** Confirmação do `+`/`-`: a quantidade que o servidor aplicou, nunca a conta da TUI (BR-12). */
function describeQuantity(item: SaleItemView | null): string {
  if (item === null) {
    return 'quantidade alterada';
  }

  return `quantidade: ${formatQuantity(item.quantity)} x ${item.name} — ${formatAmount(item.lineTotal)}`;
}

/** Linha de um item: o selecionado vai destacado — sem seta, o último, como no 1108. */
function ItemRow({ item, selected }: { item: SaleItemView; selected: boolean }) {
  return (
    <Text color={selected ? 'cyan' : undefined} bold={selected} wrap="truncate-end">
      {selected ? '›' : ' '} {formatQuantity(item.quantity)} x {item.name} — {formatAmount(item.lineTotal)}
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
