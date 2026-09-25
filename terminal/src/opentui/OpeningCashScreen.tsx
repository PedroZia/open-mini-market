/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useState, type Dispatch } from "react"

import type { TerminalApi } from "../api/terminalApi"
import { centsToAmount, digitsToCents, formatBRL } from "../core/money"
import type { Action } from "../core/reducer"
import type { OpeningCashState } from "../core/state"
import { isPrintable } from "./adapters/scanner"
import { useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Abertura de caixa (passo 1107, §11.2) portada para a UI nova (1124b): o operador informa o fundo
 * de troco e o servidor abre a sessão (`POST /cash-registers/{id}/open`, 201 `CashSessionResponse`).
 * O campo é mascarado em centavos — os dígitos viram reais (`1250` → `R$ 12,50`) — e a tela não
 * calcula nada (BR-12): envia o valor digitado e segue com a sessão que o servidor devolveu.
 *
 * O teclado é da tela (`useGlobalKeyboard`), como no login da UI nova: o handler tem prioridade
 * sobre o mapa/leitor — dígitos entram no campo, BACKSPACE/DEL apagam (a máscara é de dígitos, então
 * letra, sinal e espaço não entram) e ENTER envia. ESC não é consumido: é a última saída do entry
 * (`installExitKey` do `index.tsx`), como no port do login.
 *
 * Caixa já aberto (409 `CASH_REGISTER_ALREADY_OPEN`) não é falha: a tela busca a sessão existente
 * (`GET /cash-registers/{id}/current-session`), avisa e espera o ENTER para seguir para a venda com
 * ela — sem reabrir nada. Se nem a sessão corrente puder ser lida, aí sim é falha bloqueante.
 *
 * A tela não decide o destino: relata os fatos ao reducer (1103) — `cashOpened` leva para a venda e
 * `apiFailed` vai para a tela de erro, que volta para cá.
 */

export type OpeningCashScreenProps = {
  /** Estado do reducer: operador e caixa escolhidos no login (1124a). */
  state: OpeningCashState
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>
}

/** Caixa já aberto: o aviso que a tela dá quando o servidor responde 409. */
const ALREADY_OPEN_NOTICE = "caixa já está aberto — seguindo para a venda com a sessão existente"

export function OpeningCashScreen({ state, api, dispatch }: OpeningCashScreenProps) {
  /** Dígitos do campo, sem máscara: `1250` é o estado; `R$ 12,50` é o que se vê. */
  const [digits, setDigits] = useState("")
  const [busy, setBusy] = useState(false)
  /** Sessão existente do caixa já aberto aguardando o ENTER; `null` enquanto o campo está ativo. */
  const [alreadyOpenSessionId, setAlreadyOpenSessionId] = useState<string | null>(null)
  /** Aviso local do formulário (valor vazio); a falha da API vai para a tela de erro. */
  const [hint, setHint] = useState<string | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    // ESC é a última saída do entry (fora do React): o handler não pode engoli-la
    if (event.name === "escape") {
      return false
    }

    // requisição em andamento: ENTER repetido não dispara duas aberturas
    if (busy) {
      return true
    }

    if (alreadyOpenSessionId !== null) {
      // o aviso do caixa já aberto só reconhece: ENTER segue com a sessão existente
      if (event.name === "return") {
        dispatch({ type: "cashOpened", sessionId: alreadyOpenSessionId })
      }

      return true
    }

    if (event.name === "return") {
      void submit()
      return true
    }

    if (event.name === "backspace" || event.name === "delete") {
      setHint(null)
      setDigits((current) => current.slice(0, -1))
      return true
    }

    // combo do sistema/formulário não é dígito do campo (mesma regra do `resolveKey` do Ink)
    if (event.ctrl || event.meta) {
      return false
    }

    // F1–F12, setas e demais controles não são texto do campo: seguem o mapa do hook global
    if (!isPrintable(event.sequence)) {
      return false
    }

    // a máscara é de dígitos: letra, sinal ou espaço não entram no campo
    const typed = event.sequence.replace(/\D/g, "")
    if (typed === "") {
      return false
    }

    setHint(null)
    setDigits((current) => current + typed)
    return true
  }

  async function submit(): Promise<void> {
    if (digits === "") {
      setHint("informe o valor de abertura")
      return
    }

    setHint(null)
    setBusy(true)

    const outcome = await api.openCashRegister(
      state.register.id,
      centsToAmount(digitsToCents(digits)),
    )

    if (outcome.ok) {
      setBusy(false)
      dispatch({ type: "cashOpened", sessionId: outcome.sessionId })
      return
    }

    if (outcome.kind === "alreadyOpen") {
      const current = await api.currentCashSession(state.register.id)
      setBusy(false)

      if (!current.ok) {
        // o caixa estava aberto e a sessão não pôde ser lida: sem sessão não há venda — bloqueia
        dispatch({ type: "apiFailed", problem: current.problem })
        return
      }

      setAlreadyOpenSessionId(current.sessionId)
      return
    }

    setBusy(false)
    dispatch({ type: "apiFailed", problem: outcome.problem })
  }

  const amount = formatBRL(digitsToCents(digits))

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text attributes={TextAttributes.BOLD}>Abertura de caixa</text>
      <text>{`Operador: ${state.operator.name} · Caixa: ${state.register.name}`}</text>
      <text> </text>
      <text fg={digits === "" ? theme.muted : theme.text}>{`Fundo de troco: ${amount}`}</text>
      {digits === "" ? (
        <text fg={theme.muted}>digite o valor de abertura: 1250 vira R$ 12,50</text>
      ) : null}
      {hint === null ? null : <text fg={theme.danger}>{hint}</text>}
      {alreadyOpenSessionId === null ? null : (
        <text fg={theme.warning}>{`Aviso: ${ALREADY_OPEN_NOTICE}`}</text>
      )}
      {busy ? <text fg={theme.muted}>abrindo...</text> : null}
      <text> </text>
      <text fg={theme.muted}>
        {alreadyOpenSessionId === null ? "BACKSPACE corrige · ENTER abre o caixa" : "ENTER continua"}
      </text>
    </box>
  )
}
