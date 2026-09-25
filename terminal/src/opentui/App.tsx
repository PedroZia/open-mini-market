/** @jsxImportSource @opentui/react */
import { useEffect, useMemo, useReducer, useRef, useState } from "react"

import { withProblemGuard } from "../api/problemGuard"
import type { CustomerOption, TerminalApi } from "../api/terminalApi"
import { reduce } from "../core/reducer"
import { initialState, type State } from "../core/state"
import { useClock } from "./clock"
import { ErrorScreen } from "./ErrorScreen"
import { LoginScreen } from "./LoginScreen"
import { OpeningCashScreen } from "./OpeningCashScreen"
import { PaymentScreen } from "./PaymentScreen"
import { SaleScreen } from "./SaleScreen"
import { SaleSuccessScreen } from "./SaleSuccessScreen"
import { theme } from "./theme"

/**
 * Shell roteador da UI nova (1124a/1124b/1125a): guarda a operação no reducer puro (1103) e desenha
 * uma tela por `kind`, como o App da Ink — a troca de tela é sempre do reducer, nunca da tela. A
 * diferença é o teclado: cada tela registra o próprio handler no hook global (`useGlobalKeyboard`),
 * então aqui não há canal cru.
 *
 * A entrada e a venda estão de pé: `login`, `openingCash`, `error`, `saleOpen` (com a tela de
 * sucesso por cima quando há `receipt`) e `paying` roteiam para as telas de verdade; só o
 * fechamento ainda não tem rota (1127b).
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
 *
 * O shell também guarda as anotações locais dos modais do 1126c: o cliente do F6 (o nome do
 * cabeçalho é a seleção da busca, não a resposta do servidor) e o caixa preferido do F12 (a sessão
 * de caixa continua aberta — o próximo operador entra no mesmo caixa). Nenhum dos dois vira estado
 * do reducer: são rótulos de tela, esquecidos quando a venda ou o login saem de cena.
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
  /** Cliente que esta sessão vinculou, com o nome da busca (F6, 1126c); `null` na venda anônima. */
  const [customer, setCustomer] = useState<CustomerOption | null>(null)
  /**
   * Caixa em uso quando o F12 confirmou (1126c): o login seguinte nasce com ele selecionado — a
   * sessão de caixa continua aberta e o próximo operador entra no **mesmo** caixa. É anotação de
   * tela, não estado da operação: morre quando o login sai de cena.
   */
  const [preferredRegister, setPreferredRegister] = useState<string | null>(null)

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
   * venda — o cabeçalho fica sem loja e o operador segue. O cliente anotado pertence à venda que
   * acabou de sair de cena, então o login novo nasce sem ele (F6, 1126c).
   */
  useEffect(() => {
    if (state.kind === "login") {
      // login novo (troca de operador ou queda de sessão): a loja é perguntada de novo
      storeAsked.current = false
      setStore(null)
      setCustomer(null)
      return
    }

    // saiu do login: o "mesmo caixa" da troca (F12, 1126c) já foi escolhido — ou descartado
    setPreferredRegister(null)

    if (state.kind !== "saleOpen" || storeAsked.current) {
      return
    }

    storeAsked.current = true
    void loadStore()
  }, [state.kind])

  /**
   * A venda que sai de cena leva o nome do cliente junto (F6, 1126c): cancelada (F4, 1126d) ou
   * concluída (1127a), a anotação local era daquela venda e não vale para a próxima.
   */
  useEffect(() => {
    if (state.kind === "saleOpen" && state.sale === null) {
      setCustomer(null)
    }
  }, [state])

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

  /**
   * F12 confirmado (1126c): a sessão de login terminou e o caixa continua aberto. O shell esquece o
   * cliente anotado (a venda dele foi cancelada antes de chegar aqui) e lembra o caixa em uso para o
   * login nascer com ele selecionado ("mesmo caixa"). A transição é do reducer (`sessionEnded`):
   * volta ao login sem passar pelo `cashClosed`, que significa caixa fechado.
   */
  function operatorSwitched(registerId: string): void {
    setCustomer(null)
    setPreferredRegister(registerId)
    dispatch({ type: "sessionEnded" })
  }

  if (state.kind === "login") {
    return (
      <LoginScreen
        state={state}
        api={api}
        dispatch={dispatch}
        preferredRegisterId={preferredRegister}
      />
    )
  }

  if (state.kind === "openingCash") {
    return <OpeningCashScreen state={state} api={api} dispatch={dispatch} />
  }

  if (state.kind === "saleOpen") {
    // venda concluída (1127a): a tela de sucesso fica no lugar da venda até o ENTER
    if (state.receipt !== null) {
      return <SaleSuccessScreen receipt={state.receipt} dispatch={dispatch} />
    }

    return (
      <SaleScreen
        state={state}
        api={api}
        dispatch={dispatch}
        now={now}
        customer={customer}
        store={store}
        online={online}
        onCustomerChanged={setCustomer}
        onOperatorSwitched={() => operatorSwitched(state.register.id)}
      />
    )
  }

  if (state.kind === "paying") {
    return (
      <PaymentScreen
        state={state}
        api={api}
        dispatch={dispatch}
        onCompleted={() => setCustomer(null)}
      />
    )
  }

  if (state.kind === "error") {
    return <ErrorScreen problem={state.problem} dispatch={dispatch} />
  }

  // o fechamento ainda não tem rota (1127b)
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
