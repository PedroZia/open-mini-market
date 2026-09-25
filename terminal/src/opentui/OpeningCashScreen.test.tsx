/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test, type Mock } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type {
  CashRegistersOutcome,
  CurrentCashSessionOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  TerminalApi,
} from "../api/terminalApi"
import type { Action } from "../core/reducer"
import type { ApiProblem, OpeningCashState } from "../core/state"
import { OpeningCashScreen } from "./OpeningCashScreen"

/**
 * Contrato da abertura de caixa com o reducer (1103), igual ao dublê da Ink: a tela não troca de
 * estado sozinha — ela relata o fato e o shell decide. Como no port do login (1124a), o teclado é
 * da tela (`useGlobalKeyboard`), então cada tecla é um passo do `mockInput`.
 */

const OPERADOR = { id: "u1", name: "Ana Souza" }
const CAIXA = { id: "r1", name: "Caixa principal" }

const STATE: OpeningCashState = { kind: "openingCash", operator: OPERADOR, register: CAIXA }

const PROBLEM: ApiProblem = { status: 503, code: "UNAVAILABLE", detail: "servidor fora do ar" }
const SESSION_PROBLEM: ApiProblem = {
  status: 404,
  code: "CASH_SESSION_NOT_OPEN",
  detail: "caixa sem sessão aberta",
}

/** Problema que nenhum fluxo desta tela usa — os demais métodos só fecham o contrato. */
const API_PROBLEM: ApiProblem = { status: 0, code: null, detail: "não usado na abertura" }

/** Falha de envio do contrato (rede/5xx), como o `SendFailure` das telas de venda. */
function sendFailure(): { ok: false; kind: "retryable"; problem: ApiProblem } {
  return { ok: false, kind: "retryable", problem: API_PROBLEM }
}

/** Leitura que falha: sessão corrente, resumo do fechamento e releitura da venda. */
function failure(): { ok: false; problem: ApiProblem } {
  return { ok: false, problem: API_PROBLEM }
}

function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  return {
    login: mock(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR })),
    logout: mock(async () => undefined),
    // a abertura não lê a loja nem relê venda: aqui só fecham o contrato
    currentSession: mock(async () => ({ ok: true as const, store: null })),
    getSale: mock(async () => failure()),
    listCashRegisters: mock(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    ),
    openCashRegister: mock(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: "session-1" }),
    ),
    currentCashSession: mock(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: "session-9" }),
    ),
    // a abertura não bipa: o bipe (1109) entra na camada tipada só para fechar o contrato
    resolveBarcode: mock(async () => sendFailure()),
    searchProducts: mock(async () => sendFailure()),
    productStock: mock(async () => sendFailure()),
    createSale: mock(async () => sendFailure()),
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
    cashSessionSummary: mock(async () => failure()),
    closeCashSession: mock(async () => sendFailure()),
    cancelSale: mock(async () => sendFailure()),
    ...overrides,
  }
}

type Setup = Awaited<ReturnType<typeof testRender>>
type DispatchSpy = Mock<(action: Action) => void>

/** Espião do despacho: a assinatura é a do `Dispatch<Action>` e o argumento é o que o `expect` lê. */
function dispatchSpy(): DispatchSpy {
  return mock((action: Action) => {
    void action
  })
}

function renderScreen(api: TerminalApi, dispatch: DispatchSpy = dispatchSpy()) {
  return testRender(<OpeningCashScreen state={STATE} api={api} dispatch={dispatch} />, {
    width: 80,
    height: 24,
  })
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** Espera uma condição que só as promises da tela satisfazem (o bun:test não tem `waitFor`). */
async function until(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (predicate()) {
      return
    }

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 2))
    })
  }

  throw new Error("condição não satisfeita a tempo")
}

describe("OpeningCashScreen", () => {
  test("mostra operador, caixa e o valor vazio com a dica", async () => {
    const setup = await renderScreen(apiStub())

    try {
      await setup.renderOnce()
      const frame = setup.captureCharFrame()

      expect(frame).toContain("Abertura de caixa")
      expect(frame).toContain("Operador: Ana Souza · Caixa: Caixa principal")
      expect(frame).toContain("Fundo de troco: R$ 0,00")
      expect(frame).toContain("digite o valor de abertura: 1250 vira R$ 12,50")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("dígitos viram a máscara de centavos e o ENTER abre com o valor em reais", async () => {
    const openCashRegister = mock(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: "session-1" }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ openCashRegister }), dispatch)

    try {
      await act(async () => {
        await setup.mockInput.typeText("1250")
      })
      await expectFrame(setup, "Fundo de troco: R$ 12,50")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "cashOpened", sessionId: "session-1" })
      expect(openCashRegister).toHaveBeenCalledWith("r1", 12.5)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ENTER com o campo vazio não chama a API e avisa o operador", async () => {
    const openCashRegister = mock(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: "session-1" }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ openCashRegister }), dispatch)

    try {
      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "informe o valor de abertura")
      expect(openCashRegister).not.toHaveBeenCalled()
      expect(dispatch).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("não-dígito não entra no campo e BACKSPACE/DEL apagam", async () => {
    const setup = await renderScreen(apiStub())

    try {
      // a letra é descartada pela máscara; os dígitos em volta entram
      await act(async () => {
        await setup.mockInput.typeText("12a34")
      })
      await expectFrame(setup, "Fundo de troco: R$ 12,34")

      await act(async () => {
        setup.mockInput.pressBackspace()
      })
      await expectFrame(setup, "Fundo de troco: R$ 1,23")

      await act(async () => {
        setup.mockInput.pressKey(KeyCodes.DELETE)
      })
      await expectFrame(setup, "Fundo de troco: R$ 0,12")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("enquanto a abertura está em curso mostra 'abrindo...' e não dispara duas", async () => {
    const openCashRegister = mock(
      () =>
        new Promise<OpenCashRegisterOutcome>(() => {
          // fica pendente de propósito: é o estado de abertura em curso que o teste quer ver
        }),
    )
    const setup = await renderScreen(apiStub({ openCashRegister }))

    try {
      await act(async () => {
        await setup.mockInput.typeText("100")
      })
      await expectFrame(setup, "Fundo de troco: R$ 1,00")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "abrindo...")
      expect(openCashRegister).toHaveBeenCalledTimes(1)

      await act(async () => {
        setup.mockInput.pressEnter() // ENTER repetido não dispara outra abertura
      })
      expect(openCashRegister).toHaveBeenCalledTimes(1)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("caixa já aberto busca a sessão existente, avisa e o ENTER segue com ela", async () => {
    const openCashRegister = mock(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: "alreadyOpen" }),
    )
    const currentCashSession = mock(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: "session-9" }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(
      apiStub({ openCashRegister, currentCashSession }),
      dispatch,
    )

    try {
      await act(async () => {
        await setup.mockInput.typeText("500")
      })
      await expectFrame(setup, "Fundo de troco: R$ 5,00")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      await expectFrame(
        setup,
        "caixa já está aberto — seguindo para a venda com a sessão existente",
      )
      expect(currentCashSession).toHaveBeenCalledWith("r1")
      expect(dispatch).not.toHaveBeenCalled() // o aviso espera o ENTER do operador

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "cashOpened", sessionId: "session-9" })
      expect(openCashRegister).toHaveBeenCalledTimes(1) // não reabre nada
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha da abertura vai para a tela de erro com o problem+json", async () => {
    const openCashRegister = mock(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: "failed", problem: PROBLEM }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ openCashRegister }), dispatch)

    try {
      await act(async () => {
        await setup.mockInput.typeText("50")
      })
      await expectFrame(setup, "Fundo de troco: R$ 0,50")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "apiFailed", problem: PROBLEM })
    } finally {
      setup.renderer.destroy()
    }
  })

  test("caixa já aberto sem sessão corrente legível é falha bloqueante", async () => {
    const openCashRegister = mock(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: "alreadyOpen" }),
    )
    const currentCashSession = mock(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: false, problem: SESSION_PROBLEM }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(
      apiStub({ openCashRegister, currentCashSession }),
      dispatch,
    )

    try {
      await act(async () => {
        await setup.mockInput.typeText("50")
      })
      await expectFrame(setup, "Fundo de troco: R$ 0,50")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "apiFailed", problem: SESSION_PROBLEM })
      expect(setup.captureCharFrame()).not.toContain("caixa já está aberto")
    } finally {
      setup.renderer.destroy()
    }
  })
})
