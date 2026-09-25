/** @jsxImportSource @opentui/react */
import { useEffect, useMemo, useReducer, useRef, useState } from "react"

import { withProblemGuard } from "../api/problemGuard"
import type { TerminalApi } from "../api/terminalApi"
import { reduce } from "../core/reducer"
import { initialState } from "../core/state"
import { useClock } from "./clock"
import { ErrorScreen } from "./ErrorScreen"
import { LoginScreen } from "./LoginScreen"
import { OpeningCashScreen } from "./OpeningCashScreen"
import { SaleScreen } from "./SaleScreen"
import { theme } from "./theme"

/**
 * Shell roteador da UI nova (1124a/1124b/1125a): guarda a operação no reducer puro (1103) e desenha
 * uma tela por `kind`, como o App da Ink — a troca de tela é sempre do reducer, nunca da tela. A
 * diferença é o teclado: cada tela registra o próprio handler no hook global (`useGlobalKeyboard`),
 * então aqui não há canal cru.
 *
 * A entrada e a venda estão de pé: `login`, `openingCash`, `error` e `saleOpen` roteiam para as telas
 * de verdade; o pagamento e o fechamento ainda não têm rota (1127).
 *
 * A API que as telas recebem é a embrulhada pela guarda (1117), como no App da Ink: sessão caída
 * (401) volta ao login com aviso, sem descartar a venda preservada. O login não produz os 409 de
 * idempotência/concorrência — a releitura da venda chega com o bipe (1125b), quando a tela de venda
 * passar a chamar a API —, então `onReconcile` ainda não tem o que reler e fica explícito para a
 * guarda não perder o desfecho no caminho; a conexão (`onConnection`) já alimenta a barra de status
 * da venda, e a loja do cabeçalho vem do `GET /auth/me` uma vez por login.
 *
 * O relógio do cabeçalho é **deste** shell (`useClock`, um tique por segundo): a tela recebe a hora
 * por prop, então o desenho dela é determinístico no teste e a hora não congela como na Ink.
 */
export function App({ api: rawApi }: { api: TerminalApi }) {
  const [state, dispatch] = useReducer(reduce, initialState)
  /** Conexão com o servidor (1117): cai na falha de transporte e volta a cada resposta dele. */
  const [online, setOnline] = useState(true)
  /** Loja do cabeçalho (`GET /auth/me`, 1117); `null` enquanto não chegou — ou se falhou. */
  const [store, setStore] = useState<string | null>(null)
  /** A loja é perguntada uma vez por login (1117), não a cada troca de tela. */
  const storeAsked = useRef(false)
  /** Hora do cabeçalho da venda: o timer vive no shell e a tela só desenha o que recebe. */
  const now = useClock()

  const api = useMemo(
    () =>
      withProblemGuard(rawApi, {
        onSession: (message) => dispatch({ type: "sessionExpired", message }),
        onReconcile: () => {},
        onConnection: (next) => setOnline((current) => (current === next ? current : next)),
      }),
    [rawApi],
  )

  /**
   * Loja do cabeçalho (1117): uma vez por login, na entrada da operação. A falha não bloqueia a
   * venda — o cabeçalho fica sem loja e o operador segue.
   */
  useEffect(() => {
    if (state.kind === "login") {
      // login novo (troca de operador ou queda de sessão): a loja é perguntada de novo
      storeAsked.current = false
      setStore(null)
      return
    }

    if (state.kind !== "saleOpen" || storeAsked.current) {
      return
    }

    storeAsked.current = true
    void loadStore()
  }, [state.kind])

  /** Lê a loja da sessão e guarda o rótulo do cabeçalho; falha ou loja ausente deixam `store` nulo. */
  async function loadStore(): Promise<void> {
    const outcome = await api.currentSession()

    if (!outcome.ok || outcome.store === null) {
      return
    }

    setStore(outcome.store.name === "" ? outcome.store.code : outcome.store.name)
  }

  if (state.kind === "login") {
    return (
      // o F12 (1118) ainda não existe na UI nova: o login nasce sem caixa preferido
      <LoginScreen state={state} api={api} dispatch={dispatch} preferredRegisterId={null} />
    )
  }

  if (state.kind === "openingCash") {
    return <OpeningCashScreen state={state} api={api} dispatch={dispatch} />
  }

  if (state.kind === "saleOpen") {
    // o cliente vinculado (1112) e o `api`/`dispatch` da venda chegam no 1125b (bipe)
    return <SaleScreen state={state} now={now} customer={null} store={store} online={online} />
  }

  if (state.kind === "error") {
    return <ErrorScreen problem={state.problem} dispatch={dispatch} />
  }

  // o pagamento e o fechamento ainda não têm rota (1127)
  return (
    <box width="100%" height="100%">
      <text fg={theme.muted}>Tela ainda não migrada</text>
    </box>
  )
}
