/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useEffect, useRef, useState, type Dispatch } from "react"

import type { CashSessionSummaryView, TerminalApi } from "../api/terminalApi"
import { centsToAmount, digitsToCents, formatAmount, formatBRL } from "../core/money"
import type { Action } from "../core/reducer"
import type { ClosingCashState } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Fechamento de caixa (F10, passo 1115) portado para a UI nova (1127b): a tela do fim do turno
 * busca o resumo que o servidor devolveu (`GET /cash-sessions/{id}/summary`, passos 612/909) —
 * aberto, esperado, quebra das vendas por forma de pagamento e sangrias/suprimentos —, o operador
 * digita o **valor contado** com a máscara de centavos (o mesmo padrão do campo da abertura, 1124b)
 * e o ENTER envia o fechamento. A TUI não calcula nada (BR-12): o esperado é do resumo e a
 * **diferença é a do servidor**, exibida no bloco do fechamento depois do 200 — sem conta local.
 *
 * A `Idempotency-Key` é desta tela (§8): o retry do ENTER reusa a mesma chave, então uma resposta
 * perdida vira replay no servidor em vez de um segundo fechamento; mudar o valor digitado (ou
 * receber uma recusa) descarta a chave e a próxima tentativa é outra operação.
 *
 * Recusas ficam **na própria tela**, sem perder o contado: 403 sem `cash.close` (BR-10), 400 do
 * valor e o 409 `SESSION_HAS_OPEN_SALES` — venda em andamento no caixa, que o operador resolve
 * voltando com o ESC e cancelando a venda no F4 (o próprio texto da mensagem diz isso). Rede/5xx
 * mostram o aviso de retry e o mesmo ENTER refaz; a falha bloqueante vai para a tela de erro pela
 * mão do shell (`apiFailed`), que guarda o estado e volta para cá com o resumo relido.
 *
 * **Com o caixa fechado** o turno acabou: a conferência do servidor fica à vista e a saída é o
 * login — o ENTER revoga a sessão provisória (`POST /auth/logout`, que esquece o token mesmo se a
 * revogação falhar) e **qualquer outra tecla** volta ao login sem revogar (o token antigo morre
 * sozinho no idle timeout e é substituído no próximo login). O ESC não volta para a venda com o
 * caixa já fechado: quem decide isso é o reducer (1115).
 *
 * O leitor continua bipando: a **rajada** do hardware é descartada pelo mesmo limiar do
 * `core/scanner` (< 50 ms, como nos modais) e o ENTER colado nela é o terminador do bipe, não o
 * "sim" do operador — um código bipado por engano não vira contado nem fecha o caixa.
 */

export type ClosingCashScreenProps = {
  /** Estado do reducer: operador, caixa, sessão e a venda preservada para o ESC (1103). */
  state: ClosingCashState
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>
}

/** As cinco formas do contrato, na ordem em que a linha do resumo as mostra (passo 909). */
const METHODS = ["CASH", "PIX", "DEBIT", "CREDIT", "VOUCHER"] as const

/** Rótulos pt-BR das formas; a chave do mapa continua sendo a do contrato. */
const METHOD_LABELS: Readonly<Record<(typeof METHODS)[number], string>> = {
  CASH: "DINHEIRO",
  PIX: "PIX",
  DEBIT: "DÉBITO",
  CREDIT: "CRÉDITO",
  VOUCHER: "VOUCHER",
}

/** Máscara do valor, no mesmo espírito da abertura de caixa (1124b). */
const VALUE_HINT = "digite o valor contado: 1250 vira R$ 12,50"
const MISSING_VALUE = "informe o valor contado"
const LOADING = "lendo o resumo do caixa…"
const SENDING = "fechando o caixa…"
const RETRY_NOTICE = "falha ao fechar o caixa — ENTER tenta de novo"
const KEY_HINT = "ENTER fecha o caixa · ESC volta para a venda"
const CLOSED_HINT = "ENTER encerra a sessão (logout) · qualquer outra tecla volta ao login"

/** Rodapé da tela: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

export function ClosingCashScreen({ state, api, dispatch }: ClosingCashScreenProps) {
  /** Resumo do servidor; `null` enquanto a leitura não voltou (com ele à vista o campo é liberado). */
  const [summary, setSummary] = useState<CashSessionSummaryView | null>(null)
  /** Dígitos do contado, sem máscara: `1250` é o estado; `R$ 12,50` é o que se vê. */
  const [digits, setDigits] = useState("")
  /** Espelho do campo para o ENTER: o render não espera o `setState` do último dígito. */
  const digitsRef = useRef("")
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de um segundo fechamento; muda o pedido (ou dá recusa), chave nova.
   */
  const closingKey = useRef<string | null>(null)
  /** Trava da saída com o caixa fechado: o ENTER repetido não revoga a sessão duas vezes. */
  const leaving = useRef(false)
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  /** Caixa fechado: a conferência do servidor está à vista e não há mais o que operar. */
  const closed = state.closing !== null

  useGlobalKeyboard({ onKey: handleKey })

  useEffect(() => {
    let live = true

    void (async () => {
      const outcome = await api.cashSessionSummary(state.sessionId)

      // o ESC pode ter tirado a tela de cena (ou o caixa já fechou): sem tela, sem despacho
      if (!live) {
        return
      }

      if (outcome.ok) {
        setSummary(outcome.summary)
        return
      }

      dispatch({ type: "apiFailed", problem: outcome.problem })
    })()

    return () => {
      live = false
    }
  }, [api, state.sessionId, dispatch])

  /**
   * Teclado da tela: o campo de valor é desta tela (o ENTER envia, BACKSPACE/DEL apagam e a máscara
   * é de dígitos), o ESC volta para a venda com ela preservada (`cancel` do reducer) e, com o caixa
   * fechado, qualquer tecla sai para o login — o ENTER revoga a sessão antes.
   */
  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    // caixa fechado: a conferência do servidor está à vista e a única saída é o login (com ou sem logout)
    if (closed) {
      if (!leaving.current) {
        void leave(keyEventToKeyName(event) === "ENTER")
      }

      return true
    }

    // requisição em andamento: ENTER repetido não fecha duas vezes e o ESC não abandona o envio
    if (busy) {
      return true
    }

    // ESC volta para a venda com ela preservada: quem decide é o reducer (`cancel`), como no pagamento
    if (event.name === "escape") {
      dispatch({ type: "cancel" })
      return true
    }

    // resumo a caminho: o campo de valor só existe com o esperado do servidor à vista
    if (summary === null) {
      return true
    }

    // combo do sistema/formulário não é dígito do campo (mesma regra das demais telas)
    if (event.ctrl || event.meta) {
      return true
    }

    const keyName = keyEventToKeyName(event)

    if (keyName === "BACKSPACE" || keyName === "DEL") {
      writeDigits(digitsRef.current.slice(0, -1))
      return true
    }

    if (keyName === "ENTER") {
      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador (§11.3)
      if (!burstEnter()) {
        void send()
      }

      return true
    }

    // setas e F1–F12 não são do formulário e a máscara é de dígitos: nada entra no campo
    if (!isPrintable(event.sequence)) {
      return true
    }

    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = at

    // rajada do leitor (o hardware continua bipando): não vira valor contado
    if (burst) {
      return true
    }

    const typed = event.sequence.replace(/\D/g, "")
    if (typed !== "") {
      writeDigits(digitsRef.current + typed)
    }

    return true
  }

  /** O ENTER que chega colado nos caracteres é o terminador do bipe; mede e zera o instante. */
  function burstEnter(): boolean {
    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = null

    return burst
  }

  /** Escreve no campo: o estado desenha e o ref guarda o valor corrente; pedido novo, chave nova. */
  function writeDigits(next: string): void {
    digitsRef.current = next
    setDigits(next)
    setMessage(null)
    closingKey.current = null
  }

  /**
   * ENTER: manda o contado (centavos → reais) e mostra a conferência do servidor. A recusa volta ao
   * formulário com a mensagem e o valor digitado; a transitória fica com o aviso de retry e a mesma
   * chave; a bloqueante vai para a tela de erro pelo shell.
   */
  async function send(): Promise<void> {
    const valueDigits = digitsRef.current

    if (valueDigits === "") {
      setMessage({ kind: "hint", text: MISSING_VALUE })
      return
    }

    setMessage(null)
    setBusy(true)

    closingKey.current ??= crypto.randomUUID()
    const outcome = await api.closeCashSession(
      state.register.id,
      { countedAmount: centsToAmount(digitsToCents(valueDigits)) },
      closingKey.current,
    )

    setBusy(false)

    if (outcome.ok) {
      closingKey.current = null
      dispatch({ type: "cashCloseSucceeded", closing: outcome.closing })
      return
    }

    if (outcome.kind === "rejected") {
      // recusa não fechou nada: o digitado fica na tela com o que fazer na mensagem
      closingKey.current = null
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
      setMessage({ kind: "retry", text: RETRY_NOTICE })
      return
    }

    dispatch({ type: "apiFailed", problem: outcome.problem })
  }

  /** Saída do caixa fechado: o ENTER revoga a sessão (logout) e as demais teclas só voltam ao login. */
  async function leave(withLogout: boolean): Promise<void> {
    leaving.current = true

    if (withLogout) {
      await api.logout()
    }

    dispatch({ type: "cashClosed" })
  }

  // caixa fechado: a conferência do servidor fica no lugar do formulário até o operador sair
  if (state.closing !== null) {
    const closing = state.closing

    return (
      <box flexDirection="column" width="100%" height="100%">
        <text fg={theme.header} attributes={TextAttributes.BOLD} wrapMode="none">
          Fechamento de caixa (F10)
        </text>
        <text wrapMode="none">{`Operador: ${state.operator.name} · Caixa: ${state.register.name}`}</text>
        <text> </text>
        <text fg={theme.success} attributes={TextAttributes.BOLD} wrapMode="none">
          caixa fechado
        </text>
        <text wrapMode="none">
          {`Esperado: ${formatAmount(closing.expectedAmount)} · Contado: ${formatAmount(closing.countedAmount)}`}
        </text>
        {/* a diferença é a do servidor (BR-12): a tela só compara com zero para dizer sobra/falta */}
        <text
          fg={closing.differenceAmount === 0 ? theme.success : theme.danger}
          attributes={TextAttributes.BOLD}
          wrapMode="none"
        >
          {`Diferença (servidor): ${formatAmount(closing.differenceAmount)} — ${differenceNote(closing.differenceAmount)}`}
        </text>
        <text> </text>
        <text fg={theme.muted} wrapMode="none">
          {CLOSED_HINT}
        </text>
      </box>
    )
  }

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text fg={theme.header} attributes={TextAttributes.BOLD} wrapMode="none">
        Fechamento de caixa (F10)
      </text>
      <text wrapMode="none">{`Operador: ${state.operator.name} · Caixa: ${state.register.name}`}</text>
      <text> </text>
      {summary === null ? (
        <text fg={theme.muted} wrapMode="none">
          {LOADING}
        </text>
      ) : (
        <>
          <text wrapMode="none">{`Aberto: ${formatAmount(summary.openingAmount)}`}</text>
          <text attributes={TextAttributes.BOLD} wrapMode="none">
            {`Esperado: ${formatAmount(summary.expectedAmount)}`}
          </text>
          <text wrapMode="none">Vendas por forma de pagamento:</text>
          {METHODS.map((method) => (
            <text key={method} wrapMode="none">
              {` ${METHOD_LABELS[method]}: ${formatAmount(summary.paymentsByMethod[method] ?? 0)}`}
            </text>
          ))}
          <text wrapMode="none">
            {`Sangrias: ${formatAmount(summary.totalsByType.WITHDRAWAL ?? 0)} · Suprimentos: ${formatAmount(summary.totalsByType.SUPPLY ?? 0)}`}
          </text>
          <text> </text>
          <text fg={digits === "" ? theme.muted : theme.text} wrapMode="none">
            {`Valor contado: ${formatBRL(digitsToCents(digits))}`}
          </text>
          {/* linha reservada: dica, recusa ou retry não deslocam o resto do quadro */}
          {busy ? (
            <text fg={theme.muted} wrapMode="none">
              {SENDING}
            </text>
          ) : message !== null ? (
            <MessageRow message={message} />
          ) : digits === "" ? (
            <text fg={theme.muted} wrapMode="none">
              {VALUE_HINT}
            </text>
          ) : null}
        </>
      )}
      <text> </text>
      <text fg={theme.muted} wrapMode="none">
        {KEY_HINT}
      </text>
    </box>
  )
}

/** Rodapé: amarelo na falha transitória (retry) e vermelho na dica e na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  return (
    <text fg={message.kind === "retry" ? theme.warning : theme.danger} wrapMode="none">
      {message.text}
    </text>
  )
}

/**
 * Leitura da diferença **do servidor** (BR-12): só compara com zero para dizer sobra/falta — somar,
 * subtrair ou arredondar continua sendo do servidor.
 */
function differenceNote(differenceAmount: number): string {
  if (differenceAmount === 0) {
    return "o caixa confere"
  }

  return differenceAmount > 0 ? "sobra dinheiro na gaveta" : "falta dinheiro na gaveta"
}
