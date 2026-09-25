/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test } from "bun:test"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type {
  CashRegisterOption,
  CashRegistersOutcome,
  LoginOutcome,
  TerminalApi,
} from "../api/terminalApi"
import type { ApiProblem } from "../core/state"
import { App } from "./App"

/**
 * Shell roteador do 1124a/1124b com o reducer de verdade (não o dublê) e a guarda de problemas
 * (1117) no caminho: o que se testa é a fiação — login → abertura de caixa, erro bloqueante que
 * reconhece e volta, recusa que fica na tela de entrada e 401 que volta ao login com o aviso —,
 * nunca o HTTP (esse é do api-client).
 */

const OPERADOR = { id: "u1", name: "Ana Souza" }
const SENHA = "segredo"

const CAIXA_01: CashRegisterOption = {
  id: "r1",
  code: "01",
  name: "Caixa principal",
  open: false,
  operatorName: null,
}
const CAIXA_02: CashRegisterOption = {
  id: "r2",
  code: "02",
  name: "Caixa do fundo",
  open: true,
  operatorName: "Maria",
}

/** Problema que nenhum fluxo desta tela usa — os demais métodos só fecham o contrato. */
const API_PROBLEM: ApiProblem = { status: 0, code: null, detail: "não usado neste teste" }

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
    currentSession: mock(async () => ({ ok: true as const, store: null })),
    getSale: mock(async () => failure()),
    listCashRegisters: mock(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [CAIXA_01, CAIXA_02] }),
    ),
    // a abertura e a venda (1124b+) não são deste passo: aqui só fecham o contrato
    openCashRegister: mock(async () => ({ ok: false as const, kind: "alreadyOpen" as const })),
    currentCashSession: mock(async () => failure()),
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

function renderApp(api: TerminalApi) {
  return testRender(<App api={api} />, { width: 80, height: 24 })
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** Digita as credenciais esperando o frame entre as teclas: o handler só re-registra no render seguinte. */
async function typeCredentials(setup: Setup, username = "ana", password = SENHA): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(username)
  })
  await expectFrame(setup, `Usuário: ${username}`)

  await act(async () => {
    setup.mockInput.pressTab()
  })
  await expectFrame(setup, "› Senha:")

  await act(async () => {
    await setup.mockInput.typeText(password)
  })
  await expectFrame(setup, `Senha: ${"•".repeat(password.length)}`)
}

/** Credenciais e ENTER; o destino (lista de caixas, recusa ou erro) é do teste. */
async function signIn(setup: Setup): Promise<void> {
  await typeCredentials(setup)
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

describe("App (1124a/1124b/1125a)", () => {
  test("login e escolha do caixa roteiam para a abertura de caixa", async () => {
    const login = mock(
      async (): Promise<LoginOutcome> => ({
        ok: true,
        operator: OPERADOR,
      }),
    )
    const setup = await renderApp(apiStub({ login }))

    try {
      await signIn(setup)
      await expectFrame(setup, "Escolha o caixa")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "Abertura de caixa")

      expect(login).toHaveBeenLastCalledWith("ana", SENHA, "r1")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("abrir o caixa roteia para a tela de venda com a loja do cabeçalho", async () => {
    const openCashRegister = mock(async () => ({ ok: true as const, sessionId: "s1" }))
    const currentSession = mock(async () => ({
      ok: true as const,
      store: { code: "MATRIZ", name: "Matriz" },
    }))
    const setup = await renderApp(apiStub({ openCashRegister, currentSession }))

    try {
      await signIn(setup)
      await expectFrame(setup, "Escolha o caixa")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "Abertura de caixa")

      await act(async () => {
        await setup.mockInput.typeText("5000")
      })
      await expectFrame(setup, "Fundo de troco: R$ 50,00")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      // a venda abre vazia (o primeiro bipe é do 1125b) e a loja vem do `GET /auth/me` do shell
      const frame = await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      expect(frame).toContain("PDV minimercado · Matriz · Caixa principal")
      expect(frame).toContain("Operador: Ana Souza")
      expect(frame).toContain("TOTAL: R$ 0,00")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("credencial inválida mostra a mensagem do servidor e continua na entrada", async () => {
    const login = mock(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: "rejected",
        message: "usuário ou senha inválidos",
      }),
    )
    const setup = await renderApp(apiStub({ login }))

    try {
      await signIn(setup)
      const frame = await expectFrame(setup, "usuário ou senha inválidos")

      expect(frame).toContain("Usuário: ana")
      expect(frame).not.toContain("••••") // a senha recusada sai do campo
    } finally {
      setup.renderer.destroy()
    }
  })

  test("401 na lista de caixas volta ao login com o aviso da sessão", async () => {
    const listCashRegisters = mock(
      async (): Promise<CashRegistersOutcome> => ({
        ok: false,
        problem: { status: 401, code: "UNAUTHORIZED", detail: "sessão expirada" },
      }),
    )
    const setup = await renderApp(apiStub({ listCashRegisters }))

    try {
      await signIn(setup)
      const frame = await expectFrame(
        setup,
        "sessão expirada — entre novamente; a venda continua aberta",
      )

      // a guarda trata o 401 como sessão: o aviso aparece no login, sem empilhar a tela de erro
      expect(frame).not.toContain("Falha na operação")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha bloqueante no login cai na tela de erro e o ENTER volta para a entrada", async () => {
    const login = mock(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: "failed",
        problem: { status: 503, code: "UNAVAILABLE", detail: "servidor fora do ar" },
      }),
    )
    const setup = await renderApp(apiStub({ login }))

    try {
      await signIn(setup)
      const frame = await expectFrame(setup, "503 — UNAVAILABLE — servidor fora do ar")
      expect(frame).toContain("Falha na operação")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "PDV minimercado — entrada do operador")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ESC também reconhece a falha bloqueante e volta para a entrada", async () => {
    const login = mock(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: "failed",
        problem: { status: 0, code: null, detail: "Falha de rede ao chamar a API." },
      }),
    )
    const setup = await renderApp(apiStub({ login }))

    try {
      await signIn(setup)
      await expectFrame(setup, "0 — sem código — Falha de rede ao chamar a API.")

      await act(async () => {
        setup.mockInput.pressEscape()
      })
      // o parser segura um ESC sozinho por ~20 ms (ambiguidade com sequências), achado do spike
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
      })
      await expectFrame(setup, "PDV minimercado — entrada do operador")
    } finally {
      setup.renderer.destroy()
    }
  })
})
