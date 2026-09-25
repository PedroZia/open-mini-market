/** @jsxImportSource @opentui/react */
import type { KeyEvent } from "@opentui/core"
import { useRef, useState } from "react"

import type { BarcodeProduct, TerminalApi } from "../api/terminalApi"
import { formatAmount } from "../core/money"
import { createScanner } from "../core/scanner"
import type { ApiProblem } from "../core/state"
import { keyEventToScannerChar } from "./adapters/scanner"
import { useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import {
  burstDuration,
  diagnoseReading,
  lastInterval,
  READER_HISTORY_SIZE,
  SLOW_INTERVAL_MS,
  type ReaderDiagnosisKind,
  type ReaderReading,
  type ReaderResolution,
} from "./readerDiagnosis"
import { theme } from "./theme"

/**
 * Autoteste do leitor (F11 2.0, passo 1129b): o overlay da Ink (1104c) portado para a UI nova, com o
 * histórico das 5 últimas leituras e o diagnóstico do transporte (1129a) à vista — é o que dispensa
 * o suporte técnico quando "o leitor não funciona".
 *
 * O autoteste coleta as **próprias** leituras: com ele à vista o leitor da venda fica desligado
 * (`barcodeEnabled` do `SaleScreen`, §11.3) e o handler desta tela alimenta o `core/scanner` com os
 * caracteres e os instantes medidos aqui (`performance.now()`), registrando um `ReaderReading` a cada
 * leitura fechada. A rajada que morre **sem terminador** — o caso do leitor sem sufixo, em que nada
 * chega à venda — também é registrada (sem o terminador): o `core/scanner` descarta o buffer no
 * caractere seguinte e, sem isso, a tela mostraria "nenhuma leitura" para sempre, escondendo
 * exatamente o defeito que o diagnóstico acusa.
 *
 * O código aparece **bruto**, sem trim nem parse (BR-14): quem interpreta é o servidor, e a
 * "interpretação" exibida é o que `api.resolveBarcode` respondeu — o produto com nome/preço (e a
 * quantidade sugerida da etiqueta de balança) ou o `code` da recusa. Nada aqui recalcula rajada,
 * terminador, preço ou quantidade: o timing e o terminador são do `core/scanner`, o diagnóstico é do
 * `readerDiagnosis` (1129a) e os valores são os do servidor (BR-12).
 *
 * Como os demais modais (1126a), o corpo da venda sai de cena enquanto o autoteste está à vista e o
 * ESC fecha, devolvendo a venda como estava. As instruções de configuração são o resumo do guia
 * (`docs/leitores.md`), para o operador conferir o equipamento sem sair do PDV.
 */

export type ReaderSelfTestScreenProps = {
  /** Camada de API injetada: o código bruto vai ao servidor (BR-14) e o desfecho volta para a tela. */
  api: TerminalApi
  /** ESC do operador: o overlay sai de cena e a venda volta como estava. */
  onClosed: () => void
}

/** Terminador da leitura: ENTER (`\r`/`\n`) ou TAB, a mesma regra do `core/scanner`. */
const TERMINATORS: ReadonlySet<string> = new Set(["\r", "\n", "\t"])

/** Menor rajada que vira leitura — a mesma regra do `core/scanner`: uma tecla solta não é bipe. */
const MIN_BURST_LENGTH = 2

/**
 * O que o servidor respondeu para uma leitura (BR-14): o produto, o `problem+json` da recusa ou a
 * falha de transporte; `pending` é a leitura que ainda está indo ao servidor.
 */
type Outcome =
  | { status: "pending" }
  | { status: "found"; product: BarcodeProduct }
  | { status: "problem"; problem: ApiProblem }

/**
 * Leitura do autoteste com o desfecho do servidor: é um `ReaderReading` do 1129a (o `diagnoseReading`
 * o consome direto) e o `outcome` é o texto que a interpretação e o histórico exibem. O desfecho
 * chega depois — o dono da leitura a atualiza no histórico quando a resposta volta.
 */
type ReadingEntry = ReaderReading & { outcome: Outcome }

/** Rajada em coleta: os caracteres e os instantes que o `core/scanner` está vendo. */
type PendingBurst = { chars: string[]; times: number[] }

/** Instruções do guia (`docs/leitores.md`) que importam para o PDV, uma linha cada. */
const SETUP_INSTRUCTIONS = [
  "Sufixo: ENTER (CR) ou TAB — é o que fecha a leitura",
  "Prefixo/AIM ID e corte de dígitos: desligados",
  "Simbologias: EAN-13 ligada e só as que a loja usa",
  "Layout de teclado: US — o ABNT2 troca ' \" / ;",
  "DV da etiqueta não é conferido — guia: docs/leitores.md",
]

/** Nome curto de cada diagnóstico para a linha do histórico, que ocupa **uma** linha só. */
const DIAGNOSIS_LABELS: Readonly<Record<ReaderDiagnosisKind, string>> = {
  slow: "lento",
  "no-terminator": "sufixo",
  layout: "layout",
  "server-error": "erro",
}

export function ReaderSelfTestScreen({ api, onClosed }: ReaderSelfTestScreenProps) {
  /** Buffer do leitor desta tela: a rajada, o terminador e o timing (o da venda está desligado). */
  const [scanner] = useState(createScanner)
  /** Leituras fechadas, da mais nova para a mais antiga (no máximo `READER_HISTORY_SIZE`). */
  const [history, setHistory] = useState<readonly ReadingEntry[]>([])
  /** Rajada em coleta: o que o `core/scanner` ainda não fechou (ou vai descartar). */
  const burstRef = useRef<PendingBurst>({ chars: [], times: [] })
  /** Instante do último caractere da rajada: mede a quebra (≥ 50 ms) e o terminador atrasado. */
  const lastAtRef = useRef<number | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  const latest = history[0] ?? null
  const diagnoses = latest === null ? [] : diagnoseReading(latest)

  /**
   * Teclado do autoteste: o ESC fecha e o resto alimenta o scanner **desta** tela — o leitor da
   * venda está desligado enquanto o overlay está à vista (§11.3), então a rajada não vira item.
   * O `core/scanner` decide o que é rajada e quando o terminador fecha a leitura; o instante de cada
   * caractere vem de `performance.now()`, como no wiring da Ink.
   */
  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      onClosed()
      return true
    }

    const char = keyEventToScannerChar(event)
    const at = performance.now()

    if (char === null) {
      // tecla sem texto (setas, DEL, F...): encerra a rajada que já morreu sem terminador
      flushPending(at)
      return true
    }

    const terminator = TERMINATORS.has(char)
    // `*` devagar é o multiplicador do `core/scanner` (não conteúdo do código, BR-14): leva o que
    // estava pendente junto, como o `clearBurst` dele, e não entra na rajada
    const slowStar = char === "*" && !inBurst(at)

    if (!terminator) {
      if (slowStar) {
        clearPending()
      } else {
        // sem terminador, o `core/scanner` descarta a rajada no próximo caractere: registra o que
        // veio antes do descarte para o diagnóstico cobrar o sufixo ("sem sufixo a leitura não fecha")
        flushPending(at)
      }
    }

    const scan = scanner.feed(char, at)

    if (scan !== null) {
      // o terminador fechou a leitura: o código bruto é do scanner e a janela é a rajada coletada;
      // a rajada zera aqui para a próxima leitura não herdar os caracteres desta
      const { chars, times } = burstRef.current
      clearPending()
      closeReading(scan.barcode, [...chars, char], [...times, at], true)
      return true
    }

    // terminador sem rajada (o ENTER humano, não uma leitura) ou o multiplicador: não é código
    if (terminator || slowStar) {
      lastAtRef.current = null
      return true
    }

    burstRef.current.chars.push(char)
    burstRef.current.times.push(at)
    lastAtRef.current = at
    return true
  }

  /** A rajada ainda está viva? O mesmo limiar do `core/scanner` entre caracteres consecutivos. */
  function inBurst(at: number): boolean {
    const last = lastAtRef.current
    return last !== null && at - last < SLOW_INTERVAL_MS
  }

  /**
   * Rajada que passou do limiar entre caracteres sem terminador: o `core/scanner` vai descartá-la no
   * próximo caractere e nada chegaria à tela — o autoteste a registra **sem terminador**, para o
   * diagnóstico acusar o sufixo; rajada de uma tecla só não é leitura (como no scanner).
   */
  function flushPending(at: number): void {
    const last = lastAtRef.current

    if (last === null || at - last < SLOW_INTERVAL_MS) {
      return
    }

    const { chars, times } = burstRef.current
    clearPending()

    if (chars.length >= MIN_BURST_LENGTH) {
      closeReading(chars.join(""), chars, times, false)
    }
  }

  /** Zera a rajada pendente sem registrar leitura: o mesmo `clearBurst` do `core/scanner`. */
  function clearPending(): void {
    burstRef.current = { chars: [], times: [] }
    lastAtRef.current = null
  }

  /**
   * Fecha a leitura no histórico (com o desfecho pendente) e pergunta ao servidor o que é o código.
   * Até a resposta chegar, a resolução é o `ok` provisório do diagnóstico — nenhum aviso de servidor
   * é exibido enquanto a consulta está em voo; o `resolveReading` completa a entrada.
   */
  function closeReading(
    code: string,
    chars: readonly string[],
    timestampsMs: readonly number[],
    terminated: boolean,
  ): void {
    const entry: ReadingEntry = {
      code,
      chars,
      timestampsMs,
      terminated,
      resolution: { ok: true },
      outcome: { status: "pending" },
    }

    setHistory((current) => pushEntry(current, entry))
    void resolveReading(entry)
  }

  /**
   * Consulta o código bruto no servidor (BR-14) e completa **a própria** leitura no histórico — a
   * resposta atrasada de uma leitura antiga não mexe na nova, porque cada uma tem o seu lugar. A
   * falha de transporte também vira desfecho (`problem` com status 0): a tela mostra, não derruba.
   */
  async function resolveReading(entry: ReadingEntry): Promise<void> {
    let outcome: Outcome

    try {
      const answer = await api.resolveBarcode(entry.code)

      outcome = answer.ok
        ? { status: "found", product: answer.product }
        : { status: "problem", problem: answer.problem }
    } catch (error) {
      // contrato do client quebrado: a tela mostra a falha em vez de derrubar o PDV (como na Ink)
      outcome = { status: "problem", problem: localProblem(error) }
    }

    setHistory((current) =>
      current.map((item) =>
        item === entry ? { ...item, resolution: resolutionOf(outcome), outcome } : item,
      ),
    )
  }

  return (
    <ModalFrame title="Autoteste do leitor (F11)" hints="ESC fecha e volta para a venda">
      <text wrapMode="none">{`Última leitura: ${latest?.code ?? "nenhuma ainda"}`}</text>
      <text wrapMode="none">{describeTiming(latest)}</text>
      <text>{`Interpretação (do servidor): ${describeOutcome(latest)}`}</text>
      <text wrapMode="none">Diagnóstico:</text>
      {latest === null ? (
        <text fg={theme.muted} wrapMode="none">
          aguardando leitura
        </text>
      ) : diagnoses.length === 0 ? (
        <text fg={theme.success} wrapMode="none">
          nenhum aviso — transporte ok
        </text>
      ) : (
        diagnoses.map((diagnosis) => (
          <text key={diagnosis.kind} fg={diagnosisColor(diagnosis.kind)}>
            {`- ${diagnosis.message}`}
          </text>
        ))
      )}
      <text wrapMode="none"> </text>
      <text wrapMode="none">{`Histórico (${READER_HISTORY_SIZE}):`}</text>
      {history.length === 0 ? (
        <text fg={theme.muted} wrapMode="none">
          nenhuma leitura ainda
        </text>
      ) : (
        history.map((entry) => (
          <text
            key={`${entry.code}:${entry.timestampsMs[0] ?? 0}`}
            fg={entry === latest ? theme.text : theme.muted}
            wrapMode="none"
          >
            {historyLine(entry)}
          </text>
        ))
      )}
      <text wrapMode="none"> </text>
      <text wrapMode="none">Instruções de configuração:</text>
      {SETUP_INSTRUCTIONS.map((instruction) => (
        <text key={instruction} wrapMode="none">
          {`- ${instruction}`}
        </text>
      ))}
    </ModalFrame>
  )
}

/** Leitura nova na frente e no máximo `READER_HISTORY_SIZE` (o corte que o 1129a define). */
function pushEntry(entries: readonly ReadingEntry[], entry: ReadingEntry): ReadingEntry[] {
  return [entry, ...entries].slice(0, READER_HISTORY_SIZE)
}

/** Intervalo máximo entre caracteres e duração total da última rajada, como o F11 os mede. */
function describeTiming(entry: ReadingEntry | null): string {
  if (entry === null) {
    return "Intervalo entre caracteres: — · rajada: —"
  }

  return `Intervalo entre caracteres: ${Math.round(lastInterval(entry))} ms · rajada: ${Math.round(burstDuration(entry))} ms`
}

/** O que o servidor respondeu para a leitura (BR-14), na voz que o operador lê. */
function describeOutcome(entry: ReadingEntry | null): string {
  if (entry === null) {
    return "aguardando leitura"
  }

  const { outcome } = entry

  switch (outcome.status) {
    case "pending":
      return "consultando o servidor…"
    case "found": {
      const found = `produto "${outcome.product.name}" — ${formatAmount(outcome.product.price)}`

      return outcome.product.quantity === null
        ? found
        : `${found} — etiqueta de balança (quantidade sugerida: ${formatQuantity(outcome.product.quantity)})`
    }
    case "problem": {
      const { status, code, detail } = outcome.problem

      return status === 0
        ? `falha ao resolver: ${detail}`
        : `recusado pelo servidor: ${status} ${code ?? "sem código"} — ${detail}`
    }
  }
}

/** Linha do histórico: código bruto, timing, desfecho do servidor e os avisos do diagnóstico. */
function historyLine(entry: ReadingEntry): string {
  const diagnoses = diagnoseReading(entry)
  const flags =
    diagnoses.length === 0
      ? ""
      : ` · ${diagnoses.map((diagnosis) => DIAGNOSIS_LABELS[diagnosis.kind]).join(", ")}`

  return `- ${entry.code} · ${Math.round(lastInterval(entry))}/${Math.round(burstDuration(entry))} ms · ${describeShortOutcome(entry)}${flags}`
}

/** Desfecho curto do histórico: o nome do produto cabe; a mensagem longa fica na interpretação. */
function describeShortOutcome(entry: ReadingEntry): string {
  const { outcome } = entry

  switch (outcome.status) {
    case "pending":
      return "consultando…"
    case "found":
      return `produto "${outcome.product.name}"`
    case "problem":
      return outcome.problem.status === 0
        ? "falha local"
        : `${outcome.problem.status} ${outcome.problem.code ?? "sem código"}`
  }
}

/** O servidor recusou ou o transporte falhou: o diagnóstico do 1129a lê daqui (sem status = 0). */
function resolutionOf(outcome: Outcome): ReaderResolution {
  if (outcome.status !== "problem") {
    return { ok: true }
  }

  const { status, code } = outcome.problem

  return {
    ok: false,
    status: status === 0 ? undefined : status,
    code: code ?? undefined,
  }
}

/** Falha local do client (contrato quebrado), sem status HTTP: `code` nulo e status 0. */
function localProblem(error: unknown): ApiProblem {
  return {
    status: 0,
    code: null,
    detail: error instanceof Error ? error.message : String(error),
  }
}

/** Recusa do servidor em vermelho; o resto do transporte (rajada, sufixo, layout) em amarelo. */
function diagnosisColor(kind: ReaderDiagnosisKind): string | undefined {
  return kind === "server-error" ? theme.danger : theme.warning
}

/** Quantidade sugerida pela etiqueta, como o servidor a mandou (BR-12): `0.75` → `0,750`. */
function formatQuantity(quantity: number): string {
  return quantity.toFixed(3).replace(".", ",")
}
