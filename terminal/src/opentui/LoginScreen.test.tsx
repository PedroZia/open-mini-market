/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test, type Mock } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type {
  CashRegisterOption,
  CashRegistersOutcome,
  LoginOutcome,
  TerminalApi,
} from "../api/terminalApi"
import type { Action } from "../core/reducer"
import type { ApiProblem, LoginState } from "../core/state"
import { LoginScreen } from "./LoginScreen"

/**
 * Contrato da tela de login com o reducer (1103), igual ao dublê da Ink: a tela não troca de estado
 * sozinha — ela relata o fato e o shell decide. Aqui o `dispatch` é um espião, então dá para
 * conferir exatamente o que sai da tela (o `loginSucceeded` que leva à abertura de caixa) e os
 * casos em que não sai nada. O que muda na UI nova é o teclado: o texto chega pelo handler da tela
 * no `useGlobalKeyboard` (1124a), então cada tecla é um passo do `mockInput`.
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
const CAIXA_03: CashRegisterOption = {
  id: "r3",
  code: "03",
  name: "Caixa do açougue",
  open: false,
  operatorName: null,
}

/** Problema que nenhum fluxo desta tela usa — os demais métodos só fecham o contrato. */
const API_PROBLEM: ApiProblem = { status: 0, code: null, detail: "não usado na tela de login" }

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
    // a loja do cabeçalho e a releitura da reconciliação (1117) fecham o contrato; os fluxos que
    // precisam delas sobrescrevem no próprio teste
    currentSession: mock(async () => ({ ok: true as const, store: null })),
    getSale: mock(async () => failure()),
    listCashRegisters: mock(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [CAIXA_01, CAIXA_02] }),
    ),
    // a abertura e a venda (1124b+) não passam pelo login: aqui só fecham o contrato
    openCashRegister: mock(async () => ({ ok: false as const, kind: "alreadyOpen" as const })),
    currentCashSession: mock(async () => failure()),
    resolveBarcode: mock(async () => sendFailure()),
    searchProducts: mock(async () => sendFailure()),
    productStock: mock(async () => sendFailure()),
    quickCreateProduct: mock(async () => sendFailure()),
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

function renderScreen(
  api: TerminalApi,
  dispatch: DispatchSpy = dispatchSpy(),
  state: LoginState = { kind: "login", failure: null },
  preferredRegisterId: string | null = null,
) {
  return testRender(
    <LoginScreen
      state={state}
      api={api}
      dispatch={dispatch}
      preferredRegisterId={preferredRegisterId}
    />,
    { width: 80, height: 24 },
  )
}

/** Linha do frame que contém o rótulo: comparar a linha inteira evita casar com um frame velho. */
function lineWith(frame: string, label: string): string {
  return frame.split("\n").find((line) => line.includes(label)) ?? ""
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

/** Credenciais e ENTER; o destino (lista de caixas ou recusa) é do teste. */
async function signIn(setup: Setup): Promise<void> {
  await typeCredentials(setup)
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

describe("LoginScreen", () => {
  test("ENTER no caixa escolhido despacha loginSucceeded com operador e caixa", async () => {
    const calls: string[] = []
    const login = mock(
      async (_username: string, _password: string, registerId?: string): Promise<LoginOutcome> => {
        calls.push(registerId === undefined ? "login sem caixa" : `login ${registerId}`)
        return { ok: true, operator: OPERADOR }
      },
    )
    const logout = mock(async () => {
      calls.push("logout")
    })
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ login, logout }), dispatch)

    try {
      await signIn(setup)
      const list = await expectFrame(setup, "Escolha o caixa")
      expect(list).toContain("Operador: Ana Souza")
      expect(list).toContain("› 01 Caixa principal — livre")
      expect(list).toContain("02 Caixa do fundo — aberto com Maria")

      await act(async () => {
        setup.mockInput.pressArrow("down") // desce para o segundo caixa
      })
      await expectFrame(setup, "› 02")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({
        type: "loginSucceeded",
        operator: OPERADOR,
        register: { id: "r2", name: "Caixa do fundo" },
      })
      // a sessão provisória é revogada antes do login vinculado, e a senha segue com o operador
      expect(calls).toEqual(["login sem caixa", "logout", "login r2"])
      expect(login).toHaveBeenLastCalledWith("ana", SENHA, "r2")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("setas movem a seleção na lista de caixas, em ciclo", async () => {
    const listCashRegisters = mock(
      async (): Promise<CashRegistersOutcome> => ({
        ok: true,
        registers: [CAIXA_01, CAIXA_02, CAIXA_03],
      }),
    )
    const setup = await renderScreen(apiStub({ listCashRegisters }))

    try {
      await signIn(setup)
      await expectFrame(setup, "› 01")

      for (const expected of ["› 02", "› 03", "› 01"]) {
        await act(async () => {
          setup.mockInput.pressArrow("down")
        })
        await expectFrame(setup, expected)
      }

      await act(async () => {
        setup.mockInput.pressArrow("up") // na ponta, sobe para o último
      })
      await expectFrame(setup, "› 03")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("lista vazia avisa e ENTER não navega nem chama a API", async () => {
    const login = mock(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR }))
    const logout = mock(async () => undefined)
    const listCashRegisters = mock(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ login, logout, listCashRegisters }), dispatch)

    try {
      await signIn(setup)
      await expectFrame(setup, "nenhum caixa ativo")

      await act(async () => {
        setup.mockInput.pressArrow("down")
      })
      await act(async () => {
        setup.mockInput.pressEnter()
      })

      expect(setup.captureCharFrame()).toContain("Escolha o caixa")
      expect(dispatch).not.toHaveBeenCalled()
      expect(logout).not.toHaveBeenCalled()
      expect(login).toHaveBeenCalledTimes(1) // só o login sem caixa
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a lista pendente mostra o carregamento", async () => {
    const listCashRegisters = mock(
      () =>
        new Promise<CashRegistersOutcome>(() => {
          // fica pendente de propósito: é o estado de carregando que o teste quer ver
        }),
    )
    const setup = await renderScreen(apiStub({ listCashRegisters }))

    try {
      await signIn(setup)
      await expectFrame(setup, "carregando caixas...")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("campo vazio não chama a API e avisa o operador", async () => {
    const login = mock(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR }))
    const setup = await renderScreen(apiStub({ login }))

    try {
      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "informe usuário e senha")
      expect(login).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("só o usuário preenchido também avisa e não chama a API", async () => {
    const login = mock(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR }))
    const setup = await renderScreen(apiStub({ login }))

    try {
      await act(async () => {
        await setup.mockInput.typeText("ana")
      })
      await expectFrame(setup, "Usuário: ana")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await expectFrame(setup, "informe usuário e senha")
      expect(login).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("credencial inválida relata a recusa e limpa a senha do campo", async () => {
    const login = mock(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: "rejected",
        message: "usuário ou senha inválidos",
      }),
    )
    const listCashRegisters = mock(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ login, listCashRegisters }), dispatch)

    try {
      await signIn(setup)
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({
        type: "loginRejected",
        message: "usuário ou senha inválidos",
      })

      // a senha recusada sai do campo: espera o frame em que o campo já está vazio
      const frame = await setup.waitForFrame(
        (value) => lineWith(value, "Senha:").trim() === "› Senha:",
      )
      expect(lineWith(frame, "Usuário:").trim()).toBe("Usuário: ana")
      expect(frame).not.toContain("••••")
      expect(listCashRegisters).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("recusa do login vinculado volta às credenciais com a senha limpa", async () => {
    let first = true
    const login = mock(
      async (): Promise<LoginOutcome> => {
        if (first) {
          first = false
          return { ok: true, operator: OPERADOR }
        }
        return { ok: false, kind: "rejected", message: "caixa não encontrado ou inativo" }
      },
    )
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub({ login }), dispatch)

    try {
      await signIn(setup)
      await expectFrame(setup, "Escolha o caixa")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({
        type: "loginRejected",
        message: "caixa não encontrado ou inativo",
      })

      const frame = await expectFrame(setup, "Usuário: ana")
      expect(frame).not.toContain("••••") // a senha recusada sai do campo
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o caixa preferido nasce selecionado e o ENTER confirma nele", async () => {
    const dispatch = dispatchSpy()
    const setup = await renderScreen(apiStub(), dispatch, { kind: "login", failure: null }, "r2")

    try {
      await signIn(setup)
      await expectFrame(setup, "› 02")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({
        type: "loginSucceeded",
        operator: OPERADOR,
        register: { id: "r2", name: "Caixa do fundo" },
      })
    } finally {
      setup.renderer.destroy()
    }
  })

  test("BACKSPACE e DEL editam o campo focado", async () => {
    const setup = await renderScreen(apiStub())

    try {
      await act(async () => {
        await setup.mockInput.typeText("ana")
      })
      await expectFrame(setup, "Usuário: ana")

      await act(async () => {
        setup.mockInput.pressBackspace()
      })
      await setup.waitForFrame((frame) => lineWith(frame, "Usuário:").trim() === "› Usuário: an")

      await act(async () => {
        setup.mockInput.pressKey(KeyCodes.DELETE)
      })
      await setup.waitForFrame((frame) => lineWith(frame, "Usuário:").trim() === "› Usuário: a")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a senha digitada não aparece em nenhum frame", async () => {
    const setup = await renderScreen(apiStub())
    const frames: string[] = []

    try {
      await act(async () => {
        await setup.mockInput.typeText("ana")
      })
      await expectFrame(setup, "Usuário: ana")

      await act(async () => {
        setup.mockInput.pressTab()
      })
      await expectFrame(setup, "› Senha:")

      // um frame por tecla: nenhum deles pode conter a senha, nem antes do ENTER
      for (const char of SENHA) {
        await act(async () => {
          setup.mockInput.pressKey(char)
        })
        await setup.renderOnce()
        frames.push(setup.captureCharFrame())
      }

      await expectFrame(setup, `Senha: ${"•".repeat(SENHA.length)}`)
      frames.push(setup.captureCharFrame())

      for (const frame of frames) {
        expect(frame).not.toContain(SENHA)
      }
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o aviso da sessão que caiu aparece na entrada", async () => {
    const notice = "sessão expirada — entre novamente; a venda continua aberta"
    const setup = await renderScreen(apiStub(), undefined, { kind: "login", failure: null, notice })

    try {
      await expectFrame(setup, notice)
    } finally {
      setup.renderer.destroy()
    }
  })
})
