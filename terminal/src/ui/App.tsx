import { useEffect, useMemo, useReducer, useRef, useState } from 'react';

import { withProblemGuard } from '../api/problemGuard';
import type { CashMovementKind, CustomerOption, TerminalApi } from '../api/terminalApi';
import { reduce } from '../core/reducer';
import { initialState, type ApiProblem, type SaleView, type State } from '../core/state';
import { CancelSaleModal } from './CancelSaleModal';
import { CashMovementModal } from './CashMovementModal';
import { ClosingCashScreen } from './ClosingCashScreen';
import { CustomerModal } from './CustomerModal';
import { DiscountModal } from './DiscountModal';
import { ErrorScreen } from './ErrorScreen';
import { HelpModal } from './HelpModal';
import { LoginScreen } from './LoginScreen';
import { OpeningCashScreen } from './OpeningCashScreen';
import { PaymentScreen, type PaymentScreenHandle } from './PaymentScreen';
import { PriceLookupModal } from './PriceLookupModal';
import { ReaderSelfTestScreen, type BarcodeResolver } from './ReaderSelfTestScreen';
import { SaleScreen, type SaleScreenHandle } from './SaleScreen';
import { SaleSuccessScreen } from './SaleSuccessScreen';
import { useRawShortcuts } from './useRawShortcuts';

/**
 * Shell da TUI (§11.2): guarda o estado da operação no reducer puro (1103) e desenha uma tela por
 * `kind`. Login (1106), abertura de caixa (1107), venda (1108/1109), pagamento (1113) e fechamento
 * de caixa (1115) são as telas implementadas — a troca de tela é sempre do reducer, nunca da tela.
 *
 * O canal cru do teclado (F1–F12, que o `useInput` do Ink não entrega) é do shell: o hook resolve o
 * atalho no contexto da tela e aqui só se age nas **intenções** com comportamento — o autoteste do
 * leitor no F11 (1108), o cancelamento do item no F3, o cancelamento da venda no F4 e o fechamento
 * no F10 (1115), o desconto no F5 (1111), o cliente no F6 (1112), a sangria no F7 e o suprimento no
 * F8 (1114) e o pagamento no F9 (1113), todos menos o F10 abertos **como overlay da venda**: o corpo
 * da venda sai de cena, então a rajada do leitor não vira item, e com o modal aberto o mapa só
 * resolve ESC (`closeModal`, §11.3). O F5/F6 sem venda criada não abre nada: não há desconto nem
 * cliente a vincular antes do primeiro bipe (1109) — o F4 segue a mesma regra, porque sem venda não
 * há o que cancelar —, e o F9 depende do reducer, que só abre o pagamento com venda e itens (1113).
 * O F7/F8, ao contrário, abre com o caixa da sessão mesmo sem venda: sangrar e suprir são operações
 * da gaveta, não da venda (1114). O F3 não tem lógica própria: ele chama o `askRemove` do DEL pelo
 * handle da tela de venda (1115).
 *
 * O pagamento é uma tela, não um overlay: o F9 do shell abre (`paymentStarted`) e, já no pagamento,
 * o mesmo F9 **conclui** — a tecla chega pelo canal cru e o shell chama o `complete()` da tela pelo
 * handle, porque a chave de idempotência e o retry são dela (1113). A tela de sucesso aparece
 * enquanto houver `receipt` no estado; o ENTER que inicia a próxima venda é do reducer.
 *
 * O cliente vinculado é o único pedaço da view que não cabe no reducer: o `SaleDetailResponse` só
 * traz o `customerId`, então o shell guarda o par `{id, name}` que a busca local capturou (1112) e o
 * cabeçalho mostra esse nome enquanto a venda do servidor apontar para o mesmo id — o vínculo real
 * é o do servidor, o nome é a anotação da seleção, e ela é esquecida quando a venda conclui (1113)
 * ou é cancelada no F4 (1115).
 *
 * Os fluxos do 1115 fecham o turno: o F3 cai no mesmo `askRemove` do DEL pelo handle da tela de
 * venda (a confirmação do remover é uma só), o F4 abre o modal do cancelamento — venda cancelada
 * volta ao estado vazio e a anotação local do cliente morre com ela — e o F10 leva para o
 * `closingCash`, a primeira tela do shell que não é overlay: o resumo e o contado são dela, e a
 * diferença que ela exibe vem do corpo do close (BR-12). Com o caixa fechado, o ENTER da tela faz o
 * logout e qualquer outra tecla volta ao login sem revogar a sessão.
 *
 * O 1116 traz as duas consultas: o F1 abre a ajuda — o mapa de teclas (§11.3) — **sempre** que a
 * venda está à vista, inclusive antes do primeiro bipe e com a tela de sucesso, porque não age
 * sobre nada e o ESC devolve a tela como estava; e o F2 abre a consulta de preço, que segue a regra
 * dos overlays que consultam a venda/servidor (1114/1115) e não abre com a tela de sucesso à vista,
 * onde o ENTER é dela. A consulta não cria venda nem toca na aberta: só lê o produto (código bruto
 * ou nome, quem decide é o servidor — BR-14) e o saldo (BR-12).
 *
 * O 1117 é a resiliência, e ela também é do shell: a API que as telas recebem é a **embrulhada**
 * pela guarda (`withProblemGuard`), então sessão caída, idempotência reusada e conexão são
 * observadas num lugar só — a política de cada `code` vem de `core/problems`. A sessão caída leva ao
 * login com aviso e guarda a venda para o próximo login do mesmo caixa; o 409 de
 * idempotência/concorrência relê a venda do servidor (`GET /sales/{id}`) em vez de repetir a
 * operação; e o `online` da barra de status cai na falha de transporte e volta a cada resposta —
 * inclusive nas telas que tratam o próprio rodapé. A loja do cabeçalho vem do `GET /auth/me`, uma
 * vez por login, sem bloquear a venda se a chamada falhar.
 */
export function App({ api: rawApi }: { api: TerminalApi }) {
  const [state, dispatch] = useReducer(reduce, initialState);
  /** Conexão com o servidor (1117): cai na falha de transporte, volta a cada resposta dele. */
  const [online, setOnline] = useState(true);
  /** Loja do cabeçalho (`GET /auth/me`, 1117); `null` enquanto não chegou — ou se falhou. */
  const [store, setStore] = useState<string | null>(null);
  /** Estado corrente fora do render: a releitura da reconciliação lê a venda daqui (1117). */
  const latest = useRef(state);
  latest.current = state;
  /** A loja é perguntada uma vez por login (1117), não a cada troca de tela. */
  const storeAsked = useRef(false);
  /** Autoteste do leitor aberto sobre a venda: estado de UI, fora do reducer. */
  const [readerSelfTest, setReaderSelfTest] = useState(false);
  /** Modal de desconto aberto sobre a venda (1111): estado de UI, fora do reducer. */
  const [discountOpen, setDiscountOpen] = useState(false);
  /** Modal de cliente aberto sobre a venda (1112): estado de UI, fora do reducer. */
  const [customerOpen, setCustomerOpen] = useState(false);
  /** Modal do cancelamento aberto sobre a venda (F4, 1115): estado de UI, fora do reducer. */
  const [cancelSaleOpen, setCancelSaleOpen] = useState(false);
  /** Consulta de preço aberta sobre a venda (F2, 1116): estado de UI, fora do reducer. */
  const [priceLookupOpen, setPriceLookupOpen] = useState(false);
  /** Ajuda aberta sobre a venda (F1, 1116): estado de UI, fora do reducer. */
  const [helpOpen, setHelpOpen] = useState(false);
  /** Modal da gaveta aberto sobre a venda (1114): sangria (F7) ou suprimento (F8), fora do reducer. */
  const [cashMovement, setCashMovement] = useState<CashMovementKind | null>(null);
  /** Cliente que esta sessão vinculou, com o nome da busca; `null` na venda anônima (1112). */
  const [customer, setCustomer] = useState<CustomerOption | null>(null);
  /** Handle da tela de pagamento: é por ele que o F9 do canal cru conclui a venda (1113). */
  const paymentRef = useRef<PaymentScreenHandle | null>(null);
  /** Handle da tela de venda: é por ele que o F3 do canal cru abre a confirmação do DEL (1115). */
  const saleRef = useRef<SaleScreenHandle | null>(null);

  /**
   * A API que as telas recebem é a embrulhada pela guarda (1117): toda chamada passa pela política
   * central antes de virar tela. Os handlers fecham sobre `dispatch`, `setOnline` e `latest`, que são
   * estáveis — a guarda só é refeita se a API injetada mudar (o teste troca o dublê).
   */
  const api = useMemo(
    () =>
      withProblemGuard(rawApi, {
        onSession: (message) => dispatch({ type: 'sessionExpired', message }),
        onReconcile: (saleId, message) => void reconcile(saleId, message),
        onConnection: (next) => setOnline((current) => (current === next ? current : next)),
      }),
    [rawApi],
  );

  /**
   * Loja do cabeçalho (`GET /auth/me`, 1117): uma vez por login, na entrada da operação. A falha
   * não bloqueia a venda — o cabeçalho fica sem loja e o operador segue.
   */
  useEffect(() => {
    if (state.kind === 'login') {
      // login novo (logout, fechamento ou queda de sessão): a loja é perguntada de novo
      storeAsked.current = false;
      setStore(null);
      return;
    }

    if (state.kind !== 'saleOpen' || storeAsked.current) {
      return;
    }

    storeAsked.current = true;
    void loadStore();
  }, [state.kind]);

  /** Lê a loja da sessão e guarda o rótulo do cabeçalho; falha ou loja ausente deixam `store` nulo. */
  async function loadStore(): Promise<void> {
    const outcome = await api.currentSession();

    if (!outcome.ok || outcome.store === null) {
      return;
    }

    setStore(outcome.store.name === '' ? outcome.store.code : outcome.store.name);
  }

  /**
   * 409 de idempotência/concorrência (1117): nada é repetido às cegas — a venda é relida do
   * servidor e o operador vê o estado de verdade, com o aviso do mapa central à vista.
   */
  async function reconcile(saleId: string | null, message: string): Promise<void> {
    const id = saleId ?? currentSaleId(latest.current);

    if (id === null) {
      return;
    }

    const outcome = await api.getSale(id);

    if (outcome.ok) {
      dispatch({ type: 'saleReconciled', sale: outcome.sale, notice: message });
      return;
    }

    // a própria releitura falhou: a falha dela é que decide (rede bloqueia, sessão volta ao login)
    dispatch({ type: 'apiFailed', problem: outcome.problem });
  }

  useRawShortcuts(
    (shortcut) => {
      if (shortcut.type !== 'intent') {
        return;
      }

      if (shortcut.name === 'readerSelfTest') {
        setReaderSelfTest(true);
      } else if (shortcut.name === 'help') {
        // F1 é o mapa de teclas: não age sobre nada e volta com o ESC — abre sempre na venda,
        // inclusive com a tela de sucesso à vista (1116)
        setHelpOpen(true);
      } else if (shortcut.name === 'priceLookup') {
        // F2 consulta sem vender nem mexer na venda (1116); com a tela de sucesso à vista o ENTER é
        // dela, como nos demais overlays (1114/1115)
        if (state.kind === 'saleOpen' && state.receipt === null) {
          setPriceLookupOpen(true);
        }
      } else if (shortcut.name === 'discount') {
        // sem venda criada não há o que descontar: a venda nasce no primeiro bipe (1109)
        if (state.kind === 'saleOpen' && state.sale !== null) {
          setDiscountOpen(true);
        }
      } else if (shortcut.name === 'customer') {
        // sem venda criada não há onde vincular cliente (1112), como no desconto
        if (state.kind === 'saleOpen' && state.sale !== null) {
          setCustomerOpen(true);
        }
      } else if (shortcut.name === 'cancelItem') {
        // F3 e DEL fazem o mesmo: a confirmação do remover é do handler da tela de venda (1110)
        saleRef.current?.askRemove();
      } else if (shortcut.name === 'cancelSale') {
        // sem venda criada não há o que cancelar; a tela de sucesso é do ENTER (1113)
        if (state.kind === 'saleOpen' && state.sale !== null && state.receipt === null) {
          setCancelSaleOpen(true);
        }
      } else if (shortcut.name === 'withdrawal' || shortcut.name === 'supply') {
        // a gaveta é do caixa aberto, não da venda: o F7/F8 vale também antes do primeiro bipe.
        // Com a tela de sucesso à vista o ENTER é dela — a gaveta se mexe depois de dispensá-la.
        if (state.kind === 'saleOpen' && state.receipt === null) {
          setCashMovement(shortcut.name === 'withdrawal' ? 'withdrawal' : 'supply');
        }
      } else if (shortcut.name === 'checkout') {
        if (state.kind === 'paying') {
          // já no pagamento o F9 conclui a venda; o reducer registra o resumo (1113)
          paymentRef.current?.complete();
        } else {
          // abre o pagamento: o reducer ignora fora da venda ou com a venda sem itens (1113)
          dispatch({ type: 'paymentStarted' });
        }
      } else if (shortcut.name === 'closeCash') {
        // F10: o fechamento é do caixa aberto; com a tela de sucesso à vista o ENTER é dela (1113)
        if (state.kind === 'saleOpen' && state.receipt === null) {
          dispatch({ type: 'cashClosingStarted' });
        }
      } else if (shortcut.name === 'closeModal') {
        setReaderSelfTest(false);
        setHelpOpen(false);
        setPriceLookupOpen(false);
        setDiscountOpen(false);
        setCustomerOpen(false);
        setCancelSaleOpen(false);
        setCashMovement(null);
      }
    },
    {
      screen: state.kind,
      modal:
        cashMovement ??
        (discountOpen
          ? 'discount'
          : customerOpen
            ? 'customer'
            : cancelSaleOpen
              ? 'cancelSale'
              : priceLookupOpen
                ? 'priceLookup'
                : helpOpen
                  ? 'help'
                  : readerSelfTest
                    ? 'readerSelfTest'
                    : null),
    },
  );

  /** Desconto aplicado: o reducer guarda a venda recalculada pelo servidor e o modal sai de cena. */
  function discountApplied(sale: SaleView): void {
    dispatch({ type: 'saleUpdated', sale });
    setDiscountOpen(false);
  }

  /** Falha bloqueante do desconto: fecha o modal e manda o problema para a tela de erro (§11.4). */
  function discountFailed(problem: ApiProblem): void {
    setDiscountOpen(false);
    dispatch({ type: 'apiFailed', problem });
  }

  /** Cliente vinculado ou removido: o shell guarda a venda e o nome da seleção, e o modal sai de cena. */
  function customerUpdated(sale: SaleView, linked: CustomerOption | null): void {
    dispatch({ type: 'saleUpdated', sale });
    setCustomer(linked);
    setCustomerOpen(false);
  }

  /** Venda concluída (1113): o nome do cliente era da venda que fechou e não vale para a próxima. */
  function saleCompleted(): void {
    setCustomer(null);
  }

  /** Venda cancelada no servidor (F4, 1115): a venda some e o nome anotado era dela. */
  function saleCancelled(): void {
    setCancelSaleOpen(false);
    setCustomer(null);
    dispatch({ type: 'saleCancelled' });
  }

  /** Falha bloqueante do cancelamento: fecha o modal e manda o problema para a tela de erro (§11.4). */
  function saleCancelFailed(problem: ApiProblem): void {
    setCancelSaleOpen(false);
    dispatch({ type: 'apiFailed', problem });
  }

  /** Falha bloqueante da consulta de preço: fecha o modal e manda o problema para a tela de erro (§11.4). */
  function priceLookupFailed(problem: ApiProblem): void {
    setPriceLookupOpen(false);
    dispatch({ type: 'apiFailed', problem });
  }

  /** Falha bloqueante do cliente: fecha o modal e manda o problema para a tela de erro (§11.4). */
  function customerFailed(problem: ApiProblem): void {
    setCustomerOpen(false);
    dispatch({ type: 'apiFailed', problem });
  }

  /** Sangria/suprimento registrado: o ENTER do operador tira o modal de cena (1114). */
  function cashMovementClosed(): void {
    setCashMovement(null);
  }

  /** Falha bloqueante da gaveta (contrato): fecha o modal e manda o problema para a tela de erro (§11.4). */
  function cashMovementFailed(problem: ApiProblem): void {
    setCashMovement(null);
    dispatch({ type: 'apiFailed', problem });
  }

  /** Bipe do autoteste: a camada de API traduzida para o contrato da tela (BR-14, sem interpretar). */
  const resolveBarcode: BarcodeResolver = async (barcode) => {
    const outcome = await api.resolveBarcode(barcode);

    return outcome.ok
      ? { found: true, product: outcome.product }
      : { found: false, problem: outcome.problem };
  };

  switch (state.kind) {
    case 'login':
      return <LoginScreen state={state} api={api} dispatch={dispatch} />;
    case 'openingCash':
      return <OpeningCashScreen state={state} api={api} dispatch={dispatch} />;
    case 'saleOpen':
      // venda concluída (1113): a tela de sucesso fica no lugar da venda até o ENTER
      if (state.receipt !== null) {
        return <SaleSuccessScreen receipt={state.receipt} dispatch={dispatch} />;
      }

      // gaveta (1114): sangria (F7) ou suprimento (F8) sobre a venda — ou antes dela, sem venda
      if (cashMovement !== null) {
        return (
          <CashMovementModal
            registerId={state.register.id}
            kind={cashMovement}
            api={api}
            onClosed={cashMovementClosed}
            onFailed={cashMovementFailed}
          />
        );
      }

      if (discountOpen && state.sale !== null) {
        return (
          <DiscountModal
            saleId={state.sale.id}
            api={api}
            onApplied={discountApplied}
            onFailed={discountFailed}
          />
        );
      }

      if (customerOpen && state.sale !== null) {
        return (
          <CustomerModal
            saleId={state.sale.id}
            customer={customer}
            api={api}
            onUpdated={customerUpdated}
            onFailed={customerFailed}
          />
        );
      }

      if (cancelSaleOpen && state.sale !== null) {
        return (
          <CancelSaleModal
            saleId={state.sale.id}
            api={api}
            onCancelled={saleCancelled}
            onFailed={saleCancelFailed}
          />
        );
      }

      if (priceLookupOpen) {
        return <PriceLookupModal api={api} onFailed={priceLookupFailed} />;
      }

      if (helpOpen) {
        return <HelpModal />;
      }

      return readerSelfTest ? (
        <ReaderSelfTestScreen resolve={resolveBarcode} />
      ) : (
        <SaleScreen
          ref={saleRef}
          state={state}
          now={new Date()}
          api={api}
          dispatch={dispatch}
          customer={customer}
          store={store}
          online={online}
        />
      );
    case 'paying':
      return (
        <PaymentScreen
          ref={paymentRef}
          state={state}
          api={api}
          dispatch={dispatch}
          onCompleted={saleCompleted}
        />
      );
    case 'closingCash':
      return <ClosingCashScreen state={state} api={api} dispatch={dispatch} />;
    case 'error':
      return <ErrorScreen problem={state.problem} dispatch={dispatch} />;
  }
}

/** A venda em cima da mesa agora — venda, pagamento ou fechamento —: o alvo da releitura (1117). */
function currentSaleId(state: State): string | null {
  if (state.kind === 'saleOpen' || state.kind === 'paying' || state.kind === 'closingCash') {
    return state.sale?.id ?? null;
  }

  return null;
}
