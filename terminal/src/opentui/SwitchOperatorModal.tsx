/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { TerminalApi } from "../api/terminalApi"
import type { ApiProblem } from "../core/state"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Troca de operador (F12, passo 1118 portado no 1126c): confirmação bloqueante sobre a venda, no
 * `ModalFrame` como os demais modais da fase — o corpo da venda sai de cena e o leitor dela fica
 * desligado (§11.3).
 *
 * A tela tem duas caras, decididas pela venda aberta: **sem itens** é uma confirmação leve — o F12
 * jamais encerra a sessão por engano e o ENTER troca direto; **com itens** bloqueia a troca
 * silenciosa e diz o que o ENTER faz ("há venda aberta com N itens — a venda será cancelada"): o
 * ENTER cancela a venda no servidor e só então encerra a sessão.
 *
 * O motivo do cancelamento é **fixo** — `troca de operador` (BR-04 exige um motivo, e o próprio F12
 * já o explica): cancelar é a única saída da venda para o próximo operador entrar no caixa, e
 * digitar um motivo só adicionaria um passo a uma confirmação que o operador já leu. A
 * `Idempotency-Key` é desta tentativa: o retry reusa a mesma e uma resposta perdida vira replay no
 * servidor, sem um segundo cancelamento. O caminho do cancelamento é o que o F12 da Ink fazia — o
 * 1126d traz o F4 (modal do cancelamento com motivo) e poderá compartilhar este trecho.
 *
 * A sessão de **login** termina (o `logout` revoga e esquece o token mesmo se a revogação falhar,
 * como no 1107), mas a sessão de **caixa continua aberta**: o próximo operador entra pelo login e o
 * shell deixa o caixa atual preferido (`preferredRegisterId`), então ele só confirma. Recusa do
 * cancelamento (403 sem `sale.cancel`, 400 do motivo) fica **aqui**, com a venda intacta — nada de
 * troca silenciosa; rede/5xx avisa e o mesmo ENTER refaz; a falha bloqueante (404/409/contrato) vai
 * para a tela de erro pela mão da tela de venda.
 *
 * O teclado é **deste** modal (o padrão do `RemoveItemConfirmModal`): o ENTER que chega colado numa
 * rajada do leitor é o terminador do bipe, não o "sim" do operador — a troca nunca começa por um
 * bipe. ESC volta para a venda sem chamar nada.
 */

export type SwitchOperatorModalProps = {
  /** Venda aberta a cancelar quando ela tem itens; `null` quando não há venda criada (1125b). */
  saleId: string | null
  /** Itens da venda aberta: com item o bloqueio é explícito; sem item a troca é direta. */
  itemCount: number
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Sessão encerrada: a tela de venda avisa o shell, que volta ao login com o caixa atual preferido. */
  onSwitched: () => void
  /** ESC: volta para a venda sem encerrar sessão nenhuma. */
  onCancel: () => void
  /** Falha bloqueante do cancelamento: a tela de venda fecha o modal e leva o problema ao reducer. */
  onFailed: (problem: ApiProblem) => void
}

/** Motivo fixo do cancelamento (BR-04): o F12 já diz por que a venda foi descartada. */
const SWITCH_REASON = "troca de operador"
const SWITCHING = "encerrando a sessão…"
const RETRY_NOTICE = "falha ao cancelar a venda — ENTER tenta de novo"
const DIRECT_HINTS = "ENTER troca de operador · ESC volta"
const BLOCKED_HINTS = "ENTER cancela a venda e troca de operador · ESC volta"

/** Corpo do modal: a troca direta (sem venda) e o bloqueio explícito (com itens). */
const SESSION_ENDED = "a sessão de login termina; o caixa continua aberto"

/** Rodapé do modal: recusa do servidor ou falha transitória do cancelamento. */
type Message = { kind: "rejected" | "retry"; text: string }

export function SwitchOperatorModal({
  saleId,
  itemCount,
  api,
  onSwitched,
  onCancel,
  onFailed,
}: SwitchOperatorModalProps) {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de um segundo cancelamento.
   */
  const cancelKey = useRef<string | null>(null)
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)
  const withItems = itemCount > 0 && saleId !== null

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

    // caractere imprimível é rajada do leitor (ou digitação perdida): engolido e cronometrado
    if (isPrintable(event.sequence)) {
      lastCharAt.current = performance.now()
      return true
    }

    if (event.name === "return") {
      const at = performance.now()
      const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
      lastCharAt.current = null

      if (!burst && !busy) {
        void switchOperator()
      }

      return true
    }

    // setas, TAB e F1–F12 não são desta confirmação: engolidos, não vazam para a venda
    return true
  }

  /**
   * ENTER da confirmação: com venda aberta cancela primeiro (é o `cancelSale` que a libera) e só
   * depois encerra a sessão; sem venda, a troca é só o fim da sessão de login. A falha do `logout`
   * não prende a troca: o contrato da camada de API não rejeita e esquece o token local de qualquer
   * forma (1107) — a sessão órfã expira no idle timeout do servidor.
   */
  async function switchOperator(): Promise<void> {
    setMessage(null)
    setBusy(true)

    if (withItems && saleId !== null) {
      cancelKey.current ??= crypto.randomUUID()
      const outcome = await api.cancelSale(saleId, SWITCH_REASON, cancelKey.current)

      if (!outcome.ok) {
        setBusy(false)

        if (outcome.kind === "rejected") {
          // recusa não cancelou nada: a venda continua e a troca não acontece
          cancelKey.current = null
          setMessage({ kind: "rejected", text: outcome.message })
          return
        }

        if (outcome.kind === "retryable") {
          // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
          setMessage({ kind: "retry", text: RETRY_NOTICE })
          return
        }

        onFailed(outcome.problem)
        return
      }

      cancelKey.current = null
    }

    await api.logout()
    onSwitched()
  }

  return (
    <ModalFrame title="Trocar operador (F12)" hints={withItems ? BLOCKED_HINTS : DIRECT_HINTS}>
      <text wrapMode="none">{SESSION_ENDED}</text>
      {withItems ? (
        <text fg={theme.warning} attributes={TextAttributes.BOLD} wrapMode="none">
          {`há venda aberta com ${itemCount} ${itemCount === 1 ? "item" : "itens"} — a venda será cancelada`}
        </text>
      ) : null}
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {SWITCHING}
        </text>
      ) : message === null ? null : (
        <MessageRow message={message} />
      )}
    </ModalFrame>
  )
}

/** Rodapé: amarelo na falha transitória (retry) e vermelho na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  const color = message.kind === "retry" ? theme.warning : theme.danger

  return (
    <text fg={color} wrapMode="none">
      {message.text}
    </text>
  )
}
