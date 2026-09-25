/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  CancelSaleOutcome,
  CashRegisterOption,
  CashRegistersOutcome,
  CompleteSaleOutcome,
  CreateSaleOutcome,
  CustomerOption,
  CustomerSaleOutcome,
  LoginOutcome,
  SaleReloadOutcome,
  SearchCustomersOutcome,
  TerminalApi,
} from "../api/terminalApi"
import type { ApiProblem, ReceiptView, SaleItemView, SaleView } from "../core/state"
import { App } from "./App"

/**
 * Shell roteador do 1124a/1124b/1125a com o reducer de verdade (não o dublê) e a guarda de problemas
 * (1117) no caminho: o que se testa é a fiação — login → abertura de caixa, erro bloqueante que
 * reconhece e volta, recusa que fica na tela de entrada, 401 que volta ao login com o aviso e o 409
 * de idempotência que relê a venda (1125b) —, nunca o HTTP (esse é do api-client).
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

/** Item que o servidor já tinha gravado quando o 409 chegou (a releitura da reconciliação). */
const ARROZ: SaleItemView = {
  productId: "p1",
  name: "Arroz 5kg",
  unit: "UN",
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
}

/** Venda como o servidor a devolve: os totais seguem as linhas, que é o que ele mandaria (BR-12). */
function saleWith(items: SaleItemView[], customerId: string | null = null): SaleView {
  const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0)

  return {
    id: "sale-1",
    items,
    subtotal,
    discountAmount: 0,
    total: subtotal,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId,
  }
}

/** Cliente que a busca do F6 devolve (1126c): o nome do cabeçalho é esta seleção local. */
const ANA: CustomerOption = { id: "c1", name: "Ana Souza", taxId: "12345678900" }

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

/** ENTER do operador (formulários, escolhas e confirmações). */
async function pressEnter(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** Tecla nomeada (F6, F12, DEL): o `mockInput` emite a sequência do terminal. */
async function pressNamed(setup: Setup, key: string): Promise<void> {
  await act(async () => {
    setup.mockInput.pressKey(key)
  })
}

/** TAB dos formulários (o campo do recebido no pagamento). */
async function pressTab(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressTab()
  })
}

/** Bipe do leitor: a rajada fecha no ENTER, como no spike (1121). */
async function bip(setup: Setup, code: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code)
  })
  await pressEnter(setup)
}

/** Operação de pé: login, caixa escolhido, fundo de troco e a venda aberta no primeiro bipe. */
async function enterSale(setup: Setup): Promise<void> {
  await signIn(setup)
  await expectFrame(setup, "Escolha o caixa")

  await pressEnter(setup)
  await expectFrame(setup, "Abertura de caixa")

  await act(async () => {
    await setup.mockInput.typeText("5000")
  })
  await expectFrame(setup, "Fundo de troco: R$ 50,00")

  await pressEnter(setup)
  await expectFrame(setup, "bipar o primeiro item para iniciar a venda")
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

  test("409 de idempotência relê a venda do servidor e avisa, sem repetir a inclusão", async () => {
    const createSale = mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleWith([]) }))
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: false,
        kind: "failed",
        problem: {
          status: 409,
          code: "IDEMPOTENCY_KEY_REUSED",
          detail: "chave de idempotência já usada com outra requisição",
        },
      }),
    )
    // a releitura: o item que a inclusão já tinha gravado com a chave antiga
    const getSale = mock(async (): Promise<SaleReloadOutcome> => ({ ok: true, sale: saleWith([ARROZ]) }))
    const setup = await renderApp(
      apiStub({
        openCashRegister: mock(async () => ({ ok: true as const, sessionId: "s1" })),
        createSale,
        addSaleItem,
        getSale,
      }),
    )

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
      await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      // o bipe do leitor: a rajada fecha no ENTER e a inclusão esbarra no 409 de idempotência
      await act(async () => {
        await setup.mockInput.typeText("7891000100103")
      })
      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const frame = await expectFrame(
        setup,
        "operação já registrada com outros dados — venda conferida no servidor",
      )

      expect(getSale).toHaveBeenCalledWith("sale-1")
      expect(addSaleItem).toHaveBeenCalledTimes(1) // a inclusão não foi repetida
      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90") // a venda é a que o servidor tem
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("App: cliente e troca de operador (1126c)", () => {
  /** Abertura de caixa e venda pelo bipe, como o 1125b; o resto do dublê fica por conta do teste. */
  function saleStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
    return apiStub({
      openCashRegister: mock(async () => ({ ok: true as const, sessionId: "s1" })),
      createSale: mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleWith([]) })),
      addSaleItem: mock(
        async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleWith([ARROZ]) }),
      ),
      ...overrides,
    })
  }

  test("F6 vincula o cliente e o nome aparece no cabeçalho; o DEL o remove", async () => {
    const searchCustomers = mock(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [ANA] }),
    )
    const linkCustomer = mock(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith([ARROZ], ANA.id) }),
    )
    const unlinkCustomer = mock(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith([ARROZ]) }),
    )
    const setup = await renderApp(saleStub({ searchCustomers, linkCustomer, unlinkCustomer }))

    try {
      await enterSale(setup)
      await bip(setup, "7891000100103")

      const anonymous = await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(anonymous).not.toContain("Cliente:")

      await pressNamed(setup, KeyCodes.F6)

      const opened = await expectFrame(setup, "Cliente na venda (F6)")

      expect(opened).not.toContain("bipar o primeiro item para iniciar a venda") // o corpo saiu de cena

      await act(async () => {
        await setup.mockInput.typeText("ana", 60) // digitação humana: a rajada seria descartada
      })
      await expectFrame(setup, "Busca: ana")
      await pressEnter(setup)
      await expectFrame(setup, "› Ana Souza — 123.456.789-00")

      await pressEnter(setup)

      const linked = await expectFrame(setup, "Cliente: Ana Souza")

      expect(linkCustomer).toHaveBeenCalledWith("sale-1", "c1")
      expect(linked).not.toContain("Cliente na venda (F6)") // o modal saiu de cena

      // o DEL do modal tira o vínculo: a venda volta anônima e o nome sai do cabeçalho
      await pressNamed(setup, KeyCodes.F6)
      await expectFrame(setup, "Cliente atual: Ana Souza")
      await pressNamed(setup, KeyCodes.DELETE)

      const unlinked = await expectFrame(setup, "cliente removido da venda")

      expect(unlinkCustomer).toHaveBeenCalledWith("sale-1")
      expect(unlinked).not.toContain("Cliente: Ana Souza")
      expect(unlinked).toContain("› 1 x Arroz 5kg — R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F12 sem venda encerra a sessão e o login nasce com o caixa atual selecionado", async () => {
    const logout = mock(async () => undefined)
    const setup = await renderApp(saleStub({ logout }))

    try {
      await enterSale(setup)

      // o login vinculado já revogou a sessão provisória (1107): o F12 revoga a de verdade
      const provisional = logout.mock.calls.length

      await pressNamed(setup, KeyCodes.F12)

      const opened = await expectFrame(setup, "Trocar operador (F12)")

      expect(opened).toContain("a sessão de login termina; o caixa continua aberto")
      expect(opened).not.toContain("há venda aberta")

      await pressEnter(setup)

      const login = await expectFrame(setup, "PDV minimercado — entrada do operador")

      expect(logout.mock.calls.length).toBe(provisional + 1)
      expect(login).not.toContain("TOTAL:") // a venda saiu de cena

      // o próximo operador entra no **mesmo** caixa: a lista nasce com o Caixa 01 selecionado
      await typeCredentials(setup)
      await pressEnter(setup)

      const registers = await expectFrame(setup, "› 01 Caixa principal — livre")

      expect(registers).not.toContain("› 02")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F12 com venda aberta bloqueia e o ENTER cancela a venda antes de encerrar a sessão", async () => {
    const order: string[] = []
    const cancelSale = mock(async (): Promise<CancelSaleOutcome> => {
      order.push("cancelSale")
      return { ok: true }
    })
    const logout = mock(async () => {
      order.push("logout")
    })
    const setup = await renderApp(saleStub({ cancelSale, logout }))

    try {
      await enterSale(setup)
      await bip(setup, "7891000100103")
      await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      await pressNamed(setup, KeyCodes.F12)

      const blocked = await expectFrame(
        setup,
        "há venda aberta com 1 item — a venda será cancelada",
      )

      expect(blocked).toContain("ENTER cancela a venda e troca de operador · ESC volta")

      await pressEnter(setup)

      const login = await expectFrame(setup, "PDV minimercado — entrada do operador")

      // a venda é cancelada antes de a sessão terminar: o motivo é fixo e a chave é desta tentativa
      expect(order.slice(-2)).toEqual(["cancelSale", "logout"])
      expect(cancelSale).toHaveBeenCalledWith("sale-1", "troca de operador", expect.any(String))
      expect(login).not.toContain("TOTAL:")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("App: pagamento e sucesso (1127a)", () => {
  /** Resumo que o servidor devolveu no `complete`: o que a tela de sucesso exibe (BR-12). */
  const RECEIPT: ReceiptView = { number: 42, total: 24.9, changeAmount: 5.1 }

  /**
   * Venda com o dinheiro que o servidor aprovou: pago e troco são dele (BR-05/B) — o `24,90` da
   * linha do Arroz e os `5,10` de troco de um recebido de R$ 30,00.
   */
  function paidSale(): SaleView {
    return {
      ...saleWith([ARROZ]),
      paidAmount: 24.9,
      changeAmount: 5.1,
      payments: [
        { id: "pay-1", method: "CASH", amount: 24.9, changeAmount: 5.1, status: "APPROVED" },
      ],
    }
  }

  /** Operação de pé com a venda e a API do pagamento por conta do teste. */
  function paymentStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
    return apiStub({
      openCashRegister: mock(async () => ({ ok: true as const, sessionId: "s1" })),
      createSale: mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleWith([]) })),
      addSaleItem: mock(
        async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleWith([ARROZ]) }),
      ),
      ...overrides,
    })
  }

  /** Digitação humana (60 ms por tecla): a tela de pagamento descarta a rajada do leitor. */
  async function typeHuman(setup: Setup, text: string): Promise<void> {
    await act(async () => {
      await setup.mockInput.typeText(text, 60)
    })
  }

  test("F9 abre o pagamento, o ENTER registra o dinheiro e o F9 conclui com o troco no sucesso", async () => {
    const addPayment = mock(async (): Promise<AddPaymentOutcome> => ({ ok: true, sale: paidSale() }))
    const completeSale = mock(
      async (): Promise<CompleteSaleOutcome> => ({ ok: true, receipt: RECEIPT }),
    )
    const setup = await renderApp(paymentStub({ addPayment, completeSale }))

    try {
      await enterSale(setup)
      await bip(setup, "7891000100103")
      await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      await pressNamed(setup, KeyCodes.F9)

      const paying = await expectFrame(setup, "Pagamento (F9)")

      expect(paying).not.toContain("Código: ") // o corpo da venda saiu de cena
      expect(paying).toContain("Método: [DINHEIRO]")
      expect(paying).toContain("Pago: R$ 0,00 de R$ 24,90")

      await typeHuman(setup, "2490")
      await expectFrame(setup, "› Valor: R$ 24,90")

      // o recebido é do dinheiro: TAB alcança o campo e o corpo leva o `tenderedAmount` (BR-05)
      await pressTab(setup)
      await expectFrame(setup, "› Recebido: R$ 0,00")
      await typeHuman(setup, "3000")
      await expectFrame(setup, "› Recebido: R$ 30,00")
      await pressEnter(setup)

      await expectFrame(setup, "Pago: R$ 24,90 de R$ 24,90")

      expect(addPayment).toHaveBeenCalledWith(
        "sale-1",
        { method: "CASH", amount: 24.9, tenderedAmount: 30 },
        expect.any(String),
      )

      await pressNamed(setup, KeyCodes.F9)

      const success = await expectFrame(setup, "Venda 42 concluída")

      expect(completeSale).toHaveBeenCalledWith("sale-1", expect.any(String))
      expect(success).toContain("TOTAL: R$ 24,90")
      expect(success).toContain("TROCO: R$ 5,10") // o troco do servidor em destaque (BR-05)

      await pressEnter(setup)

      const next = await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      expect(next).not.toContain("Venda 42 concluída") // a próxima venda começou vazia
      expect(next).toContain("TOTAL: R$ 0,00")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o ENTER do sucesso começa a próxima venda e esquece o cliente da venda que fechou", async () => {
    const searchCustomers = mock(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [ANA] }),
    )
    const linkCustomer = mock(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith([ARROZ], ANA.id) }),
    )
    const completeSale = mock(
      async (): Promise<CompleteSaleOutcome> => ({ ok: true, receipt: RECEIPT }),
    )
    const setup = await renderApp(paymentStub({ searchCustomers, linkCustomer, completeSale }))

    try {
      await enterSale(setup)
      await bip(setup, "7891000100103")
      await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      await pressNamed(setup, KeyCodes.F6)
      await expectFrame(setup, "Cliente na venda (F6)")
      await act(async () => {
        await setup.mockInput.typeText("ana", 60) // digitação humana: a rajada seria descartada
      })
      await expectFrame(setup, "Busca: ana")
      await pressEnter(setup)
      await expectFrame(setup, "› Ana Souza — 123.456.789-00")
      await pressEnter(setup)

      const linked = await expectFrame(setup, "Cliente: Ana Souza")

      expect(linked).toContain("› 1 x Arroz 5kg — R$ 24,90")

      await pressNamed(setup, KeyCodes.F9)
      await expectFrame(setup, "Pagamento (F9)")
      await pressNamed(setup, KeyCodes.F9)
      await expectFrame(setup, "Venda 42 concluída")
      await pressEnter(setup)

      const next = await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      expect(next).not.toContain("Cliente: Ana Souza") // a anotação era da venda que fechou
      expect(next).not.toContain("› 1 x Arroz 5kg — R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("com o pagamento aberto a rajada do leitor não vira item nem pagamento", async () => {
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleWith([ARROZ]) }),
    )
    const addPayment = mock(
      async (): Promise<AddPaymentOutcome> => ({
        ok: false,
        kind: "rejected",
        message: "valor acima do que falta na venda — ajuste o valor",
      }),
    )
    const setup = await renderApp(paymentStub({ addSaleItem, addPayment }))

    try {
      await enterSale(setup)
      await bip(setup, "7891000100103")
      await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(addSaleItem).toHaveBeenCalledTimes(1)

      await pressNamed(setup, KeyCodes.F9)
      await expectFrame(setup, "Pagamento (F9)")

      // o leitor continua bipando: a rajada fecha no terminador colado nela, como no spike (1121)
      await act(async () => {
        await setup.mockInput.typeText("7891000100103")
        setup.mockInput.pressEnter()
      })
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 60))
      })

      const frame = await expectFrame(setup, "Pagamento (F9)")

      expect(addSaleItem).toHaveBeenCalledTimes(1) // nenhum item novo na venda
      expect(addPayment).not.toHaveBeenCalled() // e nenhum pagamento registrado
      expect(frame).toContain("Pago: R$ 0,00 de R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })
})
