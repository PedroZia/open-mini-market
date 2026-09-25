/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test } from "bun:test"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type { BarcodeLookupOutcome, SendFailure, TerminalApi } from "../api/terminalApi"
import type { ApiProblem } from "../core/state"
import { ReaderSelfTestScreen } from "./ReaderSelfTestScreen"

/**
 * Autoteste do leitor (F11 2.0, passo 1129b): a tela renderiza a última leitura (o código bruto e o
 * timing da rajada), a interpretação do servidor, o histórico das 5 com o diagnóstico de cada
 * leitura e as instruções do guia (`docs/leitores.md`). O F11 abrir e o ESC fechar são do teste da
 * venda (o wiring); aqui a tela é montada direto, como nos testes da Ink.
 *
 * O harness tem o `TerminalApi` dublado com `mock` (o runner do `src/opentui` é o `bun:test`) e o
 * relógio real: o autoteste mede o intervalo entre os caracteres com `performance.now()`, então o
 * teste separa a rajada rápida (`typeText` colado) da lenta (o terminador atrasado).
 */

const BARCODE = "7891000100103"

/** Problema que nenhum fluxo deste teste usa — os demais métodos só fecham o contrato. */
const API_PROBLEM: ApiProblem = { status: 0, code: null, detail: "não usado neste teste" }

/** Falha de envio do contrato (rede/5xx), como o `SendFailure` da camada de API. */
function sendFailure(): SendFailure {
  return { ok: false, kind: "retryable", problem: API_PROBLEM }
}

/** Produto resolvido pelo servidor (BR-14): nome, preço e a quantidade sugerida da etiqueta. */
function found(name: string, price: number, quantity: number | null = null): BarcodeLookupOutcome {
  return { ok: true, product: { id: "p1", name, price, unit: "UN", quantity } }
}

/** Recusa do servidor como o `problem+json` a traz (§9.2): status, `code` e detalhe. */
function failure(status: number, code: string | null, detail: string): BarcodeLookupOutcome {
  return { ok: false, kind: "notFound", problem: { status, code, detail } }
}

/** Dublê da camada de API: só o `resolveBarcode` do autoteste importa; o resto satisfaz o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  return {
    login: mock(async () => ({ ok: false as const, kind: "rejected" as const, message: API_PROBLEM.detail })),
    logout: mock(async () => undefined),
    currentSession: mock(async () => ({ ok: true as const, store: null })),
    listCashRegisters: mock(async () => ({ ok: true as const, registers: [] })),
    openCashRegister: mock(async () => ({ ok: false as const, kind: "alreadyOpen" as const })),
    currentCashSession: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    resolveBarcode: mock(async () => sendFailure()),
    searchProducts: mock(async () => ({ ok: true as const, products: [] })),
    productStock: mock(async () => sendFailure()),
    quickCreateProduct: mock(async () => sendFailure()),
    createSale: mock(async () => ({ ok: false as const, kind: "failed" as const, problem: API_PROBLEM })),
    getSale: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    addSaleItem: mock(async () => ({ ok: false as const, kind: "notFound" as const, barcode: "" })),
    changeSaleItemQuantity: mock(async () => ({ ok: false as const, kind: "notFound" as const })),
    removeSaleItem: mock(async () => ({ ok: false as const, kind: "notFound" as const })),
    applyDiscount: mock(async () => sendFailure()),
    searchCustomers: mock(async () => sendFailure()),
    linkCustomer: mock(async () => sendFailure()),
    unlinkCustomer: mock(async () => sendFailure()),
    addPayment: mock(async () => sendFailure()),
    completeSale: mock(async () => sendFailure()),
    withdrawCash: mock(async () => sendFailure()),
    supplyCash: mock(async () => sendFailure()),
    cashSessionSummary: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    closeCashSession: mock(async () => sendFailure()),
    cancelSale: mock(async () => sendFailure()),
    ...overrides,
  }
}

type Setup = Awaited<ReturnType<typeof testRender>>

/** Autoteste puro: a API dublada e o `onClosed` do ESC (que o teste da venda observa). */
function renderSelfTest(api: TerminalApi = apiStub(), onClosed: () => void = () => {}): Promise<Setup> {
  return testRender(<ReaderSelfTestScreen api={api} onClosed={onClosed} />, {
    width: 80,
    height: 24,
  })
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** Rajada do leitor: os caracteres chegam colados e o ENTER fecha o bipe no mesmo instante. */
async function scanFast(setup: Setup, code: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code)
    setup.mockInput.pressEnter()
  })
}

/** Rajada com o terminador atrasado: o ENTER chega `delayMs` depois do último caractere. */
async function scanSlow(setup: Setup, code: string, delayMs = 60): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code)
  })
  await waitMs(delayMs)
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** Rajada sem terminador: o leitor manda os caracteres e larga o teclado (o sufixo está faltando). */
async function scanUnterminated(setup: Setup, code: string, delayMs = 60): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code)
  })
  await waitMs(delayMs)
  await act(async () => {
    setup.mockInput.pressArrow("down") // a tecla seguinte encerra a rajada que morreu sem sufixo
  })
}

/** Pausa do teste: o autoteste mede o intervalo entre caracteres, então o relógio é real. */
async function waitMs(ms: number): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

describe("ReaderSelfTestScreen: última leitura, servidor e timing (1129b)", () => {
  test("a rajada vira leitura: código bruto, intervalo e duração na tela, com o produto do servidor", async () => {
    const resolveBarcode = mock(async (): Promise<BarcodeLookupOutcome> => found("Arroz 5kg", 24.9))
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      const initial = await expectFrame(setup, "Última leitura: nenhuma ainda")

      expect(initial).toContain("Autoteste do leitor (F11)")
      expect(initial).toContain("Intervalo entre caracteres: — · rajada: —")
      expect(initial).toContain("Diagnóstico:")
      expect(initial).toContain("aguardando leitura")

      await scanFast(setup, BARCODE)

      const frame = await expectFrame(setup, `Última leitura: ${BARCODE}`)

      expect(frame).toContain("Intervalo entre caracteres:")
      expect(frame).toContain("rajada:")
      expect(resolveBarcode).toHaveBeenCalledWith(BARCODE)

      const resolved = await expectFrame(setup, 'produto "Arroz 5kg"')

      expect(resolved).toContain("R$ 24,90")
      expect(resolved).toContain("nenhum aviso — transporte ok")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o TAB também fecha a leitura e os pedaços da rajada continuam sendo uma só", async () => {
    const resolveBarcode = mock(async (): Promise<BarcodeLookupOutcome> => found("Arroz 5kg", 24.9))
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await act(async () => {
        await setup.mockInput.typeText("7891000")
        await setup.mockInput.typeText("100103")
        setup.mockInput.pressTab()
      })

      const frame = await expectFrame(setup, 'produto "Arroz 5kg"')

      expect(frame).toContain(`Última leitura: ${BARCODE}`)
      expect(resolveBarcode).toHaveBeenCalledWith(BARCODE)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("etiqueta de balança mostra a quantidade sugerida pelo servidor", async () => {
    const resolveBarcode = mock(async (): Promise<BarcodeLookupOutcome> => found("Banana prata", 6.99, 0.75))
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await scanFast(setup, "2000420001234")

      const frame = await expectFrame(setup, "etiqueta de balança (quantidade sugerida: 0,750)")

      expect(frame).toContain('produto "Banana prata"')
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o código com separador vai bruto ao servidor e aparece bruto na tela (BR-14)", async () => {
    const resolveBarcode = mock(
      async (): Promise<BarcodeLookupOutcome> =>
        failure(404, "PRODUCT_NOT_FOUND", "produto com código de barras 789 123 não encontrado"),
    )
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await scanFast(setup, "789 123")

      const frame = await expectFrame(setup, "Última leitura: 789 123")

      expect(resolveBarcode).toHaveBeenCalledWith("789 123")
      expect(frame).toContain("404 PRODUCT_NOT_FOUND")
      expect(frame).toContain("- recusado pelo servidor: 404 PRODUCT_NOT_FOUND")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("teclas de controle no meio da rajada não quebram a leitura", async () => {
    const setup = await renderSelfTest()

    try {
      await act(async () => {
        await setup.mockInput.typeText("789")
        setup.mockInput.pressArrow("up") // seta para cima: não tem texto e fica fora da rajada
        await setup.mockInput.typeText("1000100103")
        setup.mockInput.pressEnter()
      })

      const frame = await expectFrame(setup, `Última leitura: ${BARCODE}`)

      expect(frame).toContain("Intervalo entre caracteres:")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha inesperada do resolver não derruba a tela", async () => {
    const resolveBarcode = mock(async (): Promise<BarcodeLookupOutcome> => {
      throw new Error("conexão caiu")
    })
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await scanFast(setup, BARCODE)

      const frame = await expectFrame(setup, "falha ao resolver: conexão caiu")

      expect(frame).toContain("falha local") // o histórico resume a leitura sem status do servidor
    } finally {
      setup.renderer.destroy()
    }
  })

  test("sem leitura, a tela lista as instruções de configuração do guia", async () => {
    const setup = await renderSelfTest()

    try {
      const frame = await expectFrame(setup, "Instruções de configuração:")

      expect(frame).toContain("Última leitura: nenhuma ainda")
      expect(frame).toContain("nenhuma leitura ainda")
      expect(frame).toContain("- Sufixo: ENTER (CR) ou TAB")
      expect(frame).toContain("- Prefixo/AIM ID e corte de dígitos: desligados")
      expect(frame).toContain("- Simbologias: EAN-13 ligada e só as que a loja usa")
      expect(frame).toContain("- Layout de teclado: US")
      expect(frame).toContain("- DV da etiqueta não é conferido")
      expect(frame).toContain("docs/leitores.md")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("ReaderSelfTestScreen: histórico das 5 leituras (1129b)", () => {
  test("duas leituras ficam na ordem, da mais nova para a mais antiga", async () => {
    const setup = await renderSelfTest()

    try {
      await scanFast(setup, "7891000100103")
      await scanFast(setup, "7891000100104")

      const frame = await expectFrame(setup, "- 7891000100104")

      expect(frame).toContain("Histórico (5):")
      expect(frame).toContain("- 7891000100103")
      expect(frame.indexOf("7891000100104")).toBeLessThan(frame.indexOf("7891000100103"))
      expect(frame).toContain(`Última leitura: 7891000100104`)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a sexta leitura empurra a mais antiga: o histórico guarda só 5", async () => {
    const setup = await renderSelfTest()

    try {
      for (let index = 0; index < 6; index += 1) {
        await scanFast(setup, `789100010010${index}`)
      }

      const frame = await expectFrame(setup, "- 7891000100105")

      expect(frame).toContain("- 7891000100101")
      expect(frame).not.toContain("7891000100100") // a mais antiga saiu do histórico
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a leitura seguinte não herda os caracteres da anterior (a rajada zera a cada leitura)", async () => {
    const setup = await renderSelfTest()

    try {
      await scanFast(setup, "7891000100103")
      await waitMs(60) // pausa entre as leituras: não pode virar uma leitura fantasma sem terminador
      await scanFast(setup, "7891000100104")

      const frame = await expectFrame(setup, "- 7891000100104")

      expect(frame).not.toContain("leitura sem terminador")
      expect(frame.split("7891000100103")).toHaveLength(2) // só a linha do histórico dela
    } finally {
      setup.renderer.destroy()
    }
  })

  test("cada leitura do histórico leva o aviso do próprio diagnóstico", async () => {
    const resolveBarcode = mock(async (): Promise<BarcodeLookupOutcome> => found("Arroz 5kg", 24.9))
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await scanSlow(setup, BARCODE)
      await scanFast(setup, "7891000100104")

      const frame = await expectFrame(setup, "- 7891000100104")

      expect(frame).toContain("· lento") // a rajada lenta ficou marcada na linha dela
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("ReaderSelfTestScreen: diagnóstico do transporte (1129b)", () => {
  test("rajada lenta (terminador atrasado) vira o aviso slow com o valor", async () => {
    const setup = await renderSelfTest()

    try {
      await scanSlow(setup, BARCODE)

      const frame = await expectFrame(setup, "rajada lenta:")

      expect(frame).toContain("entre caracteres (limite 50 ms)")
      expect(frame).toContain("· lento")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("rajada sem terminador vira aviso e o código aparece mesmo sem fechar a leitura", async () => {
    const resolveBarcode = mock(async (): Promise<BarcodeLookupOutcome> => found("Arroz 5kg", 24.9))
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await scanUnterminated(setup, BARCODE)

      const frame = await expectFrame(setup, "leitura sem terminador")

      expect(frame).toContain(`Última leitura: ${BARCODE}`)
      expect(frame).toContain("- leitura sem terminador: falta ENTER/TAB")
      expect(frame).toContain("· sufixo")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("recusa do servidor vira aviso com o code do problem+json", async () => {
    const resolveBarcode = mock(
      async (): Promise<BarcodeLookupOutcome> =>
        failure(422, "INVALID_INTERNAL_BARCODE", "etiqueta embute valor zero"),
    )
    const setup = await renderSelfTest(apiStub({ resolveBarcode }))

    try {
      await scanFast(setup, "2000420000000")

      const frame = await expectFrame(setup, "recusado pelo servidor: 422 INVALID_INTERNAL_BARCODE")

      expect(frame).toContain("- recusado pelo servidor: 422 INVALID_INTERNAL_BARCODE")
      expect(frame).toContain("· erro")
    } finally {
      setup.renderer.destroy()
    }
  })
})
