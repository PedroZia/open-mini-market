/** @jsxImportSource @opentui/react */
import type { KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { QuickCreateProductIntent } from "../api/terminalApi"
import { centsToAmount, digitsToCents, formatBRL } from "../core/money"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Cadastro rápido (F-02, passo 1128): modal bloqueante sobre a venda que abre quando o bipe (ou a
 * linha digitada) não resolve o código — o 404 do `addSaleItem` —, para o operador cadastrar o
 * produto desconhecido **sem sair da venda**. É o formulário do `POST /products/quick` (passo 1122,
 * pela camada de API): nome, preço mascarado em centavos e unidade.
 *
 * O **código é travado**: é a leitura que abriu o modal, exibida como veio do leitor (BR-14) e sem
 * campo — quem o normaliza é o servidor. A quantidade do bipe (o `3*` do leitor) não entra aqui:
 * ela é da tela de venda, que reenvia o bipe com ela depois do sucesso.
 *
 * Quem recusa é **sempre o servidor**: a sessão da TUI não traz as permissões do operador, então o
 * modal não decide se o cadastro é permitido — o 403 sem `product.quick_create` volta como mensagem
 * no próprio formulário, junto do 409 `BARCODE_ALREADY_EXISTS` (o código apareceu no meio do
 * caminho) e do 400 do nome/preço/unidade. Nada disso fecha o modal: o operador lê a mensagem e
 * corrige o que digitou. Rede/5xx mostram o aviso de retry e o ENTER refaz; a falha bloqueante
 * (401/contrato) é da tela de venda, que fecha o modal e leva o problema para a tela de erro
 * (§11.4). ESC cancela sem chamar a API — o bipe já foi consumido pela tela (`scanDismissed`).
 *
 * O teclado é **deste** modal (o padrão do `DiscountModal`/`CashMovementModal`): o `prependListener`
 * do hook global põe o listener mais novo na frente, então a tecla chega aqui antes do listener da
 * venda — que, com o modal à vista, devolve `false` e tem o leitor desligado (§11.3). O TAB percorre
 * os campos (nome → preço → unidade) e, com o foco na unidade, ←/→ alternam `UN`/`KG`. Como há
 * campos de texto, a **rajada** do leitor é descartada pelo mesmo critério do `core/scanner`
 * (caracteres em intervalo < 50 ms são engolidos, e o ENTER colado neles é o terminador do bipe, não
 * o "sim" do operador).
 */

export type QuickCreateProductModalProps = {
  /** Código lido que o cadastro grava e o reenvio do bipe usa: travado, o modal não o edita. */
  barcode: string
  /**
   * ENTER do formulário: a tela de venda chama a API e devolve o desfecho — sucesso (a tela fecha o
   * modal e reenvia o bipe), recusa do servidor (o modal fica com a mensagem) ou falha transitória
   * (o ENTER refaz).
   */
  onCreate: (intent: QuickCreateProductIntent) => Promise<QuickCreateProductApplyResult>
  /** ESC: cancela sem chamar a API — o bipe já consumido pela tela não volta para a venda. */
  onCancel: () => void
}

/** Desfecho do cadastro para o formulário: criado, recusa do servidor, retry manual ou bloqueante. */
export type QuickCreateProductApplyResult =
  | { kind: "created" }
  | { kind: "rejected"; message: string }
  | { kind: "retryable" }
  | { kind: "failed" }

/** Campos do formulário, na ordem em que o TAB os percorre. */
type Field = "name" | "price" | "unit"

const FIELD_ORDER: readonly Field[] = ["name", "price", "unit"]

/** Unidade do produto: a whitelist do caso de uso (`UN`/`KG`) é do servidor; a tela só alterna. */
type Unit = "UN" | "KG"

/** Textos do formulário: o que falta preencher, o envio em curso e o aviso de retry. */
const MISSING_NAME = "informe o nome do produto"
const MISSING_PRICE = "informe o preço em centavos"
const PRICE_HINT = "digite o preço em centavos: 1250 vira R$ 12,50"
const CREATING = "cadastrando…"
const RETRY_NOTICE = "falha ao cadastrar o produto — ENTER tenta de novo"
const KEY_HINT = "TAB troca o campo · ←/→ na unidade · ENTER cadastra · ESC cancela"

/** Rodapé do modal: dica do formulário, recusa do servidor ou falha transitória. */
type Message = { kind: "hint" | "rejected" | "retry"; text: string }

export function QuickCreateProductModal({ barcode, onCreate, onCancel }: QuickCreateProductModalProps) {
  /** Nome digitado pelo operador: texto livre, obrigatório. */
  const [name, setName] = useState("")
  /** Dígitos do preço, sem máscara: `1250` é o estado; `R$ 12,50` é o que se vê. */
  const [digits, setDigits] = useState("")
  const [unit, setUnit] = useState<Unit>("UN")
  const [field, setField] = useState<Field>("name")
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  /** Espelhos do nome e do preço para o ENTER (o estado do React pode não ter atualizado ainda). */
  const nameRef = useRef("")
  const digitsRef = useRef("")
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      // com o cadastro em voo o modal espera a resposta antes de sair
      if (!busy) {
        onCancel()
      }

      return true
    }

    // combo do sistema/formulário não é texto do campo nem atalho (§11.3)
    if (event.ctrl || event.meta) {
      return true
    }

    // requisição em andamento: ENTER repetido não cadastra duas vezes
    if (busy) {
      return true
    }

    if (event.name === "tab") {
      setField(nextField)
      return true
    }

    if (field === "unit" && (event.name === "left" || event.name === "right")) {
      // as duas unidades ficam à vista; a seta alterna e a ativa vai entre colchetes
      setMessage(null)
      setUnit((current) => (current === "UN" ? "KG" : "UN"))
      return true
    }

    if (event.name === "backspace" || event.name === "delete") {
      setMessage(null)
      if (field === "name") {
        writeName(nameRef.current.slice(0, -1))
      } else if (field === "price") {
        writeDigits(digitsRef.current.slice(0, -1))
      }

      return true
    }

    if (event.name === "return") {
      const at = performance.now()
      const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
      lastCharAt.current = null

      // o ENTER colado na rajada é o terminador do bipe, não o "sim" do operador (§11.3)
      if (!burst) {
        void submit()
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

    // rajada do leitor (o scanner da venda está desligado, mas o hardware não): não vira campo
    if (burst) {
      return true
    }

    setMessage(null)

    if (field === "name") {
      writeName(nameRef.current + event.sequence)
    } else if (field === "price") {
      // a máscara é de dígitos: letra, sinal ou espaço não entram no preço
      const typed = event.sequence.replace(/\D/g, "")
      if (typed !== "") {
        writeDigits(digitsRef.current + typed)
      }
    }
    // no campo da unidade não há texto: quem escolhe é a ←/→

    return true
  }

  /** Escreve no nome: o estado desenha e o ref guarda o valor corrente para o ENTER. */
  function writeName(next: string): void {
    nameRef.current = next
    setName(next)
  }

  /** Escreve no preço: o estado desenha a máscara e o ref guarda os dígitos para o ENTER. */
  function writeDigits(next: string): void {
    digitsRef.current = next
    setDigits(next)
  }

  /**
   * ENTER do formulário: sem nome ou sem preço não há cadastro — a dica fica no modal e nada vai à
   * API. Com os dois, o corpo leva o nome como o operador digitou, o código travado (BR-14), o
   * preço em reais (centavos → reais, como a abertura de caixa) e a unidade escolhida. Quem
   * normaliza e valida é o servidor (BR-12).
   */
  async function submit(): Promise<void> {
    const nameText = nameRef.current.trim()
    const valueDigits = digitsRef.current

    if (nameText === "") {
      setMessage({ kind: "hint", text: MISSING_NAME })
      return
    }

    if (valueDigits === "") {
      setMessage({ kind: "hint", text: MISSING_PRICE })
      return
    }

    setMessage(null)
    setBusy(true)

    const outcome = await onCreate({
      name: nameText,
      barcode,
      price: centsToAmount(digitsToCents(valueDigits)),
      unit,
    })

    setBusy(false)

    if (outcome.kind === "rejected") {
      // 400/403/409 ficam aqui: o operador lê a recusa do servidor e corrige sem perder o digitado
      setMessage({ kind: "rejected", text: outcome.message })
      return
    }

    if (outcome.kind === "retryable") {
      // o formulário fica à vista e o mesmo ENTER refaz
      setMessage({ kind: "retry", text: RETRY_NOTICE })
    }
    // "created" (a tela fecha o modal e reenvia o bipe) e "failed" (a tela já levou o problema ao
    // reducer) não deixam nada à vista: o modal sai de cena pelas mãos da tela de venda
  }

  return (
    <ModalFrame title="Cadastro rápido (F-02)" hints={KEY_HINT}>
      <text fg={theme.muted} wrapMode="none">
        {`Código: ${barcode} (travado)`}
      </text>
      <text wrapMode="none">{`${field === "name" ? "›" : " "} Nome: ${name}`}</text>
      <text wrapMode="none">{`${field === "price" ? "›" : " "} Preço: ${formatBRL(digitsToCents(digits))}`}</text>
      {field === "price" && digits === "" ? (
        <text fg={theme.muted} wrapMode="none">
          {PRICE_HINT}
        </text>
      ) : null}
      <text wrapMode="none">
        {`${field === "unit" ? "›" : " "} Unidade: ${unit === "UN" ? "[UN]" : "UN"} · ${unit === "KG" ? "[KG]" : "KG"}`}
      </text>
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {CREATING}
        </text>
      ) : message === null ? null : (
        <MessageRow message={message} />
      )}
    </ModalFrame>
  )
}

/** Próximo campo no ciclo do TAB: nome → preço → unidade → nome. */
function nextField(field: Field): Field {
  const index = FIELD_ORDER.indexOf(field)
  return FIELD_ORDER[(index + 1) % FIELD_ORDER.length] ?? "name"
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
