import { expect, test } from "bun:test"

import {
  burstDuration,
  diagnoseReading,
  lastInterval,
  pushReading,
  READER_HISTORY_SIZE,
  type ReaderReading,
} from "./readerDiagnosis"

/**
 * Aceite do 1129a (diagnóstico do leitor): cada diagnóstico a partir da leitura e do desfecho da
 * resolução, o timing (maior intervalo e duração) e o histórico das 5 últimas. Tudo puro — o teste
 * não abre terminal nem rede.
 */

/** Leitura com timestamps de 10 ms por caractere (terminador incluso); o helper não aplica regra. */
function reading(overrides: Partial<ReaderReading> = {}): ReaderReading {
  const chars = [..."7891000315507", "\r"]

  return {
    code: "7891000315507",
    chars,
    timestampsMs: chars.map((_, index) => index * 10),
    terminated: true,
    resolution: { ok: true },
    ...overrides,
  }
}

test("rajada rápida, terminada e resolvida não gera diagnóstico e mede intervalo e duração", () => {
  const fast = reading()

  expect(diagnoseReading(fast)).toEqual([])
  expect(lastInterval(fast)).toBe(10)
  expect(burstDuration(fast)).toBe(130)
  expect(lastInterval(reading({ chars: [], timestampsMs: [] }))).toBe(0)
})

test("intervalo ≥ 50 ms vira rajada lenta com o valor", () => {
  const slow = reading({
    code: "78910",
    chars: [..."78910", "\r"],
    timestampsMs: [0, 10, 20, 90, 100, 110],
  })

  const [diagnosis] = diagnoseReading(slow)

  expect(diagnosis?.kind).toBe("slow")
  expect(diagnosis?.message).toContain("70 ms")
  expect(lastInterval(slow)).toBe(70)

  // o limite do guia é ≥ 50 ms: exatamente 50 ms já é rajada lenta
  const atLimit = reading({
    code: "12",
    chars: ["1", "2", "\r"],
    timestampsMs: [0, 50, 60],
  })
  expect(diagnoseReading(atLimit).map((item) => item.kind)).toEqual(["slow"])
})

test("rajada sem terminador vira diagnóstico", () => {
  const chars = [..."7891000315507"]
  const withoutTerminator = reading({
    chars,
    timestampsMs: chars.map((_, index) => index * 10),
    terminated: false,
  })

  expect(diagnoseReading(withoutTerminator).map((item) => item.kind)).toEqual(["no-terminator"])
})

test("caractere de layout suspeito vira diagnóstico; código só de dígitos não", () => {
  const suspeito = reading({
    code: "12/34",
    chars: [..."12/34", "\r"],
    timestampsMs: [0, 10, 20, 30, 40, 50],
  })

  const [diagnosis] = diagnoseReading(suspeito)

  expect(diagnosis?.kind).toBe("layout")
  expect(diagnosis?.message).toContain("/")
  expect(diagnoseReading(reading()).some((item) => item.kind === "layout")).toBe(false)
})

test("resolução recusada vira server-error com o code; ok não gera erro", () => {
  const notFound = reading({
    resolution: { ok: false, status: 404, code: "PRODUCT_NOT_FOUND" },
  })

  expect(diagnoseReading(notFound)).toEqual([
    { kind: "server-error", message: "recusado pelo servidor: 404 PRODUCT_NOT_FOUND" },
  ])

  for (const [status, code] of [
    [422, "INVALID_INTERNAL_BARCODE"],
    [403, "ACCESS_DENIED"],
    [401, "SESSION_EXPIRED"],
  ] as const) {
    const [diagnosis] = diagnoseReading(reading({ resolution: { ok: false, status, code } }))

    expect(diagnosis?.kind).toBe("server-error")
    expect(diagnosis?.message).toContain(code)
  }

  expect(
    diagnoseReading(reading({ resolution: { ok: true } })).some(
      (item) => item.kind === "server-error",
    ),
  ).toBe(false)
})

test("histórico guarda as 5 últimas, da mais nova para a mais antiga", () => {
  let history: readonly ReaderReading[] = []

  for (const code of ["1", "2", "3", "4", "5"]) {
    history = pushReading(history, reading({ code }))
  }

  expect(history.map((item) => item.code)).toEqual(["5", "4", "3", "2", "1"])
})

test("leitura nova empurra a mais antiga do histórico", () => {
  let history: readonly ReaderReading[] = []

  for (const code of ["1", "2", "3", "4", "5", "6"]) {
    history = pushReading(history, reading({ code }))
  }

  expect(history).toHaveLength(READER_HISTORY_SIZE)
  expect(history.map((item) => item.code)).toEqual(["6", "5", "4", "3", "2"])
})
