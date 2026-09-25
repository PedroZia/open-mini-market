import { useReducer, useRef, useState } from 'react';

import type { CustomerOption, TerminalApi } from '../api/terminalApi';
import { reduce } from '../core/reducer';
import { initialState, type ApiProblem, type SaleView } from '../core/state';
import { CustomerModal } from './CustomerModal';
import { DiscountModal } from './DiscountModal';
import { ErrorScreen } from './ErrorScreen';
import { LoginScreen } from './LoginScreen';
import { OpeningCashScreen } from './OpeningCashScreen';
import { PaymentScreen, type PaymentScreenHandle } from './PaymentScreen';
import { PlaceholderScreen } from './PlaceholderScreen';
import { ReaderSelfTestScreen, type BarcodeResolver } from './ReaderSelfTestScreen';
import { SaleScreen } from './SaleScreen';
import { SaleSuccessScreen } from './SaleSuccessScreen';
import { useRawShortcuts } from './useRawShortcuts';

/**
 * Shell da TUI (§11.2): guarda o estado da operação no reducer puro (1103) e desenha uma tela por
 * `kind`. Login (1106), abertura de caixa (1107), venda (1108/1109) e pagamento (1113) são as telas
 * implementadas; o fechamento ainda aparece como placeholder até o passo que o implementa — a troca
 * de tela é sempre do reducer, nunca da tela.
 *
 * O canal cru do teclado (F1–F12, que o `useInput` do Ink não entrega) é do shell: o hook resolve o
 * atalho no contexto da tela e aqui só se age nas **intenções** com comportamento — o autoteste do
 * leitor no F11 (1108), o desconto no F5 (1111), o cliente no F6 (1112) e o pagamento no F9 (1113),
 * os três primeiros abertos **como overlay da venda**: o corpo da venda sai de cena, então a rajada
 * do leitor não vira item, e com o modal aberto o mapa só resolve ESC (`closeModal`, §11.3). O F5/F6
 * sem venda criada não abre nada: não há desconto nem cliente a vincular antes do primeiro bipe
 * (1109) — e o F9 depende do reducer, que só abre o pagamento com venda e itens (1113).
 *
 * O pagamento é uma tela, não um overlay: o F9 do shell abre (`paymentStarted`) e, já no pagamento,
 * o mesmo F9 **conclui** — a tecla chega pelo canal cru e o shell chama o `complete()` da tela pelo
 * handle, porque a chave de idempotência e o retry são dela (1113). A tela de sucesso aparece
 * enquanto houver `receipt` no estado; o ENTER que inicia a próxima venda é do reducer.
 *
 * O cliente vinculado é o único pedaço da view que não cabe no reducer: o `SaleDetailResponse` só
 * traz o `customerId`, então o shell guarda o par `{id, name}` que a busca local capturou (1112) e o
 * cabeçalho mostra esse nome enquanto a venda do servidor apontar para o mesmo id — o vínculo real
 * é o do servidor, o nome é a anotação da seleção, e ela é esquecida quando a venda conclui (1113).
 */
export function App({ api }: { api: TerminalApi }) {
  const [state, dispatch] = useReducer(reduce, initialState);
  /** Autoteste do leitor aberto sobre a venda: estado de UI, fora do reducer. */
  const [readerSelfTest, setReaderSelfTest] = useState(false);
  /** Modal de desconto aberto sobre a venda (1111): estado de UI, fora do reducer. */
  const [discountOpen, setDiscountOpen] = useState(false);
  /** Modal de cliente aberto sobre a venda (1112): estado de UI, fora do reducer. */
  const [customerOpen, setCustomerOpen] = useState(false);
  /** Cliente que esta sessão vinculou, com o nome da busca; `null` na venda anônima (1112). */
  const [customer, setCustomer] = useState<CustomerOption | null>(null);
  /** Handle da tela de pagamento: é por ele que o F9 do canal cru conclui a venda (1113). */
  const paymentRef = useRef<PaymentScreenHandle | null>(null);

  useRawShortcuts(
    (shortcut) => {
      if (shortcut.type !== 'intent') {
        return;
      }

      if (shortcut.name === 'readerSelfTest') {
        setReaderSelfTest(true);
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
      } else if (shortcut.name === 'checkout') {
        if (state.kind === 'paying') {
          // já no pagamento o F9 conclui a venda; o reducer registra o resumo (1113)
          paymentRef.current?.complete();
        } else {
          // abre o pagamento: o reducer ignora fora da venda ou com a venda sem itens (1113)
          dispatch({ type: 'paymentStarted' });
        }
      } else if (shortcut.name === 'closeModal') {
        setReaderSelfTest(false);
        setDiscountOpen(false);
        setCustomerOpen(false);
      }
    },
    {
      screen: state.kind,
      modal: discountOpen
        ? 'discount'
        : customerOpen
          ? 'customer'
          : readerSelfTest
            ? 'readerSelfTest'
            : null,
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

  /** Falha bloqueante do cliente: fecha o modal e manda o problema para a tela de erro (§11.4). */
  function customerFailed(problem: ApiProblem): void {
    setCustomerOpen(false);
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

      return readerSelfTest ? (
        <ReaderSelfTestScreen resolve={resolveBarcode} />
      ) : (
        <SaleScreen
          state={state}
          now={new Date()}
          api={api}
          dispatch={dispatch}
          customer={customer}
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
      return <PlaceholderScreen title="Fechamento de caixa" step="1115" />;
    case 'error':
      return <ErrorScreen problem={state.problem} dispatch={dispatch} />;
  }
}
