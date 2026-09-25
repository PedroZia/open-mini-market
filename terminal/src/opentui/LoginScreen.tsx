/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useState, type Dispatch } from "react"

import type { CashRegisterOption, TerminalApi } from "../api/terminalApi"
import type { Action } from "../core/reducer"
import type { LoginState, Operator } from "../core/state"
import { isPrintable } from "./adapters/scanner"
import { useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Entrada do operador (passo 1106, §11.2) portada para a UI nova (1124a): usuário/senha com a senha
 * mascarada e, aceito o login, a escolha do caixa ativo (`GET /api/v1/cash-registers`).
 *
 * O `<input>` do OpenTUI não tem máscara (docs do componente), então os dois campos são tratados
 * manualmente pela tela: texto imprimível entra no campo focado, BACKSPACE/DEL apagam, TAB/↑↓
 * alternam e ENTER envia. A senha é renderizada só como `•` — e nunca chega ao scanner do leitor
 * (o handler da tela consome a tecla antes do hook global, 1123b/1124a).
 *
 * O primeiro login nasce **sem caixa**: a sessão provisória só serve para listar os caixas. Ao
 * confirmar a escolha (ajuste do 1107), a tela revoga essa sessão (`POST /auth/logout`) e loga de
 * novo com o `cashRegisterId` escolhido — é o que faz a sessão nascer vinculada ao caixa (passo
 * 607). A senha fica na memória da tela até esse segundo login e só então é descartada; a falha da
 * revogação é tolerada de propósito (`api.logout()` não rejeita e o token local sai de cena).
 *
 * A tela não decide o destino: relata os fatos ao reducer (1103) e ele troca de estado —
 * `loginSucceeded` leva para a abertura de caixa, `loginRejected` **fica aqui** com a mensagem do
 * servidor e `apiFailed` vai para a tela de erro com volta. Nada de conta, total ou parse (BR-12).
 *
 * O `notice` do estado (1117) é o aviso da sessão que caiu no meio da operação — o login volta para
 * cá com a venda ainda em memória, e o mesmo caixa a retoma depois de abrir o turno de novo. Na
 * troca de operador (1118) não há venda a retomar: o caixa continua aberto e o
 * `preferredRegisterId` faz a lista nascer com ele selecionado — o operador novo só confirma.
 *
 * O token fica só na sessão em memória (`src/api/session.ts`), nunca no estado da tela nem na
 * saída — a senha também não é renderizada em momento algum.
 */

export type LoginScreenProps = {
  /** Estado do reducer: `failure` é a mensagem do último login recusado pelo servidor. */
  state: LoginState
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>
  /**
   * Caixa a deixar selecionado quando a lista chegar (troca de operador, 1118): o F12 mantém a
   * sessão de caixa aberta, então o login seguinte nasce no **mesmo** caixa — o operador só confirma.
   */
  preferredRegisterId?: string | null
}

/** Etapa da tela: credenciais e, com o login aceito, a escolha do caixa. */
type Stage =
  | { kind: "credentials" }
  | {
      kind: "registers"
      operator: Operator
      /** `null` enquanto a lista não chegou: a tela mostra o carregamento. */
      registers: CashRegisterOption[] | null
      selected: number
    }

export function LoginScreen({ state, api, dispatch, preferredRegisterId }: LoginScreenProps) {
  const [stage, setStage] = useState<Stage>({ kind: "credentials" })
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [focus, setFocus] = useState<"username" | "password">("username")
  const [busy, setBusy] = useState(false)
  /** Aviso local do formulário (campo vazio); a recusa do servidor vem em `state.failure`. */
  const [hint, setHint] = useState<string | null>(null)

  // o teclado é da tela (como o `useInput` do Ink): o handler tem prioridade sobre o mapa/leitor
  useGlobalKeyboard({
    onKey: (event) => (stage.kind === "credentials" ? handleCredentials(event) : handleRegisters(event)),
  })

  function handleCredentials(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    // ESC é a última saída do entry (fora do React): o handler não pode engoli-la
    if (event.name === "escape") {
      return false
    }

    // combo do sistema/formulário não é texto do campo (mesma regra do `resolveKey` do Ink)
    if (event.ctrl || event.meta) {
      return true
    }

    // requisição em andamento: ENTER repetido não dispara dois logins
    if (busy) {
      return true
    }

    if (event.name === "return") {
      void submit()
      return true
    }

    if (event.name === "tab" || event.name === "up" || event.name === "down") {
      setFocus((current) => (current === "username" ? "password" : "username"))
      return true
    }

    if (event.name === "backspace" || event.name === "delete") {
      setHint(null)
      editField((value) => value.slice(0, -1))
      return true
    }

    if (!isPrintable(event.sequence)) {
      // F1–F12 e demais controles não são texto do campo: seguem o mapa do hook global
      return false
    }

    setHint(null)
    editField((value) => value + event.sequence)
    return true
  }

  function handleRegisters(event: KeyEvent): boolean {
    if (event.eventType === "release" || event.name === "escape") {
      return false
    }

    // revogação da sessão provisória + login vinculado em andamento: ENTER repetido não dispara dois
    if (busy) {
      return true
    }

    if (event.name === "up") {
      move(-1)
      return true
    }

    if (event.name === "down") {
      move(1)
      return true
    }

    if (event.name === "return") {
      void confirm()
      return true
    }

    // a etapa de caixa não tem texto: o resto não vaza para o leitor nem para o campo
    return true
  }

  function editField(change: (value: string) => string): void {
    if (focus === "username") {
      setUsername(change)
    } else {
      setPassword(change)
    }
  }

  async function submit(): Promise<void> {
    if (username.trim() === "" || password === "") {
      setHint("informe usuário e senha")
      return
    }

    setHint(null)
    setBusy(true)

    const outcome = await api.login(username, password)
    setBusy(false)

    if (outcome.ok) {
      // a senha fica na memória: o login vinculado ao caixa (1107) precisa dela de novo
      await loadRegisters(outcome.operator)
      return
    }

    if (outcome.kind === "rejected") {
      setPassword("") // a senha recusada não fica no campo: o operador digita de novo
      dispatch({ type: "loginRejected", message: outcome.message })
      return
    }

    dispatch({ type: "apiFailed", problem: outcome.problem })
  }

  /** Login aceito: busca os caixas ativos; a escolha do operador é a etapa seguinte. */
  async function loadRegisters(operator: Operator): Promise<void> {
    setStage({ kind: "registers", operator, registers: null, selected: 0 })

    const outcome = await api.listCashRegisters()
    if (!outcome.ok) {
      dispatch({ type: "apiFailed", problem: outcome.problem })
      return
    }

    // o caixa da troca de operador (1118) nasce selecionado; sem ele na lista, o primeiro mesmo
    const preferred = outcome.registers.findIndex((register) => register.id === preferredRegisterId)

    setStage((current) =>
      current.kind === "registers"
        ? { ...current, registers: outcome.registers, selected: preferred < 0 ? 0 : preferred }
        : current,
    )
  }

  /** Anda na lista em ciclo: com poucos caixas, a seta não trava na ponta. */
  function move(step: number): void {
    setStage((current) => {
      if (current.kind !== "registers" || current.registers === null || current.registers.length === 0) {
        return current
      }

      const count = current.registers.length
      return { ...current, selected: (current.selected + step + count) % count }
    })
  }

  /**
   * Escolha do caixa: revoga a sessão provisória e loga de novo com o `cashRegisterId` para a
   * sessão nascer vinculada ao caixa (passo 607).
   *
   * A falha do logout é tolerada de propósito (`api.logout()` não rejeita e limpa o token local):
   * uma sessão prestes a ser substituída não pode travar o operador, e sem limpar o token o login
   * seguinte iria com o token revogado — 401 antes de chegar ao recurso. A órfã expira no idle
   * timeout do servidor. Recusa (400/401/423) volta às credenciais com a mensagem do servidor; o
   * resto bloqueia na tela de erro.
   */
  async function confirm(): Promise<void> {
    if (stage.kind !== "registers" || stage.registers === null) {
      return
    }

    const chosen = stage.registers[stage.selected]
    if (chosen === undefined) {
      return
    }

    setBusy(true)
    await api.logout()
    const outcome = await api.login(username, password, chosen.id)
    setBusy(false)

    if (outcome.ok) {
      setPassword("") // a senha só sai da memória quando o login vinculado termina
      dispatch({
        type: "loginSucceeded",
        operator: outcome.operator,
        register: { id: chosen.id, name: chosen.name === "" ? chosen.code : chosen.name },
      })
      return
    }

    if (outcome.kind === "rejected") {
      setPassword("") // a senha recusada não fica no campo: o operador digita de novo
      setStage({ kind: "credentials" })
      dispatch({ type: "loginRejected", message: outcome.message })
      return
    }

    dispatch({ type: "apiFailed", problem: outcome.problem })
  }

  const message = state.failure ?? hint

  if (stage.kind === "registers") {
    return (
      <box flexDirection="column" width="100%" height="100%">
        <text attributes={TextAttributes.BOLD}>Escolha o caixa</text>
        <text>{`Operador: ${stage.operator.name}`}</text>
        {state.notice === undefined ? null : <NoticeRow notice={state.notice} />}
        <text> </text>
        {stage.registers === null ? (
          <text fg={theme.muted}>carregando caixas...</text>
        ) : stage.registers.length === 0 ? (
          <text fg={theme.warning}>nenhum caixa ativo — procure o suporte antes de abrir o PDV</text>
        ) : (
          stage.registers.map((register, index) => (
            <text key={register.id} fg={index === stage.selected ? theme.accent : theme.text}>
              {`${index === stage.selected ? "›" : " "} ${register.code} ${register.name} — ${describeSession(register)}`}
            </text>
          ))
        )}
        <text> </text>
        {busy ? <text fg={theme.muted}>vinculando ao caixa...</text> : null}
        <text fg={theme.muted}>↑↓ escolhe · ENTER confirma</text>
      </box>
    )
  }

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text attributes={TextAttributes.BOLD}>PDV minimercado — entrada do operador</text>
      <text> </text>
      <text>{`${focus === "username" ? "›" : " "} Usuário: ${username}`}</text>
      <text>{`${focus === "password" ? "›" : " "} Senha: ${"•".repeat(password.length)}`}</text>
      {state.notice === undefined ? null : <NoticeRow notice={state.notice} />}
      {message === null ? null : <text fg={theme.danger}>{message}</text>}
      {busy ? <text fg={theme.muted}>entrando...</text> : null}
      <text> </text>
      <text fg={theme.muted}>TAB alterna os campos · ENTER entra</text>
    </box>
  )
}

/** Aviso da sessão que caiu (1117): amarelo, uma linha, com a venda preservada explicada ao operador. */
function NoticeRow({ notice }: { notice: string }) {
  return <text fg={theme.warning}>{notice}</text>
}

/** Como a lista descreve a sessão do caixa: quem está nele, ou livre. */
function describeSession(register: CashRegisterOption): string {
  if (!register.open) {
    return "livre"
  }

  return register.operatorName === null ? "aberto" : `aberto com ${register.operatorName}`
}
