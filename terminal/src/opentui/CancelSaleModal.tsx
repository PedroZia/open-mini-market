/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { TerminalApi } from "../api/terminalApi"
import type { ApiProblem } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Cancelamento da venda (F4, passo 1115 portado no 1126d): modal bloqueante sobre a venda, no
 * `ModalFrame` como os demais da fase — o corpo da venda sai de cena e o leitor dela fica
 * desligado (§11.3).
 *
 * O motivo é **obrigatório** (BR-04, `SaleCancelRequest`): o ENTER do formulário sem motivo não
 * confirma nada, só mostra a dica; com o motivo, o ENTER passa para a **confirmação** ("cancelar a
 * venda em andamento?") e é o ENTER dela que chama o `POST /sales/{id}/cancel`. A venda `OPEN`
 * bloqueia o fechamento do caixa (409 `SESSION_HAS_OPEN_SALES`, 1127), então descartá-la é o que
 * permite encerrar o turno — e a venda cancelada é imutável no servidor (§4.4).
 *
 * A `Idempotency-Key` é desta tentativa (o mesmo trecho do `SwitchOperatorModal`, 1126c): o retry
 * reusa a mesma e uma resposta perdida vira replay no servidor, sem um segundo cancelamento. Mudar
 * o motivo (ou uma recusa) zera a chave — o pedido é outro. Quem recusa é o servidor: 403 sem
 * `sale.cancel` (BR-04: quem opera não cancela) e 400 do motivo voltam como mensagem **no próprio
 * modal**, que volta ao formulário com o motivo digitado; rede/5xx mostram o aviso de retry na
 * confirmação e o mesmo ENTER refaz; a falha bloqueante (404/409/contrato) é da tela de venda, que
 * fecha o modal e leva o problema para a tela de erro (§11.4) — a venda fica intacta.
 *
 * O teclado é **deste** modal (o padrão do `RemoveItemConfirmModal`): o `prependListener` do hook
 * global põe o listener mais novo na frente, então a tecla chega aqui antes do listener da venda —
 * que, com o modal à vista, devolve `false` (e o leitor dela está desligado, §11.3). A **rajada** do
 * leitor (o hardware não sabe que o scanner está desligado) é descartada pelo mesmo critério do
 * `core/scanner`: caracteres em intervalo < 50 ms são engolidos e o ENTER que vem logo depois é o
 * terminador do bipe, não o "sim" do operador. ESC fecha **sem** chamar nada.
 */

export type CancelSaleModalProps = {
  /** Venda aberta que será cancelada: o `SaleModal` guarda o id na abertura (alvo fixo do POST). */
  saleId: string
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Cancelada no servidor: a tela de venda volta ao estado vazio e esquece o cliente anotado. */
  onCancelled: () => void
  /** ESC: volta para a venda sem chamar a API. */
  onCancel: () => void
  /** Falha bloqueante (404/409/contrato): a tela de venda fecha o modal e leva o problema ao reducer. */
  onFailed: (problem: ApiProblem) => void
}

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

const MISSING_REASON = "informe o motivo do cancelamento"
const CANCELING = "cancelando…"
const RETRY_NOTICE = "falha ao cancelar a venda — ENTER tenta de novo"
const KEY_HINT = "ENTER confirma · ESC volta para a venda"
const CONFIRMING_HINT = "cancelar a venda em andamento? ENTER confirma · ESC volta"

export function CancelSaleModal({
  saleId,
  api,
  onCancelled,
  onCancel,
  onFailed,
}: CancelSaleModalProps) {
  const [reason, setReason] = useState("")
  /**
   * Espelho do motivo para o ENTER: o handler do hook global é o do último render, e os caracteres
   * de uma digitação rápida chegam em eventos seguidos — o ref guarda o que ainda não re-renderizou.
   */
  const reasonRef = useRef("")
  /** Motivo validado esperando o ENTER da confirmação; nada foi à API ainda. */
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de um segundo cancelamento; muda o pedido (ou dá recusa), chave nova.
   */
  const cancelKey = useRef<string | null>(null)
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      // com o cancelamento em voo o modal espera a resposta antes de sair
      if (!busy) {
        onCancel()
      }

      return true
    }

    // combo do sistema/formulário não é texto do motivo nem atalho (§11.3)
    if (event.ctrl || event.meta) {
      return true
    }

    // requisição em andamento: ENTER repetido não cancela duas vezes
    if (busy) {
      return true
    }

    const keyName = keyEventToKeyName(event)

    // confirmação: os campos estão travados e a única tecla é o ENTER que chama a API
    if (confirming) {
      if (keyName === "ENTER" && !burstEnter()) {
        void send()
      }

      return true
    }

    if (keyName === "BACKSPACE" || keyName === "DEL") {
      writeReason(reasonRef.current.slice(0, -1))
      return true
    }

    if (keyName === "ENTER") {
      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador (§11.3)
      if (!burstEnter()) {
        askConfirmation(reasonRef.current)
      }

      return true
    }

    // setas, TAB e F1–F12 não são do formulário: engolidos, não vazam para a venda
    if (!isPrintable(event.sequence)) {
      return true
    }

    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = at

    // rajada do leitor (o scanner da venda está desligado, mas o hardware não): não vira motivo
    if (burst) {
      return true
    }

    writeReason(reasonRef.current + event.sequence)
    return true
  }

  /** O ENTER que chega colado nos caracteres é o terminador do bipe; mede e zera o instante. */
  function burstEnter(): boolean {
    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = null

    return burst
  }

  /**
   * Escreve no motivo: o estado desenha e o ref guarda o valor corrente. Mudar o pedido zera a
   * chave do retry anterior — a tentativa nova nasce com uma chave nova.
   */
  function writeReason(next: string): void {
    reasonRef.current = next
    setReason(next)
    setMessage(null)
    cancelKey.current = null
  }

  /** ENTER do formulário: sem motivo não há confirmação — a dica fica no modal. */
  function askConfirmation(reasonText: string): void {
    if (reasonText.trim() === "") {
      setMessage({ kind: "hint", text: MISSING_REASON })
      return
    }

    setMessage(null)
    setConfirming(true)
  }

  /**
   * ENTER da confirmação: manda o motivo como o operador o digitou e o servidor cancela a venda. A
   * recusa volta ao formulário com a mensagem; a transitória fica na confirmação com o aviso de
   * retry e a mesma chave; a bloqueante é da tela de venda.
   */
  async function send(): Promise<void> {
    setMessage(null)
    setBusy(true)

    cancelKey.current ??= crypto.randomUUID()
    const outcome = await api.cancelSale(saleId, reasonRef.current, cancelKey.current)

    setBusy(false)

    if (outcome.ok) {
      cancelKey.current = null
      onCancelled() // a tela zera a venda, esquece o cliente e tira o modal de cena
      return
    }

    if (outcome.kind === "rejected") {
      // recusa não cancelou nada no servidor: volta ao formulário com o motivo digitado
      cancelKey.current = null
      setConfirming(false)
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
      setMessage({ kind: "retry", text: RETRY_NOTICE })
      return
    }

    onFailed(outcome.problem)
  }

  return (
    <ModalFrame title="Cancelar a venda (F4)" hints={KEY_HINT}>
      <text wrapMode="none">{`${confirming ? " " : "›"} Motivo: ${reason}`}</text>
      {confirming ? (
        <text fg={theme.warning} attributes={TextAttributes.BOLD} wrapMode="none">
          {CONFIRMING_HINT}
        </text>
      ) : (
        <text fg={theme.muted} wrapMode="none">
          a venda em andamento será descartada — o cliente é esquecido
        </text>
      )}
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {CANCELING}
        </text>
      ) : message === null ? null : (
        <MessageRow message={message} />
      )}
    </ModalFrame>
  )
}

/** Rodapé: amarelo na falha transitória (retry) e vermelho na dica e na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  const color = message.kind === "retry" ? theme.warning : theme.danger

  return (
    <text fg={color} wrapMode="none">
      {message.text}
    </text>
  )
}
