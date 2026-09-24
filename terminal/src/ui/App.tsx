import { useReducer } from 'react';

import type { TerminalApi } from '../api/terminalApi';
import { reduce } from '../core/reducer';
import { initialState } from '../core/state';
import { ErrorScreen } from './ErrorScreen';
import { LoginScreen } from './LoginScreen';
import { PlaceholderScreen } from './PlaceholderScreen';

/**
 * Shell da TUI (§11.2): guarda o estado da operação no reducer puro (1103) e desenha uma tela por
 * `kind`. O login é a tela implementada (1106); os estados seguintes aparecem como placeholder
 * explícito até o passo que os implementa — a troca de tela é sempre do reducer, nunca da tela.
 */
export function App({ api }: { api: TerminalApi }) {
  const [state, dispatch] = useReducer(reduce, initialState);

  switch (state.kind) {
    case 'login':
      return <LoginScreen state={state} api={api} dispatch={dispatch} />;
    case 'openingCash':
      return <PlaceholderScreen title="Abertura de caixa" step="1107" />;
    case 'saleOpen':
      return <PlaceholderScreen title="Venda" step="1108" />;
    case 'paying':
      return <PlaceholderScreen title="Pagamento" step="1113" />;
    case 'closingCash':
      return <PlaceholderScreen title="Fechamento de caixa" step="1115" />;
    case 'error':
      return <ErrorScreen problem={state.problem} dispatch={dispatch} />;
  }
}
