/** @jsxImportSource @opentui/react */
import type { KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { CashMovementIntent, CashMovementKind } from "../api/terminalApi"
import { centsToAmount, digitsToCents, formatBRL } from "../core/money"
import { keyEventToKeyName } from "./adapters/keys"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Sangria (F7) e suprimento (F8), passo 1114 portado no 1126d: modal bloqueante sobre a venda, no
 * `ModalFrame` como os demais da fase — o corpo da venda sai de cena e o leitor dela fica
 * desligado (§11.3).
 *
 * Diferente do desconto e do cliente, a gaveta **não** depende da venda: sangrar e suprir são
 * operações do caixa aberto (BR-10), então o modal abre mesmo antes do primeiro bipe.
 *
 * O valor é mascarado em centavos, como a abertura de caixa (1124b) e o desconto (1126b): `1250`
 * vira `R$ 12,50` na tela e vai como `12.5` no corpo; o motivo é texto livre e obrigatório
 * (BR-10) — vazio (ou valor vazio) não chama a API e a dica fica no próprio formulário. O TAB
 * percorre os dois campos. A TUI não calcula dinheiro nenhum (BR-12): manda valor e motivo
 * (`POST .../withdrawals` ou `.../supplies`, passos 609/610) e quem grava o movimento, o esperado
 * antes e o depois é o servidor — a confirmação que o operador vê no rodapé é o valor dele.
 *
 * O ENTER do formulário chama a API pela tela de venda (`onSend`), que passa pela mesma trava de
 * mutação da venda (uma por vez, com a fila de bipes drenada no `finally`). Quem recusa é o
 * servidor: 403 sem `cash.withdrawal`/`cash.supply` (BR-10), 400 do valor/motivo e 404/409 da
 * sessão do caixa que mudou por fora voltam como mensagem **no próprio modal**, que volta ao
 * formulário com o que foi digitado; rede/5xx mostram o aviso de retry e o ENTER refaz com a
 * **mesma** `Idempotency-Key` (retry não sangra duas vezes, §8); a falha bloqueante (contrato) é da
 * tela de venda, que fecha o modal e leva o problema para a tela de erro (§11.4). ESC cancela sem
 * chamar a API.
 *
 * O teclado é **deste** modal (o padrão do `DiscountModal`/1126b): o `prependListener` do hook
 * global põe o listener mais novo na frente, então a tecla chega aqui antes do listener da venda —
 * que, com o modal à vista, devolve `false` (e o leitor dela está desligado, §11.3). Como aqui há
 * campos de texto, a **rajada** do leitor é descartada pelo mesmo critério do `core/scanner`
 * (caracteres em intervalo < 50 ms são engolidos, e o ENTER que vem logo depois é o terminador do
 * bipe, não o "sim" do operador).
 */

export type CashMovementModalProps = {
  /** Qual movimento o operador pediu (F7/F8): muda os rótulos e a rota que a tela de venda chama. */
  kind: CashMovementKind
  /**
   * ENTER do formulário: a tela de venda chama a API pela trava de mutação (uma por vez) e devolve
   * o desfecho — sucesso (o modal sai de cena com a confirmação no rodapé), recusa do servidor (o
   * modal fica com a mensagem) ou falha transitória (o ENTER refaz com a mesma chave).
   */
  onSend: (intent: CashMovementIntent, idempotencyKey: string) => Promise<CashMovementApplyResult>
  /** ESC: cancela sem chamar a API — o valor e o motivo não saem do modal. */
  onCancel: () => void
}

/** Desfecho do movimento para o formulário: registrado, recusa do servidor, retry manual ou bloqueante. */
export type CashMovementApplyResult =
  | { kind: "applied" }
  | { kind: "rejected"; message: string }
  | { kind: "retryable" }
  | { kind: "failed" }

/** Campos do formulário, no ciclo do TAB. */
type Field = "amount" | "reason"

/** Textos do movimento: título, frase com artigo, rótulo e aviso de retry, por F7/F8. */
const TEXTS: Readonly<
  Record<CashMovementKind, { title: string; label: string; article: "da" | "do"; retry: string }>
> = {
  withdrawal: {
    title: "Sangria (F7)",
    label: "sangria",
    article: "da",
    retry: "falha ao registrar a sangria — ENTER tenta de novo",
  },
  supply: {
    title: "Suprimento (F8)",
    label: "suprimento",
    article: "do",
    retry: "falha ao registrar o suprimento — ENTER tenta de novo",
  },
}

/** Máscara do valor, no mesmo espírito do campo da abertura de caixa (1124b) e do desconto (1126b). */
const VALUE_HINT = "digite o valor em centavos: 1250 vira R$ 12,50"
const KEY_HINT = "TAB troca o campo · ENTER registra · ESC cancela"
const SENDING = "enviando…"

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

export function CashMovementModal({ kind, onSend, onCancel }: CashMovementModalProps) {
  const texts = TEXTS[kind]
  /** Dígitos do valor, sem máscara: `1250` é o estado; `R$ 12,50` é o que se vê. */
  const [digits, setDigits] = useState("")
  const [reason, setReason] = useState("")
  /** Espelhos do valor e do motivo para o ENTER (ver `writeDigits`/`writeReason`). */
  const digitsRef = useRef("")
  const reasonRef = useRef("")
  const [field, setField] = useState<Field>("amount")
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /**
   * Chave da tentativa em curso: o retry do ENTER reusa a mesma, então uma resposta perdida vira
   * replay no servidor em vez de uma segunda sangria; muda o pedido (ou dá recusa), chave nova.
   */
  const movementKey = useRef<string | null>(null)
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      // com o movimento em voo o modal espera a resposta antes de sair
      if (!busy) {
        onCancel()
      }

      return true
    }

    // combo do sistema/formulário não é texto do campo nem atalho (§11.3)
    if (event.ctrl || event.meta) {
      return true
    }

    // requisição em andamento: ENTER repetido não registra duas vezes
    if (busy) {
      return true
    }

    const keyName = keyEventToKeyName(event)

    if (keyName === "TAB") {
      setField((current) => (current === "amount" ? "reason" : "amount"))
      return true
    }

    if (keyName === "BACKSPACE" || keyName === "DEL") {
      if (field === "amount") {
        writeDigits(digitsRef.current.slice(0, -1))
      } else {
        writeReason(reasonRef.current.slice(0, -1))
      }

      return true
    }

    if (keyName === "ENTER") {
      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador (§11.3)
      if (!burstEnter()) {
        void submit()
      }

      return true
    }

    // setas e F1–F12 não são do formulário: engolidos, não vazam para a venda
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

    if (field === "amount") {
      // a máscara é de dígitos: letra, sinal ou espaço não entram no valor
      const typed = event.sequence.replace(/\D/g, "")
      if (typed !== "") {
        writeDigits(digitsRef.current + typed)
      }
    } else {
      writeReason(reasonRef.current + event.sequence)
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

  /**
   * Escreve no campo: o estado desenha e o ref guarda o valor corrente. Mudar o pedido zera a
   * chave do retry anterior — a tentativa nova nasce com uma chave nova.
   */
  function writeDigits(next: string): void {
    digitsRef.current = next
    setDigits(next)
    clearAttempt()
  }

  function writeReason(next: string): void {
    reasonRef.current = next
    setReason(next)
    clearAttempt()
  }

  function clearAttempt(): void {
    setMessage(null)
    movementKey.current = null
  }

  /**
   * ENTER do formulário: sem valor ou sem motivo não há movimento — a dica fica no modal e nada vai
   * à API. Com os dois, manda valor (centavos → reais) e motivo como o operador os digitou.
   */
  async function submit(): Promise<void> {
    const valueDigits = digitsRef.current
    const reasonText = reasonRef.current

    if (valueDigits === "") {
      setMessage({ kind: "hint", text: `informe o valor ${texts.article} ${texts.label}` })
      return
    }

    if (reasonText.trim() === "") {
      setMessage({ kind: "hint", text: `informe o motivo ${texts.article} ${texts.label}` })
      return
    }

    setMessage(null)
    setBusy(true)

    const idempotencyKey = (movementKey.current ??= crypto.randomUUID())
    const outcome = await onSend(
      { amount: centsToAmount(digitsToCents(valueDigits)), reason: reasonText },
      idempotencyKey,
    )

    setBusy(false)

    if (outcome.kind === "rejected") {
      // recusa não é gravada no servidor: volta ao formulário com a mensagem e o que foi digitado
      movementKey.current = null
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      // a tentativa segue com a mesma chave, e o mesmo ENTER refaz
      setMessage({ kind: "retry", text: texts.retry })
    }
    // "applied" (a tela fecha o modal com a confirmação no rodapé) e "failed" (a tela já levou o
    // problema ao reducer) não deixam nada à vista: o modal sai de cena pelas mãos da tela de venda
  }

  const masked = formatBRL(digitsToCents(digits))

  return (
    <ModalFrame title={texts.title} hints={KEY_HINT}>
      <text fg={field === "amount" && digits === "" ? theme.muted : theme.text} wrapMode="none">
        {`${field === "amount" ? "›" : " "} Valor: ${masked}`}
      </text>
      {digits === "" ? (
        <text fg={theme.muted} wrapMode="none">
          {VALUE_HINT}
        </text>
      ) : null}
      <text wrapMode="none">{`${field === "reason" ? "›" : " "} Motivo: ${reason}`}</text>
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {SENDING}
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
