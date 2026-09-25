import { useReducer, useState } from 'react';

import type { TerminalApi } from '../api/terminalApi';
import { reduce } from '../core/reducer';
import { initialState } from '../core/state';
import { ErrorScreen } from './ErrorScreen';
import { LoginScreen } from './LoginScreen';
import { OpeningCashScreen } from './OpeningCashScreen';
import { PlaceholderScreen } from './PlaceholderScreen';
import { ReaderSelfTestScreen, type BarcodeResolver } from './ReaderSelfTestScreen';
import { SaleScreen } from './SaleScreen';
import { useRawShortcuts } from './useRawShortcuts';

/**
 * Shell da TUI (§11.2): guarda o estado da operação no reducer puro (1103) e desenha uma tela por
 * `kind`. Login (1106), abertura de caixa (1107) e venda (1108) são as telas implementadas; os
 * estados seguintes aparecem como placeholder explícito até o passo que os implementa — a troca de
 * tela é sempre do reducer, nunca da tela.
 *
 * O canal cru do teclado (F1–F12, que o `useInput` do Ink não entrega) é do shell: o hook resolve o
 * atalho no contexto da tela e aqui só se age nas **intenções** com comportamento — hoje o autoteste
 * do leitor no F11, aberto como overlay da venda e fechado com ESC (§11.3). As ações do reducer
 * (`confirm`/`cancel`) continuam com as telas que já as tratam, e os demais atalhos (desconto,
 * cliente, pagamento...) são dos passos 1111+ e por ora são ignorados.
 */
export function App({ api }: { api: TerminalApi }) {
  const [state, dispatch] = useReducer(reduce, initialState);
  /** Autoteste do leitor aberto sobre a venda: estado de UI, fora do reducer. */
  const [readerSelfTest, setReaderSelfTest] = useState(false);

  useRawShortcuts(
    (shortcut) => {
      if (shortcut.type !== 'intent') {
        return;
      }

      if (shortcut.name === 'readerSelfTest') {
        setReaderSelfTest(true);
      } else if (shortcut.name === 'closeModal') {
        setReaderSelfTest(false);
      }
    },
    { screen: state.kind, modal: readerSelfTest ? 'readerSelfTest' : null },
  );

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
      return readerSelfTest ? (
        <ReaderSelfTestScreen resolve={resolveBarcode} />
      ) : (
        <SaleScreen state={state} now={new Date()} />
      );
    case 'paying':
      return <PlaceholderScreen title="Pagamento" step="1113" />;
    case 'closingCash':
      return <PlaceholderScreen title="Fechamento de caixa" step="1115" />;
    case 'error':
      return <ErrorScreen problem={state.problem} dispatch={dispatch} />;
  }
}
