/** @jsxImportSource @opentui/react */
import { useEffect, useMemo, useReducer, useRef, useState } from "react"

import { withProblemGuard } from "../api/problemGuard"
import type { TerminalApi } from "../api/terminalApi"
import { reduce } from "../core/reducer"
import { initialState, type State } from "../core/state"
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
 * (401) volta ao login com aviso, sem descartar a venda preservada, e o 409 de
 * idempotência/concorrência é conferido no servidor (`getSale`) antes de qualquer repetição — o
 * desfecho vira `saleReconciled` com o aviso, ou `apiFailed` quando a própria releitura falha. A
 * conexão (`onConnection`) alimenta a barra de status da venda, e a loja do cabeçalho vem do
 * `GET /auth/me` uma vez por login.
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
  /** Estado da última render: a releitura da reconciliação (1117) lê o id da venda daqui. */
  const latest = useRef(state)
  /** Hora do cabeçalho da venda: o timer vive no shell e a tela só desenha o que recebe. */
  const now = useClock()

  useEffect(() => {
    latest.current = state
  })

  const api = useMemo(
    () =>
      withProblemGuard(rawApi, {
        onSession: (message) => dispatch({ type: "sessionExpired", message }),
        onReconcile: (saleId, message) => void reconcile(saleId, message),
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

  /**
   * 409 de idempotência/concorrência (1117): nada é repetido às cegas — a venda é relida do
   * servidor e o operador vê o estado de verdade, com o aviso do mapa central à vista. A falha da
   * própria releitura é que decide o desfecho dela (rede bloqueia, sessão volta ao login).
   */
  async function reconcile(saleId: string | null, message: string): Promise<void> {
    const id = saleId ?? currentSaleId(latest.current)

    if (id === null) {
      return
    }

    const outcome = await api.getSale(id)

    if (outcome.ok) {
      dispatch({ type: "saleReconciled", sale: outcome.sale, notice: message })
      return
    }

    dispatch({ type: "apiFailed", problem: outcome.problem })
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
    return (
      <SaleScreen
        state={state}
        api={api}
        dispatch={dispatch}
        now={now}
        customer={null}
        store={store}
        online={online}
      />
    )
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

/** A venda em cima da mesa agora — venda, pagamento ou fechamento —: o alvo da releitura (1117). */
function currentSaleId(state: State): string | null {
  if (state.kind === "saleOpen" || state.kind === "paying" || state.kind === "closingCash") {
    return state.sale?.id ?? null
  }

  return null
}
