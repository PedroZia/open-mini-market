/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act, useReducer } from "react"

import type {
  CashSessionSummaryOutcome,
  CashSessionSummaryView,
  CloseCashSessionIntent,
  CloseCashSessionOutcome,
  SendFailure,
  TerminalApi,
} from "../api/terminalApi"
import type { Action } from "../core/reducer"
import { reduce } from "../core/reducer"
import type { ApiProblem, ClosingCashState, SaleItemView, SaleView } from "../core/state"
import { ClosingCashScreen } from "./ClosingCashScreen"
import { ErrorScreen } from "./ErrorScreen"

/**
 * Fechamento de caixa (F10, 1127b): a tela da UI nova portada da Ink (1115) com o reducer real
 * (1103) por trás e a camada de API dublada com `mock` — o que se testa é a operação da tela, nunca
 * o HTTP (esse é do api-client).
 *
 * O harness faz o papel do shell: em `closingCash` desenha a tela; em `login` (o caixa fechado e a
 * sessão encerrada) e em `error` (a falha bloqueante) um marcador diz para onde o reducer levou o
 * operador. É por ele que se prova que o resumo vem do servidor, que o contado é mascarado, que a
 * diferença exibida é a do `close` (BR-12), que a recusa por venda em andamento **não** fecha o
 * caixa e que o ENTER do caixa fechado revoga a sessão.
 */

const OPERADOR = { id: "u1", name: "Ana Souza" }
const CAIXA = { id: "r1", name: "Caixa principal" }

/** Código do bipe: a rajada do leitor que não pode virar contado nem fechar o caixa. */
const BARCODE = "7891000100103"

/** Problema que nenhum fluxo deste teste usa — os demais métodos só fecham o contrato. */
const API_PROBLEM: ApiProblem = { status: 0, code: null, detail: "não usado neste teste" }

/** Falha transitória do contrato (rede/5xx), como o `SendFailure` das telas de dinheiro. */
function sendFailure(): SendFailure {
  return {
    ok: false,
    kind: "retryable",
    problem: { status: 503, code: "UNAVAILABLE", detail: "servidor fora do ar" },
  }
}

/** Estado em que o shell entrega a tela: caixa aberto e a venda preservada para o ESC (1115). */
const STATE: ClosingCashState = {
  kind: "closingCash",
  operator: OPERADOR,
  register: CAIXA,
  sessionId: "s1",
  sale: null,
  closing: null,
}

/** Resumo como o servidor o devolve: esperado, formas de pagamento e a gaveta da sessão (BR-12). */
function summary(overrides: Partial<CashSessionSummaryView> = {}): CashSessionSummaryView {
  return {
    sessionId: "s1",
    status: "OPEN",
    openingAmount: 10,
    expectedAmount: 44.9,
    countedAmount: null,
    differenceAmount: null,
    totalsByType: { OPENING: 10, SALE: 34.9, WITHDRAWAL: 3, SUPPLY: 3 },
    paymentsByMethod: { CASH: 34.9, PIX: 0, DEBIT: 0, CREDIT: 0, VOUCHER: 0 },
    ...overrides,
  }
}

/** Item da venda preservada pelo ESC: o que volta com ela é o `sale` do estado do reducer. */
const ARROZ: SaleItemView = {
  productId: "p1",
  name: "Arroz 5kg",
  unit: "UN",
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
}

/** Venda como o servidor a devolveu (BR-12); a tela do fechamento só a devolve no ESC. */
const SALE: SaleView = {
  id: "sale-1",
  items: [ARROZ],
  subtotal: 24.9,
  discountAmount: 0,
  total: 24.9,
  paidAmount: 0,
  changeAmount: 0,
  payments: [],
  customerId: null,
}

/** Dublê da camada de API: só o fechamento importa; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  return {
    login: mock(async () => ({
      ok: false as const,
      kind: "rejected" as const,
      message: API_PROBLEM.detail,
    })),
    logout: mock(async () => undefined),
    currentSession: mock(async () => ({ ok: true as const, store: null })),
    listCashRegisters: mock(async () => ({ ok: true as const, registers: [] })),
    openCashRegister: mock(async () => ({ ok: false as const, kind: "alreadyOpen" as const })),
    currentCashSession: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    resolveBarcode: mock(async () => sendFailure()),
    searchProducts: mock(async () => ({ ok: true as const, products: [] })),
    productStock: mock(async () => sendFailure()),
    createSale: mock(async () => sendFailure()),
    getSale: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    addSaleItem: mock(async () => sendFailure()),
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
    cashSessionSummary: mock(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    ),
    closeCashSession: mock(async () => sendFailure()),
    cancelSale: mock(async () => sendFailure()),
    ...overrides,
  }
}

type Setup = Awaited<ReturnType<typeof testRender>>

/**
 * Shell mínimo do teste, como o `App` faz (1127b): o reducer real por trás. Em `closingCash` a tela
 * do F10; no `error`, a tela de erro de verdade (o ENTER volta para o fechamento e o resumo é
 * relido); no `login`, o marcador de que a sessão terminou.
 */
function ClosingHarness({
  api,
  initial,
  onAction,
}: {
  api: TerminalApi
  initial: ClosingCashState
  /** Ouve o que a tela despacha (o cancelamento do ESC, o fechamento e a saída). */
  onAction?: (action: Action) => void
}) {
  const [state, dispatch] = useReducer(reduce, initial)
  const send = (action: Action) => {
    onAction?.(action)
    dispatch(action)
  }

  if (state.kind === "closingCash") {
    return <ClosingCashScreen state={state} api={api} dispatch={send} />
  }

  if (state.kind === "error") {
    return <ErrorScreen problem={state.problem} dispatch={send} />
  }

  if (state.kind === "saleOpen") {
    return <text>{`estado: venda com ${String(state.sale?.items.length ?? 0)} item(ns)`}</text>
  }

  return <text>{`estado: ${state.kind}`}</text>
}

function renderHarness(api: TerminalApi, initial: ClosingCashState = STATE): Promise<Setup> {
  return testRender(<ClosingHarness api={api} initial={initial} />, { width: 80, height: 24 })
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** ENTER do operador: o formulário envia e, com o caixa fechado, encerra a sessão. */
async function pressEnter(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** Tecla nomeada (DEL) do campo: o `mockInput` emite a sequência do terminal. */
async function pressNamed(setup: Setup, key: string): Promise<void> {
  await act(async () => {
    setup.mockInput.pressKey(key)
  })
}

/** Digitação humana: 60 ms por tecla — a rajada do leitor (caracteres em < 50 ms) é descartada. */
async function typeHuman(setup: Setup, text: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(text, 60)
  })
}

/** Rajada do leitor: os caracteres chegam colados e o `\r` fecha o bipe, como no spike (1121). */
async function scanReader(setup: Setup, code: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code)
    setup.mockInput.pressEnter()
  })
}

describe("ClosingCashScreen (F10, 1127b)", () => {
  test("mostra o resumo do servidor e a máscara do contado em centavos", async () => {
    const cashSessionSummary = mock(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    )
    const setup = await renderHarness(apiStub({ cashSessionSummary }))

    try {
      const frame = await expectFrame(setup, "Esperado: R$ 44,90")

      expect(cashSessionSummary).toHaveBeenCalledWith("s1")
      expect(frame).toContain("Fechamento de caixa (F10)")
      expect(frame).toContain("Operador: Ana Souza · Caixa: Caixa principal")
      expect(frame).toContain("Aberto: R$ 10,00")
      expect(frame).toContain("DINHEIRO: R$ 34,90")
      expect(frame).toContain("PIX: R$ 0,00")
      expect(frame).toContain("Sangrias: R$ 3,00 · Suprimentos: R$ 3,00")
      expect(frame).toContain("Valor contado: R$ 0,00")
      expect(frame).toContain("digite o valor contado: 1250 vira R$ 12,50")

      await typeHuman(setup, "1250")

      await expectFrame(setup, "Valor contado: R$ 12,50")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o ENTER manda o contado e a diferença exibida é a que o servidor devolveu (BR-12)", async () => {
    const closeCashSession = mock(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    )
    const setup = await renderHarness(apiStub({ closeCashSession }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "3000")
      await expectFrame(setup, "Valor contado: R$ 30,00")

      await pressEnter(setup)

      const frame = await expectFrame(setup, "Diferença (servidor): R$ -14,90")

      expect(closeCashSession).toHaveBeenCalledWith("r1", { countedAmount: 30 }, expect.any(String))
      expect(frame).toContain("caixa fechado")
      expect(frame).toContain("Esperado: R$ 44,90 · Contado: R$ 30,00")
      expect(frame).toContain("falta dinheiro na gaveta")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ENTER com o campo vazio não manda nada: a tela pede o valor contado", async () => {
    const closeCashSession = mock(async () => sendFailure())
    const setup = await renderHarness(apiStub({ closeCashSession }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")

      await pressEnter(setup)

      const frame = await expectFrame(setup, "informe o valor contado")

      expect(closeCashSession).not.toHaveBeenCalled()
      expect(frame).not.toContain("caixa fechado")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("409 SESSION_HAS_OPEN_SALES fica na tela e o caixa não fecha", async () => {
    const closeCashSession = mock(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: "rejected",
        message: "há venda em andamento — cancele a venda (F4) antes de fechar",
      }),
    )
    const setup = await renderHarness(apiStub({ closeCashSession }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "3000")
      await expectFrame(setup, "Valor contado: R$ 30,00")

      await pressEnter(setup)

      const frame = await expectFrame(
        setup,
        "há venda em andamento — cancele a venda (F4) antes de fechar",
      )

      expect(frame).toContain("Fechamento de caixa (F10)") // a tela do fechamento continua
      expect(frame).toContain("Valor contado: R$ 30,00") // o digitado não se perdeu
      expect(frame).not.toContain("caixa fechado")
      expect(frame).not.toContain("Diferença (servidor)")
      expect(closeCashSession).toHaveBeenCalledTimes(1)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha transitória mantém a tela e o ENTER refaz com a mesma chave (§8)", async () => {
    /** Chaves das tentativas na ordem em que saíram: o retry tem de reusar a mesma. */
    const sentKeys: string[] = []
    let attempts = 0
    const closeCashSession = mock(
      async (
        registerId: string,
        counted: CloseCashSessionIntent,
        key: string,
      ): Promise<CloseCashSessionOutcome> => {
        sentKeys.push(`${registerId}:${String(counted.countedAmount)}:${key}`)
        attempts += 1

        return attempts === 1
          ? {
              ok: false,
              kind: "retryable",
              problem: { status: 0, code: null, detail: "fetch failed" },
            }
          : {
              ok: true,
              closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
            }
      },
    )
    const setup = await renderHarness(apiStub({ closeCashSession }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "3000")
      await pressEnter(setup)

      const failed = await expectFrame(setup, "falha ao fechar o caixa — ENTER tenta de novo")

      expect(failed).toContain("Valor contado: R$ 30,00") // o caixa não se perdeu
      expect(closeCashSession).toHaveBeenCalledTimes(1) // sem retry automático

      await pressEnter(setup)

      await expectFrame(setup, "Diferença (servidor): R$ -14,90")

      expect(closeCashSession).toHaveBeenCalledTimes(2)
      expect(sentKeys[1]).toBe(sentKeys[0]) // mesma chave, mesmo pedido: replay no servidor
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha bloqueante vai para a tela de erro e o ENTER volta ao fechamento com o resumo relido", async () => {
    // o fechamento sem conferência na resposta é a falha de contrato do `closeCashSession` (não veio
    // do servidor: status 0) — quem a leva para a tela de erro é o `apiFailed` da tela
    const closeCashSession = mock(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: "failed",
        problem: { status: 0, code: null, detail: "fechamento sem conferência na resposta" },
      }),
    )
    const cashSessionSummary = mock(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    )
    const setup = await renderHarness(apiStub({ closeCashSession, cashSessionSummary }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "3000")
      await pressEnter(setup)

      const blocked = await expectFrame(setup, "Falha na operação")

      expect(blocked).toContain("0 — sem código — fechamento sem conferência na resposta")
      expect(blocked).not.toContain("caixa fechado")

      await pressEnter(setup)

      // o estado de origem é o fechamento (o caixa não foi encerrado) e o resumo é lido de novo
      const back = await expectFrame(setup, "Esperado: R$ 44,90")

      expect(back).toContain("Fechamento de caixa (F10)")
      expect(cashSessionSummary).toHaveBeenCalledTimes(2)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a leitura do resumo que falha bloqueia na tela de erro", async () => {
    const cashSessionSummary = mock(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: false, problem: API_PROBLEM }),
    )
    const setup = await renderHarness(apiStub({ cashSessionSummary }))

    try {
      const frame = await expectFrame(setup, "Falha na operação")

      expect(frame).not.toContain("Valor contado:")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("com o caixa fechado o ENTER revoga a sessão e volta ao login", async () => {
    const logout = mock(async () => undefined)
    const closeCashSession = mock(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 50, expectedAmount: 44.9, differenceAmount: 5.1 },
      }),
    )
    const setup = await renderHarness(apiStub({ closeCashSession, logout }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "5000")
      await pressEnter(setup)

      const closed = await expectFrame(setup, "Diferença (servidor): R$ 5,10")

      expect(closed).toContain("sobra dinheiro na gaveta")

      await pressEnter(setup)

      const login = await expectFrame(setup, "estado: login")

      expect(logout).toHaveBeenCalledTimes(1)
      expect(login).not.toContain("caixa fechado")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("com o caixa fechado qualquer outra tecla volta ao login sem revogar a sessão", async () => {
    const logout = mock(async () => undefined)
    const closeCashSession = mock(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    )
    const setup = await renderHarness(apiStub({ closeCashSession, logout }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "3000")
      await pressEnter(setup)
      await expectFrame(setup, "Diferença (servidor): R$ -14,90")

      await typeHuman(setup, "x")

      await expectFrame(setup, "estado: login")

      expect(logout).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("BACKSPACE/DEL corrigem o campo contado", async () => {
    const setup = await renderHarness(apiStub())

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")
      await typeHuman(setup, "3000")
      await expectFrame(setup, "Valor contado: R$ 30,00")

      await act(async () => {
        setup.mockInput.pressBackspace()
      })
      await expectFrame(setup, "Valor contado: R$ 3,00")

      await pressNamed(setup, KeyCodes.DELETE)
      await expectFrame(setup, "Valor contado: R$ 0,30")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a rajada do leitor não fecha o caixa: o terminador colado nela não é o 'sim' do operador", async () => {
    const closeCashSession = mock(async () => sendFailure())
    const setup = await renderHarness(apiStub({ closeCashSession }))

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")

      // os caracteres colados não entram (só o primeiro é indistinguível da digitação humana — a
      // mesma troca do campo da gaveta) e o ENTER que fecha o bipe não envia o fechamento
      await scanReader(setup, BARCODE)

      const frame = await expectFrame(setup, "Valor contado: R$ 0,07")

      expect(frame).toContain("Fechamento de caixa (F10)")
      expect(frame).not.toContain("caixa fechado")
      expect(closeCashSession).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ESC volta para a venda com ela preservada, sem fechar o caixa", async () => {
    const closeCashSession = mock(async () => sendFailure())
    const setup = await renderHarness(
      apiStub({ closeCashSession }),
      { ...STATE, sale: SALE },
    )

    try {
      await expectFrame(setup, "Esperado: R$ 44,90")

      await act(async () => {
        setup.mockInput.pressEscape()
      })
      // o parser segura um ESC sozinho por ~20 ms (ambiguidade com sequências), achado do spike
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
      })

      await expectFrame(setup, "estado: venda com 1 item(ns)")

      expect(closeCashSession).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })
})
