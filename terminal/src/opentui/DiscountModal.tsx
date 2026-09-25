/** @jsxImportSource @opentui/react */
import type { KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { SaleDiscountIntent } from "../api/terminalApi"
import { centsToAmount, digitsToCents, formatBRL } from "../core/money"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Desconto na venda (F5, passo 1111 portado no 1126b): modal bloqueante sobre a venda com o valor e
 * o motivo (BR-04) no `ModalFrame`. A TUI **não calcula desconto nenhum** (BR-12): manda o valor que
 * o operador digitou e o motivo ao servidor (`PUT /sales/{id}/discount`, passos 810/811b, pela
 * `runMutation` da tela de venda) e fica com a venda inteira que ele devolveu, com subtotal,
 * desconto e total recalculados.
 *
 * O valor é mascarado em centavos, como a abertura de caixa: os dígitos viram reais (`1250` →
 * `R$ 12,50`) e vão como `12.5` no corpo; o motivo é texto livre e obrigatório — vazio (ou valor
 * vazio) não chama a API e a dica fica no próprio formulário. TAB troca o campo (valor → motivo).
 *
 * Quem recusa é o servidor: limite da loja (422), permissão `sale.discount.apply` (403) e
 * forma/motivo (400) voltam como mensagem **no próprio modal**, sem fechá-lo, para o operador
 * corrigir sem perder o que digitou. Rede/5xx mostram o aviso de retry e o ENTER refaz; a falha
 * bloqueante é da tela de venda, que fecha o modal e leva o problema para a tela de erro (§11.4).
 * ESC cancela sem chamar a API.
 *
 * O teclado é **deste** modal (o padrão do `LoginScreen`/1126a): o `prependListener` do hook global
 * põe o listener mais novo na frente, então a tecla chega aqui antes do listener da venda — que,
 * com o modal à vista, devolve `false` (e o leitor dela está desligado, §11.3). Como aqui há
 * campos de texto, o que o leitor mandar cairia neles — e o terminador colado, no ENTER que aplica:
 * por isso a **rajada** é descartada pelo mesmo critério do `core/scanner` (caracteres em intervalo
 * < 50 ms são engolidos, e o ENTER que vem logo depois é o terminador do bipe, não o "sim" do
 * operador). Digitação humana continua normal: os dígitos entram no campo e o ENTER dela aplica.
 */

export type DiscountModalProps = {
  /**
   * ENTER do formulário: a tela de venda aplica pela `runMutation` (uma mutação por vez) e devolve
   * o desfecho — sucesso (o modal sai de cena com a venda recalculada no estado), recusa do
   * servidor (o modal fica à vista com a mensagem) ou falha transitória (o ENTER refaz).
   */
  onApply: (intent: SaleDiscountIntent) => Promise<DiscountApplyResult>
  /** ESC: cancela sem chamar a API — o valor e o motivo não saem do modal. */
  onCancel: () => void
}

/** Desfecho da aplicação para o formulário: aplicado, recusa do servidor, retry manual ou bloqueante. */
export type DiscountApplyResult =
  | { kind: "applied" }
  | { kind: "rejected"; message: string }
  | { kind: "retryable" }
  | { kind: "failed" }

/** Campos do formulário, na ordem em que o TAB os percorre. */
type Field = "value" | "reason"

/** Dica do valor: a mesma máscara de centavos da abertura de caixa. */
const VALUE_HINT = "digite o valor em centavos: 1250 vira R$ 12,50"

/** Validação de forma, só do formulário: o limite da loja e o resto são do servidor (BR-12). */
const MISSING_VALUE = "informe o valor do desconto"
const MISSING_REASON = "informe o motivo do desconto"

const APPLYING = "aplicando…"
const RETRY_NOTICE = "falha ao aplicar o desconto — ENTER tenta de novo"
const KEY_HINT = "TAB troca o campo · ENTER aplica · ESC cancela"

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

export function DiscountModal({ onApply, onCancel }: DiscountModalProps) {
  /** Dígitos do campo do valor, sem máscara: `1250` é o estado; `R$ 12,50` é o que se vê. */
  const [digits, setDigits] = useState("")
  const [reason, setReason] = useState("")
  const [field, setField] = useState<Field>("value")
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      // uma mutação por vez: com a aplicação em voo o modal espera a resposta antes de sair
      if (!busy) {
        onCancel()
      }

      return true
    }

    // combo do sistema não é texto do campo (mesma regra do `resolveKey` do Ink)
    if (event.ctrl || event.meta) {
      return true
    }

    if (busy) {
      return true // aplicação em voo: ENTER repetido não aplica duas vezes
    }

    if (event.name === "tab") {
      setField((current) => (current === "value" ? "reason" : "value"))
      return true
    }

    if (event.name === "backspace" || event.name === "delete") {
      setMessage(null)
      if (field === "value") {
        setDigits((current) => current.slice(0, -1))
      } else {
        setReason((current) => current.slice(0, -1))
      }

      return true
    }

    if (event.name === "return") {
      const at = performance.now()
      const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
      lastCharAt.current = null

      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador (§11.3)
      if (!burst) {
        void apply(digits, reason)
      }

      return true
    }

    // setas, F1–F12 e demais controles não são texto do campo: engolidos, não vazam para a venda
    if (!isPrintable(event.sequence)) {
      return true
    }

    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = at

    // rajada do leitor (o scanner da venda está desligado, mas o hardware não): não vira valor/motivo
    if (burst) {
      return true
    }

    setMessage(null)

    if (field === "value") {
      // a máscara é de dígitos: letra, sinal ou espaço não entram no valor
      const typed = event.sequence.replace(/\D/g, "")
      if (typed !== "") {
        setDigits((current) => current + typed)
      }
    } else {
      setReason((current) => current + event.sequence)
    }

    return true
  }

  /**
   * Aplica o desconto com o que o operador digitou: o valor vai em reais (centavos → reais na
   * máscara) e o motivo como texto; a venda recalculada é a que a tela de venda devolveu (BR-12).
   */
  async function apply(valueDigits: string, reasonText: string): Promise<void> {
    if (valueDigits === "") {
      setMessage({ kind: "hint", text: MISSING_VALUE })
      return
    }

    if (reasonText.trim() === "") {
      setMessage({ kind: "hint", text: MISSING_REASON })
      return
    }

    setMessage(null)
    setBusy(true)

    const outcome = await onApply({
      type: "VALUE",
      value: centsToAmount(digitsToCents(valueDigits)),
      reason: reasonText,
    })

    setBusy(false)

    if (outcome.kind === "rejected") {
      // 400/403/422 ficam aqui: o operador lê a recusa do servidor e corrige sem perder o que digitou
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      setMessage({ kind: "retry", text: RETRY_NOTICE })
    }
    // "applied" (a tela fecha o modal) e "failed" (a tela já levou o problema ao reducer) não
    // deixam nada à vista: o modal sai de cena pelas mãos da tela de venda
  }

  const masked = formatBRL(digitsToCents(digits))

  return (
    <ModalFrame title="Desconto na venda (F5)" hints={KEY_HINT}>
      <text wrapMode="none">{`${field === "value" ? "›" : " "} Valor: ${masked}`}</text>
      {digits === "" ? (
        <text fg={theme.muted} wrapMode="none">
          {VALUE_HINT}
        </text>
      ) : null}
      <text wrapMode="none">{`${field === "reason" ? "›" : " "} Motivo: ${reason}`}</text>
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {APPLYING}
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
