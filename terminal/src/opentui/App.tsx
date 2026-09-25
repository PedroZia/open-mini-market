/** @jsxImportSource @opentui/react */
import { useMemo, useReducer } from "react"

import { withProblemGuard } from "../api/problemGuard"
import type { TerminalApi } from "../api/terminalApi"
import { reduce } from "../core/reducer"
import { initialState } from "../core/state"
import { ErrorScreen } from "./ErrorScreen"
import { LoginScreen } from "./LoginScreen"
import { OpeningCashScreen } from "./OpeningCashScreen"
import { theme } from "./theme"

/**
 * Shell roteador da UI nova (1124a/1124b): guarda a operação no reducer puro (1103) e desenha uma
 * tela por `kind`, como o App da Ink — a troca de tela é sempre do reducer, nunca da tela. A
 * diferença é o teclado: cada tela registra o próprio handler no hook global (`useGlobalKeyboard`),
 * então aqui não há canal cru.
 *
 * A entrada está fechada (1124b): `login`, `openingCash` e `error` roteiam para as telas de verdade;
 * a venda (1125), o pagamento e o fechamento (1127) ainda não têm rota.
 *
 * A API que as telas recebem é a embrulhada pela guarda (1117), como no App da Ink: sessão caída
 * (401) volta ao login com aviso, sem descartar a venda preservada. O login não produz os 409 de
 * idempotência/concorrência — a releitura da venda chega com a tela de venda (1125) —, então
 * `onReconcile` ainda não tem o que reler e fica explícito para a guarda não perder o desfecho no
 * caminho; o indicador de conexão (`onConnection`) também só ganha destino com a barra de status
 * (1125).
 */
export function App({ api: rawApi }: { api: TerminalApi }) {
  const [state, dispatch] = useReducer(reduce, initialState)

  const api = useMemo(
    () =>
      withProblemGuard(rawApi, {
        onSession: (message) => dispatch({ type: "sessionExpired", message }),
        onReconcile: () => {},
        onConnection: () => {},
      }),
    [rawApi],
  )

  if (state.kind === "login") {
    return (
      // o F12 (1118) ainda não existe na UI nova: o login nasce sem caixa preferido
      <LoginScreen state={state} api={api} dispatch={dispatch} preferredRegisterId={null} />
    )
  }

  if (state.kind === "openingCash") {
    return <OpeningCashScreen state={state} api={api} dispatch={dispatch} />
  }

  if (state.kind === "error") {
    return <ErrorScreen problem={state.problem} dispatch={dispatch} />
  }

  // a venda (1125), o pagamento e o fechamento (1127) ainda não têm rota
  return (
    <box width="100%" height="100%">
      <text fg={theme.muted}>Tela ainda não migrada</text>
    </box>
  )
}
