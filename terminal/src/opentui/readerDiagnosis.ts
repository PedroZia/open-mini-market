/**
 * Diagnóstico do leitor (F11 2.0, passo 1129a): classifica o transporte do bipe a partir da leitura
 * e do desfecho da resolução no servidor. Puro — sem React, OpenTUI ou rede —, então o 1129b só
 * desenha o resultado e o teste roda no `bun test` sem terminal.
 *
 * A entrada é a leitura como o wiring a mediu: o código bruto (BR-14), os caracteres da rajada com os
 * instantes (a mesma janela do F11: o terminador entra quando fechou) e o que o servidor respondeu.
 * Nada aqui recalcula rajada, terminador ou interpretação: o timing e o terminador continuam sendo do
 * `core/scanner` (§11.3) — o módulo só lê os tempos já medidos.
 *
 * Diagnósticos possíveis (lista vazia = transporte ok), emitidos nesta ordem:
 * - `slow`: algum intervalo entre caracteres ≥ 50 ms, o limite do PDV — o scanner descarta o começo
 *   da rajada e lê só o fim (ou nada), então o leitor está espaçando demais os caracteres;
 * - `no-terminator`: a rajada fechou sem ENTER/TAB — falta o sufixo no leitor;
 * - `layout`: o código tem caractere que denuncia layout de teclado trocado (`'`, `"`, `/`, `;` e
 *   companhia saem diferentes no ABNT2) ou fora do ASCII imprimível. É **suspeita**, não veredito:
 *   um Code 128 legítimo com esses caracteres também cai aqui — quem confirma é o guia
 *   (`docs/leitores.md`);
 * - `server-error`: a resolução recusou (404 `PRODUCT_NOT_FOUND`, 422 `INVALID_INTERNAL_BARCODE`,
 *   401/403...) — o leitor está bem; o problema é cadastro, etiqueta ou sessão.
 */

/** Limite do PDV entre caracteres da rajada (§11.3); a partir dele a leitura vira digitação humana. */
export const SLOW_INTERVAL_MS = 50

/** Quantas leituras o histórico guarda: as 5 últimas. */
export const READER_HISTORY_SIZE = 5

/** Desfecho da resolução do código no servidor (BR-14); recusado carrega o status e o `code`. */
export type ReaderResolution = { ok: true } | { ok: false; status?: number; code?: string }

/** Uma leitura do autoteste, com o timing que o wiring mediu e a resposta do servidor. */
export type ReaderReading = {
  /** Código bruto, como o leitor mandou (BR-14); terminador fora. */
  code: string
  /** Caracteres capturados na rajada, terminador incluso quando `terminated`. */
  chars: readonly string[]
  /** Instante de `chars[i]` em ms, na mesma origem; a janela do F11 inclui o terminador. */
  timestampsMs: readonly number[]
  /** `true` quando ENTER/TAB fechou a rajada. */
  terminated: boolean
  /** O que o servidor respondeu para este código. */
  resolution: ReaderResolution
}

export type ReaderDiagnosisKind = "slow" | "no-terminator" | "layout" | "server-error"

export type ReaderDiagnosis = {
  /** Kind estável para o 1129b decidir o destaque; a mensagem é o que o operador lê. */
  kind: ReaderDiagnosisKind
  message: string
}

/**
 * Caracteres que denunciam layout trocado: no ABNT2 eles saem diferentes do US — o padrão do PDV nas
 * duas pontas —, então um código que devia ser só dígitos chega com um deles no lugar. Cobre os
 * sinais mais comuns da troca (o guia cita `'`, `"`, `/` e `;`); não é lista fechada de layout.
 */
const LAYOUT_SUSPECTS = `'"/;?°´ç~^[]{}`

/** Maior intervalo entre caracteres consecutivos da rajada, em ms; sem par de tempos, mede 0. */
export function lastInterval(reading: ReaderReading): number {
  let maxGapMs = 0
  const times = reading.timestampsMs

  for (let index = 1; index < times.length; index += 1) {
    maxGapMs = Math.max(maxGapMs, times[index]! - times[index - 1]!)
  }

  return maxGapMs
}

/** Duração total da rajada, em ms: do primeiro caractere ao último instante medido (terminador incluso). */
export function burstDuration(reading: ReaderReading): number {
  const first = reading.timestampsMs[0]
  const last = reading.timestampsMs.at(-1)

  return first === undefined || last === undefined ? 0 : Math.max(0, last - first)
}

/**
 * Histórico do autoteste com a leitura nova na frente (mais nova primeiro) e no máximo
 * `READER_HISTORY_SIZE` leituras: a mais antiga cai quando entra uma nova.
 */
export function pushReading(
  history: readonly ReaderReading[],
  reading: ReaderReading,
): ReaderReading[] {
  return [reading, ...history].slice(0, READER_HISTORY_SIZE)
}

/** Diagnósticos da leitura, na ordem documentada no topo; lista vazia = transporte ok. */
export function diagnoseReading(reading: ReaderReading): ReaderDiagnosis[] {
  const diagnoses: ReaderDiagnosis[] = []
  const intervalMs = lastInterval(reading)

  if (intervalMs >= SLOW_INTERVAL_MS) {
    diagnoses.push({
      kind: "slow",
      message: `rajada lenta: ${Math.round(intervalMs)} ms entre caracteres (limite ${SLOW_INTERVAL_MS} ms) — reduza o "inter-character delay" do leitor`,
    })
  }

  if (!reading.terminated) {
    diagnoses.push({
      kind: "no-terminator",
      message: "leitura sem terminador: falta ENTER/TAB — configure o sufixo do leitor",
    })
  }

  const suspects = layoutSuspects(reading.code)

  if (suspects.length > 0) {
    diagnoses.push({
      kind: "layout",
      message: `suspeita de layout: "${suspects.join(" ")}" no código — confira o layout US do leitor e do Windows`,
    })
  }

  if (!reading.resolution.ok) {
    const { status, code } = reading.resolution

    diagnoses.push({
      kind: "server-error",
      message: `recusado pelo servidor: ${status === undefined ? "sem status" : status} ${code ?? "sem código"}`,
    })
  }

  return diagnoses
}

/** Caracteres do código que denunciam layout trocado, sem repetição e na ordem em que aparecem. */
function layoutSuspects(code: string): string[] {
  const suspects = new Set<string>()

  for (const char of code) {
    if (LAYOUT_SUSPECTS.includes(char) || char < " " || char > "~") {
      suspects.add(char)
    }
  }

  return [...suspects]
}
