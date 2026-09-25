/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useRef, useState, type Dispatch } from "react"

import type { SalePaymentIntent, TerminalApi } from "../api/terminalApi"
import { resolveShortcut } from "../core/keys"
import { centsToAmount, digitsToCents, formatAmount, formatBRL } from "../core/money"
import { problemPolicy } from "../core/problems"
import type { Action } from "../core/reducer"
import type { PaymentMethod, PaymentView, PayingState } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Pagamento (F9, passo 1113) portado para a UI nova (1127a): o corpo da venda sai de cena e esta tela
 * toma o lugar — é o que garante que a rajada do leitor não vire item com o pagamento aberto, como
 * nos modais do 1126. A TUI não calcula nada (BR-05, BR-12): o operador informa a forma e o valor, o
 * recebido no dinheiro, e o servidor devolve a venda inteira com `paidAmount`, `payments` e o
 * `changeAmount` que esta tela destaca — troco é do servidor.
 *
 * O fluxo é de repetição: ENTER registra o pagamento e a tela **continua no pagamento**, com a lista
 * dos registrados e o pago/total atualizados, para o próximo da venda (múltiplos pagamentos). O F9
 * conclui: `POST /sales/{id}/complete` devolve o resumo da tela de sucesso, que o shell mostra no
 * lugar da venda enquanto houver `receipt` (1127a). ESC é o `cancel` do reducer: volta à venda
 * intacta; é consumido aqui de propósito, senão o `installExitKey` derrubaria o PDV.
 *
 * Erros de dinheiro ficam aqui com retry: 400/403/422 do servidor viram mensagem clara (insuficiente,
 * acima do restante, recebido inválido, sem permissão) e nada avança; rede/5xx mostram o aviso e o
 * mesmo ENTER/F9 refaz — a sessão caída e o 409 de idempotência/concorrência seguem para o shell
 * (1117), que volta ao login com a venda preservada ou relê o estado do servidor.
 *
 * A `Idempotency-Key` é da tela porque o retry é dela (§8): a chave do pagamento em curso só troca
 * quando o operador muda o pedido (valor, recebido **ou** forma) e a da conclusão é uma por tentativa
 * de fechar a venda — repetir o F9 depois de uma falha transitória manda a mesma chave e o servidor
 * devolve o replay, sem um segundo pagamento nem uma segunda baixa de estoque. O servidor só grava
 * resposta < 400, então a recusa libera a chave: a próxima tentativa é outra operação.
 *
 * O leitor fica **desligado** (`barcodeEnabled: false`, §11.3) e a rajada do hardware — que continua
 * chegando — é descartada pelo mesmo limiar do `core/scanner` (caracteres em intervalo < 50 ms), com
 * o ENTER colado neles valendo como terminador do bipe: um código bipado por engano não vira valor
 * nem registra pagamento.
 */

export type PaymentScreenProps = {
  /** Estado do reducer: operador, caixa e a venda como o servidor devolveu (1103). */
  state: PayingState
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Despacho do shell; toda transição nasce no reducer (ESC, pagamento e conclusão). */
  dispatch: Dispatch<Action>
  /** Venda concluída: o shell esquece a anotação local do cliente da venda que fechou (1127a). */
  onCompleted: () => void
}

/** As cinco formas do contrato, na ordem em que a linha do seletor as mostra. */
const METHODS: readonly PaymentMethod[] = ["CASH", "PIX", "DEBIT", "CREDIT", "VOUCHER"]

/** Rótulos pt-BR das formas; o valor que vai no corpo continua sendo o do contrato. */
const METHOD_LABELS: Readonly<Record<PaymentMethod, string>> = {
  CASH: "DINHEIRO",
  PIX: "PIX",
  DEBIT: "DÉBITO",
  CREDIT: "CRÉDITO",
  VOUCHER: "VOUCHER",
}

/** Campos que o TAB percorre: o valor e, só no dinheiro, o recebido (BR-05). */
type Field = "amount" | "tendered"

/** Pagamentos visíveis: o que sobra das 24 linhas depois dos campos, totais e rodapé. */
const MAX_PAYMENT_ROWS = 5

const AMOUNT_HINT = "digite o valor em centavos: 1000 vira R$ 10,00"
const MISSING_AMOUNT = "informe o valor do pagamento"
const SENDING = "enviando…"
const REGISTER_RETRY = "falha ao registrar o pagamento — ENTER tenta de novo"
const COMPLETE_RETRY = "falha ao concluir — F9 tenta de novo"
const KEY_HINT = "←/→ método · TAB campo · ENTER registra · F9 conclui · ESC volta"

/** Rodapé da tela: dica do formulário, confirmação, recusa do servidor ou falha transitória. */
type Message =
  | { kind: "hint"; text: string }
  | { kind: "success"; text: string }
  | { kind: "rejected"; text: string }
  | { kind: "retry"; text: string }

export function PaymentScreen({ state, api, dispatch, onCompleted }: PaymentScreenProps) {
  const [method, setMethod] = useState<PaymentMethod>("CASH")
  /** Dígitos dos campos, sem máscara: `1000` é o estado; `R$ 10,00` é o que se vê. */
  const [amount, setAmount] = useState("")
  const [tendered, setTendered] = useState("")
  const [field, setField] = useState<Field>("amount")
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /** Espelhos dos campos para o ENTER: o render não espera o `setState` do último dígito. */
  const amountRef = useRef("")
  const tenderedRef = useRef("")
  /**
   * Chave do pagamento em curso: a mesma tentativa (retry do ENTER) reusa a chave, então uma
   * resposta perdida vira replay no servidor em vez de um segundo pagamento; muda o pedido, chave
   * nova. O servidor só grava resposta < 400, então a recusa também libera a chave.
   */
  const paymentKey = useRef<string | null>(null)
  /** Chave da conclusão: uma por tentativa de fechar a venda, reusada em cada F9 (replay, §8). */
  const completionKey = useRef<string | null>(null)
  /** Instante do último caractere imprimível: diz se ele é rajada do leitor e se o ENTER a fecha. */
  const lastCharAt = useRef<number | null>(null)

  const sale = state.sale

  // F9 (o `checkout` do contexto `paying`, §11.3) e ESC são do mapa; o leitor fica desligado
  useGlobalKeyboard({ onKey: handleKey, barcodeEnabled: false })

  /**
   * Teclado da tela (o padrão das demais telas da fase): ESC volta à venda intacta (`cancel`), F9
   * conclui, ←/→ trocam a forma, TAB percorre os campos, BACKSPACE/DEL apagam o último dígito e os
   * dígitos entram na máscara. ENTER registra e **continua** no pagamento. Com requisição em voo
   * nada dispara de novo; a rajada do leitor (o scanner está desligado, mas o hardware não) é
   * descartada e o ENTER que fecha o bipe não registra nada.
   */
  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    const keyName = keyEventToKeyName(event)

    // ESC é o `cancel` do reducer: volta para a venda preservada; consumido para não ser a última saída
    if (keyName === "ESC") {
      if (!busy) {
        dispatch({ type: "cancel" })
      }

      return true
    }

    // requisição em andamento: ENTER/F9 repetido não dispara outra chamada
    if (busy) {
      return true
    }

    const shortcut =
      keyName === null ? null : resolveShortcut(keyName, { screen: "paying", modal: null })

    if (shortcut !== null && shortcut.type === "intent" && shortcut.name === "checkout") {
      void complete()
      return true
    }

    if (keyName === "LEFT" || keyName === "RIGHT") {
      switchMethod(keyName === "LEFT" ? -1 : 1)
      return true
    }

    if (keyName === "TAB") {
      // o recebido só existe no dinheiro (BR-05): fora dele o TAB não tem para onde ir
      if (method === "CASH") {
        setField((current) => (current === "amount" ? "tendered" : "amount"))
      }

      return true
    }

    if (keyName === "BACKSPACE" || keyName === "DEL") {
      erase()
      return true
    }

    if (keyName === "ENTER") {
      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador
      if (!burstEnter()) {
        void register()
      }

      return true
    }

    // combo do sistema/formulário e tecla que não é texto não entram no campo
    if (event.ctrl || event.meta || !isPrintable(event.sequence)) {
      return true
    }

    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = at

    // rajada do leitor: não vira valor de pagamento
    if (burst) {
      return true
    }

    // a máscara é de dígitos: letra, sinal ou espaço não entram no campo
    const typed = event.sequence.replace(/\D/g, "")
    if (typed === "") {
      return true
    }

    if (field === "amount") {
      writeAmount(amountRef.current + typed)
    } else {
      writeTendered(tenderedRef.current + typed)
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
  function writeAmount(next: string): void {
    amountRef.current = next
    setAmount(next)
    clearAttempt()
  }

  function writeTendered(next: string): void {
    tenderedRef.current = next
    setTendered(next)
    clearAttempt()
  }

  /** Mudou o pedido: a mensagem sai de cena e a chave do retry anterior não vale mais (§8). */
  function clearAttempt(): void {
    setMessage(null)
    paymentKey.current = null
  }

  /**
   * Registra o pagamento e fica no pagamento: o valor vai como o operador digitou (centavos →
   * reais) e o recebido só existe no dinheiro; a venda que volta é a do servidor e vira
   * `saleUpdated` — a lista, o pago e o troco desta tela saem dela (BR-05, BR-12).
   */
  async function register(): Promise<void> {
    const amountDigits = amountRef.current

    if (amountDigits === "") {
      setMessage({ kind: "hint", text: MISSING_AMOUNT })
      return
    }

    const payment: SalePaymentIntent = {
      method,
      amount: centsToAmount(digitsToCents(amountDigits)),
    }

    if (method === "CASH" && tenderedRef.current !== "") {
      payment.tenderedAmount = centsToAmount(digitsToCents(tenderedRef.current))
    }

    setMessage(null)
    setBusy(true)

    paymentKey.current ??= crypto.randomUUID()
    const outcome = await api.addPayment(sale.id, payment, paymentKey.current)

    setBusy(false)

    if (outcome.ok) {
      paymentKey.current = null // registrado: o próximo ENTER é um pagamento novo, com chave nova
      amountRef.current = ""
      tenderedRef.current = ""
      setAmount("")
      setTendered("")
      setField("amount")
      setMessage({
        kind: "success",
        text: `registrado: ${METHOD_LABELS[method]} ${formatAmount(payment.amount)}`,
      })
      dispatch({ type: "saleUpdated", sale: outcome.sale })
      return
    }

    if (outcome.kind === "rejected") {
      // recusa não é gravada no servidor: a próxima tentativa é outra operação, com chave nova
      paymentKey.current = null
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    const policy = problemPolicy(outcome.problem)

    if (policy.kind === "session" || policy.kind === "reconcile") {
      // sessão caída ou operação a conferir: quem trata é o shell (1117), e esta tentativa não volta
      // com a mesma chave — o estado local pode não valer mais
      paymentKey.current = null
      dispatch({ type: "apiFailed", problem: outcome.problem })
      return
    }

    // transitória: a tentativa segue com a mesma chave, e o mesmo ENTER refaz
    setMessage({
      kind: outcome.kind === "retryable" ? "retry" : "rejected",
      text: outcome.kind === "retryable" ? REGISTER_RETRY : outcome.problem.detail,
    })
  }

  /**
   * F9: conclui a venda (`POST /sales/{id}/complete`, 200) e leva o resumo que o servidor devolveu
   * para a tela de sucesso. A chave da conclusão nasce uma vez por tentativa de fechar a venda e
   * vai em todas as repetições — um F9 depois de uma falha transitória é o mesmo fechamento, e o
   * servidor devolve o replay sem uma segunda baixa de estoque nem um segundo movimento de caixa.
   */
  async function complete(): Promise<void> {
    if (busy) {
      return
    }

    setMessage(null)
    setBusy(true)

    completionKey.current ??= crypto.randomUUID()
    const outcome = await api.completeSale(sale.id, completionKey.current)

    setBusy(false)

    if (outcome.ok) {
      dispatch({ type: "saleCompleted", receipt: outcome.receipt })
      onCompleted() // o shell esquece o nome do cliente da venda que fechou
      return
    }

    if (outcome.kind === "rejected") {
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    const policy = problemPolicy(outcome.problem)

    if (policy.kind === "session" || policy.kind === "reconcile") {
      // sessão caída ou venda a conferir: o shell assume (1117) e a próxima tentativa é nova
      completionKey.current = null
      dispatch({ type: "apiFailed", problem: outcome.problem })
      return
    }

    setMessage({
      kind: outcome.kind === "retryable" ? "retry" : "rejected",
      text: outcome.kind === "retryable" ? COMPLETE_RETRY : outcome.problem.detail,
    })
  }

  /** ←/→ troca a forma; fora do dinheiro o foco volta para o valor (o recebido sai de cena). */
  function switchMethod(delta: 1 | -1): void {
    const index = METHODS.indexOf(method)
    const next = METHODS[(index + delta + METHODS.length) % METHODS.length] ?? "CASH"

    setMethod(next)
    // mudou a forma, mudou o corpo: a chave do retry anterior não vale para esta chamada (§8)
    clearAttempt()

    if (next !== "CASH") {
      setField("amount")
    }
  }

  /** BACKSPACE/DEL apaga o último dígito do campo em foco — o valor é digitado da esquerda para a direita. */
  function erase(): void {
    if (field === "amount") {
      writeAmount(amountRef.current.slice(0, -1))
    } else {
      writeTendered(tenderedRef.current.slice(0, -1))
    }
  }

  const visible = sale.payments.slice(-MAX_PAYMENT_ROWS)
  const hidden = sale.payments.length - visible.length
  const change = sale.changeAmount
  const methodLine = METHODS.map((option) =>
    option === method ? `[${METHOD_LABELS[option]}]` : METHOD_LABELS[option],
  ).join(" · ")

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text fg={theme.header} attributes={TextAttributes.BOLD} wrapMode="none">
        Pagamento (F9)
      </text>
      <text wrapMode="none">{`Operador: ${state.operator.name} · Caixa: ${state.register.name}`}</text>
      {/* linha do aviso é reservada, como na venda: o recado do shell (1117) não desloca o quadro */}
      <text fg={theme.warning} wrapMode="none">
        {state.notice ?? " "}
      </text>
      <text> </text>
      <text wrapMode="none">{`Método: ${methodLine}`}</text>
      <text fg={amount === "" ? theme.muted : theme.text} wrapMode="none">
        {`${field === "amount" ? "›" : " "} Valor: ${formatBRL(digitsToCents(amount))}`}
      </text>
      {method === "CASH" ? (
        <text fg={tendered === "" ? theme.muted : theme.text} wrapMode="none">
          {`${field === "tendered" ? "›" : " "} Recebido: ${formatBRL(digitsToCents(tendered))}`}
        </text>
      ) : null}
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {SENDING}
        </text>
      ) : message !== null ? (
        <MessageRow message={message} />
      ) : amount === "" ? (
        <text fg={theme.muted} wrapMode="none">
          {AMOUNT_HINT}
        </text>
      ) : null}
      <text> </text>
      <text wrapMode="none">Pagamentos:</text>
      {hidden === 0 ? null : <text fg={theme.muted} wrapMode="none">{`… ${hidden} acima`}</text>}
      {sale.payments.length === 0 ? (
        <text fg={theme.muted} wrapMode="none">
          nenhum pagamento registrado
        </text>
      ) : (
        visible.map((payment, index) => (
          <PaymentRow key={payment.id} payment={payment} position={hidden + index + 1} />
        ))
      )}
      <text wrapMode="none">
        {`Pago: ${formatAmount(sale.paidAmount)} de ${formatAmount(sale.total)}`}
      </text>
      {/* o troco é o do servidor (BR-05): a tela só o destaca quando existe */}
      <text
        fg={change > 0 ? theme.success : theme.text}
        attributes={change > 0 ? TextAttributes.BOLD : undefined}
        wrapMode="none"
      >
        {`TROCO: ${formatAmount(change)}`}
      </text>
      <text> </text>
      <text fg={theme.muted} wrapMode="none">
        {KEY_HINT}
      </text>
    </box>
  )
}

/** Rodapé: verde no pagamento aceito, amarelo na dica e no retry, vermelho na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  const color =
    message.kind === "success"
      ? theme.success
      : message.kind === "rejected"
        ? theme.danger
        : theme.warning

  return (
    <text fg={color} wrapMode="none">
      {message.text}
    </text>
  )
}

/** Linha de um pagamento registrado: forma, valor e o troco que o servidor calculou, se houver. */
function PaymentRow({ payment, position }: { payment: PaymentView; position: number }) {
  const change = payment.changeAmount > 0 ? ` · troco ${formatAmount(payment.changeAmount)}` : ""

  return (
    <text wrapMode="none">
      {`${position}. ${METHOD_LABELS[payment.method]} — ${formatAmount(payment.amount)}${change}`}
    </text>
  )
}
