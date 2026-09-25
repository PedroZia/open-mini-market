/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type {
  ProductOption,
  SendFailure,
  StockBalanceView,
  TerminalApi,
} from "../api/terminalApi"
import { formatAmount } from "../core/money"
import type { ApiProblem } from "../core/state"
import { isPrintable } from "./adapters/scanner"
import { useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Consulta de preço (F2, passo 1116 portado no 1126b): modal bloqueante sobre a venda que consulta
 * **sem vender** — o termo, a lista e o detalhe saem todos do `ModalFrame`, e nenhuma chamada de
 * venda sai daqui: o F2 não cria venda, não inclui item e não mexe na venda aberta.
 *
 * O termo é o que o operador digitou ou bipou — código **bruto** ou trecho do nome (BR-14) — e o
 * ENTER consulta: o servidor decide se é um código (`GET /products/barcode/{termo}`) e, quando ele
 * não o conhece (404/422), o mesmo termo vale como nome (`GET /products?search=`, passos 409/404).
 * O produto encontrado é exibido com o preço e a unidade, e o saldo vem do módulo de estoque
 * (`GET /stock/{productId}`, passo 704) — quantidade, mínimo e o aviso de estoque baixo são do
 * servidor (BR-12), a TUI só exibe. Nada é calculado aqui.
 *
 * Na lista (busca por nome) as setas escolhem com clamp nas pontas e o ENTER busca o saldo do
 * selecionado — dá para conferir outro resultado sem digitar de novo; mexer no termo invalida a
 * lista e o detalhe antigos, então o ENTER nunca consulta um resultado que não é mais o que está
 * escrito no campo. Uma consulta por vez: com a resposta em voo o ENTER repetido não dispara outra.
 *
 * A recusa do servidor (403 sem `product.read`/`stock.read`, 404 do produto que sumiu) fica **no
 * próprio modal**, sem fechá-lo, e a falha transitória (rede/5xx) pede o ENTER de novo; a
 * bloqueante é da tela de venda, que fecha o modal e leva o problema para a tela de erro
 * (`onFailed`, §11.4). ESC fecha sem chamar nada.
 *
 * O teclado é **deste** modal (o padrão do `LoginScreen`/1126a): o `prependListener` do hook global
 * põe o listener mais novo na frente, então a tecla chega aqui antes do listener da venda — que, com
 * o modal à vista, devolve `false` (e o leitor dela está desligado, §11.3). O que este modal não usa
 * ele engole: nada vaza para a venda nem para o campo de leitura, e o que o operador digitar fica
 * no campo de consulta — o bipe que chegar aqui consulta o código em vez de virar item.
 */

export type PriceLookupModalProps = {
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** ESC: fecha sem chamar a API — nada é consultado nem adicionado à venda. */
  onCancel: () => void
  /** Falha bloqueante: a tela de venda fecha o modal e leva o problema ao reducer (`apiFailed`). */
  onFailed: (problem: ApiProblem) => void
}

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

/** O que sobra do desfecho da API depois do sucesso: recusa do modal, retry manual ou bloqueante. */
type Refusal = { ok: false; kind: "rejected"; message: string } | SendFailure

/** Produto consultado com o saldo que o servidor devolveu: o que o detalhe exibe. */
type LookupDetail = {
  product: ProductOption
  /** Saldo e aviso de estoque baixo, calculados pelo servidor (BR-12). */
  stock: StockBalanceView
}

const FORM_HINT = "digite o código de barras ou o nome e ENTER consulta"
const MISSING_TERM = "informe o código de barras ou o nome do produto"
const NO_RESULTS = "nenhum produto encontrado"

const BARCODE_RETRY = "falha ao consultar — ENTER tenta de novo"
const SEARCH_RETRY = "falha ao buscar — ENTER tenta de novo"
const STOCK_RETRY = "falha ao consultar o saldo — ENTER tenta de novo"

const CONSULTING = "consultando…"
const FORM_HINTS = "ENTER consulta · ESC fecha"
const LIST_HINTS = "↑↓ escolhe · ENTER consulta · ESC fecha"

export function PriceLookupModal({ api, onCancel, onFailed }: PriceLookupModalProps) {
  /** Texto do campo de consulta: o que o operador digitou (ou o bipe que caiu nele). */
  const [term, setTerm] = useState("")
  /**
   * Espelho do termo para o ENTER: o handler do hook global é o do último render, e os caracteres
   * de uma rajada chegam em eventos seguidos — o ref acumula o que ainda não re-renderizou.
   */
  const termRef = useRef("")
  /**
   * Resultados da última busca por nome (`null` = nada listado): editar o termo limpa a lista, então
   * o ENTER nunca consulta um resultado que não é mais o do campo.
   */
  const [results, setResults] = useState<ProductOption[] | null>(null)
  /** Seleção na lista: clamp nas pontas, como na lista da venda (1108/1110). */
  const [cursor, setCursor] = useState(0)
  /** Produto e saldo que o servidor devolveu: nome, preço, unidade e estoque (BR-12). */
  const [detail, setDetail] = useState<LookupDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

  const index = results === null ? 0 : Math.min(cursor, Math.max(results.length - 1, 0))
  const chosen = results?.[index] ?? null

  useGlobalKeyboard({ onKey: handleKey })

  /** ESC fecha; o resto é do formulário, da lista e do detalhe — nada vaza para a venda. */
  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      onCancel() // consulta é leitura: sair de cena não deixa nada pela metade
      return true
    }

    // combo do sistema/formulário não é texto do campo nem atalho (§11.3)
    if (event.ctrl || event.meta) {
      return true
    }

    // consulta em voo: ENTER repetido não dispara outra
    if (busy) {
      return true
    }

    if (event.name === "return") {
      // com a lista à vista o ENTER consulta o saldo do selecionado; sem lista, consulta o campo
      if (chosen === null) {
        void consult(termRef.current)
      } else {
        void loadStock(chosen)
      }

      return true
    }

    if (event.name === "up" || event.name === "down") {
      moveCursor(event.name === "up" ? -1 : 1)
      return true
    }

    if (event.name === "backspace" || event.name === "delete") {
      writeTerm(termRef.current.slice(0, -1))
      return true
    }

    // o terminador do leitor pode chegar colado no texto: não é texto do campo (como no 1107)
    const typed = event.sequence.replace(/[\r\n]/g, "")

    if (typed !== "" && isPrintable(typed)) {
      writeTerm(termRef.current + typed)
    }

    // setas laterais, TAB e F1–F12 não são do campo: engolidos, não viram atalho da venda
    return true
  }

  /**
   * Escreve no campo de consulta: o estado desenha e o ref guarda o valor corrente. Mexer no termo
   * invalida a lista e o detalhe antigos — o ENTER volta a consultar em vez de repetir o de antes.
   */
  function writeTerm(next: string): void {
    termRef.current = next
    setTerm(next)
    setResults(null)
    setDetail(null)
    setMessage(null)
  }

  /**
   * ENTER no campo: o código vai **bruto** ao servidor (BR-14) e é ele quem decide o que o termo é.
   * Código conhecido → saldo; 404/422 dele → o mesmo termo vale como nome; o resto é recusa/falha.
   */
  async function consult(query: string): Promise<void> {
    const trimmed = query.trim()

    if (trimmed === "") {
      setMessage({ kind: "hint", text: MISSING_TERM })
      return
    }

    setMessage(null)
    setDetail(null)
    setResults(null)
    setBusy(true)

    const outcome = await api.resolveBarcode(trimmed)

    setBusy(false)

    if (outcome.ok) {
      await loadStock({
        id: outcome.product.id,
        name: outcome.product.name,
        price: outcome.product.price,
        unit: outcome.product.unit,
      })
      return
    }

    if (outcome.kind === "notFound") {
      await search(trimmed)
      return
    }

    refuse(outcome, BARCODE_RETRY)
  }

  /** Busca por nome no servidor (404 do código): quem interpreta o termo é ele, a TUI só transporta. */
  async function search(query: string): Promise<void> {
    setMessage(null)
    setBusy(true)

    const outcome = await api.searchProducts(query)

    setBusy(false)

    if (outcome.ok) {
      setResults(outcome.products)
      setCursor(0)
      // lista vazia: o aviso fica no lugar da dica e o ENTER consulta de novo
      setMessage(outcome.products.length === 0 ? { kind: "hint", text: NO_RESULTS } : null)
      return
    }

    refuse(outcome, SEARCH_RETRY)
  }

  /**
   * Saldo do produto escolhido (`GET /stock/{productId}`, passo 704): quantidade, mínimo e o aviso
   * de estoque baixo são do servidor (BR-12). É o mesmo caminho do código resolvido e do resultado
   * escolhido na lista — selecionar outro refaz o saldo. 403/404 ficam no modal; rede/5xx pedem o
   * ENTER de novo (que volta a consultar o termo do campo).
   */
  async function loadStock(product: ProductOption): Promise<void> {
    setMessage(null)
    setBusy(true)

    const outcome = await api.productStock(product.id)

    setBusy(false)

    if (outcome.ok) {
      setDetail({ product, stock: outcome.stock })
      return
    }

    refuse(outcome, STOCK_RETRY)
  }

  /**
   * Recusa (403) fica no modal com o texto do servidor — o operador consulta outro termo sem perder
   * o campo; a transitória (rede/5xx) pede o retry manual; a bloqueante é da tela de venda.
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

  /** Setas na lista: clamp nas pontas, como na lista da venda; sem lista, não há o que mover. */
  function moveCursor(delta: number): void {
    if (results === null || results.length === 0) {
      return
    }

    setCursor((current) => Math.min(Math.max(current + delta, 0), results.length - 1))
  }

  return (
    <ModalFrame
      title="Consulta de preço (F2)"
      hints={results === null ? FORM_HINTS : LIST_HINTS}
    >
      <text wrapMode="none">{`Busca: ${term}`}</text>
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {CONSULTING}
        </text>
      ) : message !== null ? (
        <MessageRow message={message} />
      ) : detail === null && results === null ? (
        <text fg={theme.muted} wrapMode="none">
          {FORM_HINT}
        </text>
      ) : null}
      {detail === null ? null : <DetailRows detail={detail} />}
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

/** Linha de resultado: o selecionado vai destacado; o preço é o do servidor (BR-12). */
function ResultRow({ option, selected }: { option: ProductOption; selected: boolean }) {
  return (
    <text
      fg={selected ? theme.accent : theme.text}
      attributes={selected ? TextAttributes.BOLD : undefined}
      wrapMode="none"
    >
      {`${selected ? "›" : " "} ${option.name} — ${formatAmount(option.price)}`}
    </text>
  )
}

/** Detalhe da consulta: produto, preço e o saldo do servidor, com o alerta de estoque baixo. */
function DetailRows({ detail }: { detail: LookupDetail }) {
  const { product, stock } = detail
  const balance = `Saldo: ${formatQuantity(stock.quantity)} · mínimo ${formatQuantity(stock.minQuantity)}`

  return (
    <>
      <text attributes={TextAttributes.BOLD} wrapMode="none">
        {`Produto: ${product.name}`}
      </text>
      <text wrapMode="none">{`Preço: ${formatAmount(product.price)} · ${product.unit}`}</text>
      <text fg={stock.lowStock ? theme.warning : theme.text} wrapMode="none">
        {stock.lowStock ? `${balance} · ESTOQUE BAIXO` : balance}
      </text>
    </>
  )
}

/** Quantidade em pt-BR, como a lista da venda (1108): inteira como `2`, fracionária como `0,750`. */
function formatQuantity(quantity: number): string {
  return Number.isInteger(quantity) ? String(quantity) : quantity.toFixed(3).replace(".", ",")
}
