/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { CustomerOption, SendFailure, TerminalApi } from "../api/terminalApi"
import type { ApiProblem } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Cliente na venda (F6, passo 1112 portado no 1126c): modal bloqueante sobre a venda com a busca por
 * nome ou CPF, a lista de resultados com setas, o vínculo (`PUT /sales/{id}/customer`) e a remoção
 * dele (`DELETE`, passo 811b) — tudo no `ModalFrame`, como os modais do 1126a/1126b.
 *
 * O fluxo é de **busca**, não de cadastro: o operador digita o termo (trecho do nome ou dígitos do
 * CPF) e o ENTER procura no servidor (`GET /customers`, passo 502) — quem decide o que é nome e o
 * que é CPF é ele, nunca esta tela (BR-12). Os resultados ficam à vista com nome e CPF; as setas
 * escolhem (clamp nas pontas) e o ENTER vincula o selecionado. Mexer no termo invalida a lista
 * antiga, então o ENTER nunca vincula um resultado que não é mais o do campo.
 *
 * Com cliente vinculado o modal mostra o atual e o DEL remove o vínculo; sem vínculo, o DEL só
 * avisa e nada vai à API. O nome que o cabeçalho mostra é a **seleção local** (o `SaleDetailResponse`
 * devolve o `customerId`, não o nome): quem guarda o par é o `App`, avisado pelo `onLink`/`onUnlink`
 * da tela de venda — o vínculo real continua sendo o do servidor.
 *
 * Vincular e remover são **mutação da venda** e passam pela `runMutation` da tela de venda (1125c),
 * como o desconto do 1126b: uma por vez, com a venda recalculada pelo servidor virando
 * `saleUpdated`. O modal traduz o desfecho: sucesso e falha bloqueante saem de cena pelas mãos da
 * tela, a recusa do servidor (404 `CUSTOMER_NOT_FOUND`, 422 `CUSTOMER_INACTIVE`, 403) fica **aqui**
 * com a mensagem dele e a falha transitória (rede/5xx) pede o ENTER/DEL de novo. A busca também
 * mostra a recusa no próprio modal; só a falha bloqueante dela (`onFailed`) vai para a tela de erro.
 *
 * O teclado é **deste** modal (o padrão do `LoginScreen`/`PriceLookupModal`): o `prependListener` do
 * hook global põe o listener mais novo na frente, então a tecla chega aqui antes do listener da
 * venda — que, com o modal à vista, devolve `false` (e o leitor dela está desligado, §11.3). Como
 * aqui há campo de busca, a **rajada** do leitor é descartada pelo mesmo critério do `core/scanner`
 * (caracteres em intervalo < 50 ms e o ENTER colado neles): o hardware não sabe que o scanner da
 * venda está desligado, e um bipe não pode virar termo nem vincular cliente. ESC fecha sem chamar
 * nada.
 */

export type CustomerModalProps = {
  /** Cliente vinculado agora, como a busca local o conhece; `null` na venda anônima. */
  customer: CustomerOption | null
  /** Camada de API injetada: dublê no teste, instância única no app — daqui sai **só** a busca. */
  api: TerminalApi
  /** ENTER do resultado: a tela de venda vincula pela `runMutation` e devolve o desfecho. */
  onLink: (option: CustomerOption) => Promise<CustomerApplyResult>
  /** DEL: a tela de venda remove o vínculo pela `runMutation` e devolve o desfecho. */
  onUnlink: () => Promise<CustomerApplyResult>
  /** ESC: fecha sem chamar a API — não busca, não vincula e não remove. */
  onCancel: () => void
  /** Falha bloqueante da busca: a tela de venda fecha o modal e leva o problema ao reducer. */
  onFailed: (problem: ApiProblem) => void
}

/** Desfecho de vincular/remover para o formulário, como o desconto (1126b): o resto é da tela. */
export type CustomerApplyResult =
  | { kind: "applied" }
  | { kind: "rejected"; message: string }
  | { kind: "retryable" }
  | { kind: "failed" }

/** Dica do campo: o termo é o do servidor (trecho do nome ou dígitos do CPF), não um formato da TUI. */
const SEARCH_HINT = "digite o nome ou o CPF e ENTER busca"
const MISSING_TERM = "informe o nome ou o CPF do cliente"
const NO_RESULTS = "nenhum cliente encontrado"
const NO_CUSTOMER = "nenhum cliente vinculado para remover"

const SEARCH_RETRY = "falha ao buscar — ENTER tenta de novo"
const LINK_RETRY = "falha ao vincular — ENTER tenta de novo"
const UNLINK_RETRY = "falha ao remover — DEL tenta de novo"

const SENDING = "enviando…"
const FORM_HINTS = "ENTER busca · DEL remove o vínculo · ESC fecha"
const LIST_HINTS = "↑↓ escolhe · ENTER vincula · DEL remove · ESC fecha"

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

/** O que sobra do desfecho da busca depois do sucesso: recusa do modal, retry manual ou bloqueante. */
type Refusal = { ok: false; kind: "rejected"; message: string } | SendFailure

export function CustomerModal({
  customer,
  api,
  onLink,
  onUnlink,
  onCancel,
  onFailed,
}: CustomerModalProps) {
  /** Texto do campo de busca: o que o operador digitou. */
  const [term, setTerm] = useState("")
  /**
   * Espelho do termo para o ENTER: o handler do hook global é o do último render, e os caracteres
   * de uma digitação rápida chegam em eventos seguidos — o ref guarda o que ainda não re-renderizou.
   */
  const termRef = useRef("")
  /**
   * Resultados da última busca (`null` = nada buscado ainda): editar o termo limpa a lista, então o
   * ENTER nunca vincula um resultado que não é mais do que está escrito no campo.
   */
  const [results, setResults] = useState<CustomerOption[] | null>(null)
  /** Seleção na lista: clamp nas pontas, como na lista da venda (1108/1110). */
  const [cursor, setCursor] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  const index = results === null ? 0 : Math.min(cursor, Math.max(results.length - 1, 0))
  const chosen = results?.[index] ?? null

  useGlobalKeyboard({ onKey: handleKey })

  /** ESC fecha; o resto é da busca, da lista e do vínculo — nada vaza para a venda. */
  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      // uma mutação por vez: com o vínculo em voo o modal espera a resposta antes de sair
      if (!busy) {
        onCancel()
      }

      return true
    }

    // combo do sistema/formulário não é texto do campo nem atalho (§11.3)
    if (event.ctrl || event.meta) {
      return true
    }

    // requisição em andamento: ENTER/DEL repetido não dispara duas chamadas
    if (busy) {
      return true
    }

    const keyName = keyEventToKeyName(event)

    if (keyName === "ENTER") {
      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador (§11.3)
      const at = performance.now()
      const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
      lastCharAt.current = null

      if (!burst) {
        // sem resultado à vista o ENTER busca o que está no campo (pelo ref, não pelo render); com a
        // lista à vista, vincula o selecionado
        if (chosen === null) {
          void search(termRef.current)
        } else {
          void link(chosen)
        }
      }

      return true
    }

    if (keyName === "DEL") {
      void unlink()
      return true
    }

    if (keyName === "UP" || keyName === "DOWN") {
      moveCursor(keyName === "UP" ? -1 : 1)
      return true
    }

    if (keyName === "BACKSPACE") {
      writeTerm(termRef.current.slice(0, -1))
      return true
    }

    // setas laterais, TAB e F1–F12 não são texto do campo: engolidos, não viram atalho da venda
    if (!isPrintable(event.sequence)) {
      return true
    }

    const at = performance.now()
    const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
    lastCharAt.current = at

    // rajada do leitor (o scanner da venda está desligado, mas o hardware não): não vira termo
    if (burst) {
      return true
    }

    writeTerm(termRef.current + event.sequence)
    return true
  }

  /**
   * Escreve no campo de busca: o estado desenha e o ref guarda o valor corrente. Mexer no termo
   * invalida a lista antiga — o ENTER volta a buscar em vez de vincular o que não é mais do campo.
   */
  function writeTerm(next: string): void {
    termRef.current = next
    setTerm(next)
    setResults(null)
    setMessage(null)
  }

  /** Busca o termo no servidor: quem interpreta nome/CPF é ele (502), a TUI só transporta (BR-12). */
  async function search(query: string): Promise<void> {
    const trimmed = query.trim()

    if (trimmed === "") {
      setMessage({ kind: "hint", text: MISSING_TERM })
      return
    }

    setMessage(null)
    setResults(null)
    setBusy(true)

    const outcome = await api.searchCustomers(trimmed)

    setBusy(false)

    if (outcome.ok) {
      setResults(outcome.customers)
      setCursor(0)
      // lista vazia: o aviso fica no lugar da dica, e o ENTER busca de novo
      setMessage(outcome.customers.length === 0 ? { kind: "hint", text: NO_RESULTS } : null)
      return
    }

    refuse(outcome, SEARCH_RETRY)
  }

  /** Vincula o cliente escolhido: o vínculo é do servidor; o nome, a anotação que a tela de venda guarda. */
  async function link(option: CustomerOption): Promise<void> {
    setMessage(null)
    setBusy(true)

    const outcome = await onLink(option)

    setBusy(false)
    translate(outcome, LINK_RETRY)
  }

  /** DEL: remove o cliente da venda; sem vínculo não há o que remover e nada vai à API. */
  async function unlink(): Promise<void> {
    if (customer === null) {
      setMessage({ kind: "hint", text: NO_CUSTOMER })
      return
    }

    setMessage(null)
    setBusy(true)

    const outcome = await onUnlink()

    setBusy(false)
    translate(outcome, UNLINK_RETRY)
  }

  /**
   * Recusa (403/404/422) fica no modal com a mensagem do servidor — o operador corrige ali mesmo,
   * sem perder o termo nem a lista; a transitória (rede/5xx) pede o retry manual na mesma tecla; o
   * resto bloqueia na tela de erro pela mão da tela de venda (que fecha o modal antes).
   */
  function refuse(outcome: Refusal, retryNotice: string): void {
    if (outcome.kind === "rejected") {
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      setMessage({ kind: "retry", text: retryNotice })
      return
    }

    onFailed(outcome.problem)
  }

  /** Desfecho da mutação: recusa e retry ficam à vista; aplicado e bloqueante saem pela tela de venda. */
  function translate(outcome: CustomerApplyResult, retryNotice: string): void {
    if (outcome.kind === "rejected") {
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      setMessage({ kind: "retry", text: retryNotice })
    }
  }

  /** Setas na lista: clamp nas pontas, como na lista da venda; sem lista, não há o que mover. */
  function moveCursor(delta: number): void {
    if (results === null || results.length === 0) {
      return
    }

    setCursor((current) => Math.min(Math.max(current + delta, 0), results.length - 1))
  }

  return (
    <ModalFrame title="Cliente na venda (F6)" hints={results === null ? FORM_HINTS : LIST_HINTS}>
      {customer === null ? null : (
        <text wrapMode="none">{`Cliente atual: ${customer.name}`}</text>
      )}
      <text wrapMode="none">{`Busca: ${term}`}</text>
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {SENDING}
        </text>
      ) : message !== null ? (
        <MessageRow message={message} />
      ) : results === null ? (
        <text fg={theme.muted} wrapMode="none">
          {SEARCH_HINT}
        </text>
      ) : null}
      {results === null
        ? null
        : results.map((option, position) => (
            <ResultRow key={option.id} option={option} selected={position === index} />
          ))}
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

/** Linha de resultado: o selecionado vai destacado; o CPF entra quando o cadastro tem um. */
function ResultRow({ option, selected }: { option: CustomerOption; selected: boolean }) {
  const taxId = formatTaxId(option.taxId)

  return (
    <text
      fg={selected ? theme.accent : theme.text}
      attributes={selected ? TextAttributes.BOLD : undefined}
      wrapMode="none"
    >
      {`${selected ? "›" : " "} ${option.name}${taxId === "" ? "" : ` — ${taxId}`}`}
    </text>
  )
}

/** CPF como o cadastro o guarda (só dígitos, 502a): a máscara é da apresentação, com os 11 dígitos. */
function formatTaxId(taxId: string | null): string {
  const digits = taxId ?? ""

  return digits.length === 11
    ? `${digits.slice(0, 3)}.${digits.slice(3, 6)}.${digits.slice(6, 9)}-${digits.slice(9)}`
    : digits
}
