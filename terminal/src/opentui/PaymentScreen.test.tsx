/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act, useReducer } from "react"

import type {
  AddPaymentOutcome,
  CompleteSaleOutcome,
  SendFailure,
  TerminalApi,
} from "../api/terminalApi"
import type { Action } from "../core/reducer"
import { reduce } from "../core/reducer"
import type {
  ApiProblem,
  PaymentMethod,
  PaymentView,
  PayingState,
  ReceiptView,
  SaleItemView,
  SaleView,
} from "../core/state"
import { PaymentScreen } from "./PaymentScreen"
import { SaleSuccessScreen } from "./SaleSuccessScreen"

/**
 * Pagamento e sucesso (1127a): a tela de pagamento da UI nova portada da Ink (1113) com o reducer
 * real (1103) por trás e a camada de API dublada com `mock` — o que se testa é a operação da tela,
 * nunca o HTTP (esse é do api-client).
 *
 * O harness faz o papel do shell: em `paying` desenha o `PaymentScreen`; em `saleOpen` com
 * `receipt` desenha a tela de sucesso; sem `receipt`, um marcador de texto diz que a venda voltou
 * (o corpo da venda de verdade é do `SaleScreen`, testado no arquivo dele). É por ele que se
 * prova que o pagamento parcial **não sai da tela**, que o ESC volta à venda intacta e que o F9
 * conclui levando o resumo do servidor para o sucesso.
 */

const OPERADOR = { id: "u1", name: "Ana Souza" }
const CAIXA = { id: "r1", name: "Caixa 01" }
const SALE_ID = "sale-1"

/** Código do bipe: a rajada do leitor que não pode virar valor nem registro com o pagamento aberto. */
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

/** Item da venda: a linha de R$ 25,00 deixa o troco e o parcial em números redondos. */
const ARROZ: SaleItemView = {
  productId: "p1",
  name: "Arroz 5kg",
  unit: "UN",
  quantity: 1,
  unitPrice: 25,
  lineTotal: 25,
}

/** Venda do servidor com os totais que o caso precisa (BR-12: nada é calculado na TUI). */
function saleOf(totals: Partial<SaleView> = {}): SaleView {
  return {
    id: SALE_ID,
    items: [ARROZ],
    subtotal: 25,
    discountAmount: 0,
    total: 25,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId: null,
    ...totals,
  }
}

/** Pagamento como o servidor o devolveu; o troco é dele (BR-05). */
function paymentOf(
  method: PaymentMethod,
  amount: number,
  changeAmount = 0,
  id = `pay-${method}-${amount}`,
): PaymentView {
  return { id, method, amount, changeAmount, status: "APPROVED" }
}

/** Estado do reducer como o `paymentStarted` o entrega à tela (1103). */
function payingState(sale: SaleView = saleOf()): PayingState {
  return {
    kind: "paying",
    operator: OPERADOR,
    register: CAIXA,
    sessionId: "s1",
    sale,
  }
}

/** Dublê da camada de API: só o que o pagamento usa importa; o resto existe para satisfazer o tipo. */
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
    createSale: mock(async (): Promise<{ ok: true; sale: SaleView }> => ({ ok: true, sale: saleOf() })),
    getSale: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    addSaleItem: mock(async (): Promise<{ ok: true; sale: SaleView }> => ({ ok: true, sale: saleOf() })),
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

/**
 * Shell mínimo do teste, como o `App` faz (1127a): o reducer real por trás. Em `paying` a tela de
 * pagamento; com o `receipt` no estado, a tela de sucesso no lugar da venda; de volta na venda (o
 * ESC), um marcador diz que ela voltou intacta.
 */
function PaymentHarness({
  api,
  initial,
  onAction,
  onCompleted,
}: {
  api: TerminalApi
  initial: PayingState
  /** Ouve o que a tela despacha (o bipe que não pode aparecer, o cancelamento do ESC). */
  onAction?: (action: Action) => void
  /** Aviso de venda concluída (o shell esquece o cliente da venda que fechou). */
  onCompleted?: () => void
}) {
  const [state, dispatch] = useReducer(reduce, initial)

  if (state.kind === "paying") {
    return (
      <PaymentScreen
        state={state}
        api={api}
        dispatch={(action) => {
          onAction?.(action)
          dispatch(action)
        }}
        onCompleted={onCompleted ?? (() => {})}
      />
    )
  }

  if (state.kind === "saleOpen") {
    if (state.receipt !== null) {
      return <SaleSuccessScreen receipt={state.receipt} dispatch={dispatch} />
    }

    return <text>{state.sale === null ? "venda nova" : "venda intacta"}</text>
  }

  throw new Error(`estado inesperado no harness do pagamento: ${state.kind}`)
}

function renderHarness(
  api: TerminalApi,
  initial: PayingState = payingState(),
  onAction?: (action: Action) => void,
  onCompleted?: () => void,
) {
  return testRender(
    <PaymentHarness api={api} initial={initial} onAction={onAction} onCompleted={onCompleted} />,
    { width: 80, height: 24 },
  )
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** ENTER do operador. */
async function pressEnter(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** TAB: valor ↔ recebido (o recebido só existe no dinheiro). */
async function pressTab(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressTab()
  })
}

/** ←/→ do seletor de forma de pagamento. */
async function pressArrow(setup: Setup, direction: "left" | "right"): Promise<void> {
  await act(async () => {
    setup.mockInput.pressArrow(direction)
  })
}

/** Tecla nomeada (F9): o `mockInput` emite a sequência do terminal. */
async function pressNamed(setup: Setup, key: string): Promise<void> {
  await act(async () => {
    setup.mockInput.pressKey(key)
  })
}

/** ESC: o parser segura a tecla sozinha por ~20 ms (ambiguidade com sequências), achado do spike. */
async function pressEscape(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressEscape()
  })
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50))
  })
}

/** Digitação humana: 60 ms por tecla — a tela descarta a rajada (caracteres em < 50 ms). */
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

/** Pausa do teste: o guard da rajada mede o intervalo entre os caracteres. */
async function waitMs(ms: number): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

/** Espera o efeito assíncrono aparecer no dublê (mesma espera ativa das outras telas). */
async function until(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (predicate()) {
      return
    }

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 2))
    })
  }

  throw new Error("condição não satisfeita no tempo do teste")
}

describe("PaymentScreen: pagamento parcial e múltiplo (1127a)", () => {
  test("pagamento parcial: registra forma e valor e continua no pagamento com o pago/total do servidor", async () => {
    const addPayment = mock(
      async (): Promise<AddPaymentOutcome> => ({
        ok: true,
        sale: saleOf({ paidAmount: 10, payments: [paymentOf("CASH", 10)] }),
      }),
    )
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      const opened = await expectFrame(setup, "Pagamento (F9)")

      expect(opened).toContain("Método: [DINHEIRO] · PIX · DÉBITO · CRÉDITO · VOUCHER")
      expect(opened).toContain("Pago: R$ 0,00 de R$ 25,00")
      expect(opened).toContain("nenhum pagamento registrado")

      await typeHuman(setup, "1000")
      await expectFrame(setup, "› Valor: R$ 10,00")

      await pressEnter(setup)

      const frame = await expectFrame(setup, "Pago: R$ 10,00 de R$ 25,00")

      expect(addPayment).toHaveBeenCalledWith(
        SALE_ID,
        { method: "CASH", amount: 10 },
        expect.any(String),
      )
      expect(frame).toContain("Pagamento (F9)") // registrou e continua no pagamento
      expect(frame).toContain("registrado: DINHEIRO R$ 10,00")
      expect(frame).toContain("1. DINHEIRO — R$ 10,00")
      expect(frame).toContain("› Valor: R$ 0,00") // campo limpo para o próximo pagamento
    } finally {
      setup.renderer.destroy()
    }
  })

  test("múltiplos pagamentos: dois registros na lista e os totais somados pelo servidor", async () => {
    let attempts = 0
    const addPayment = mock<TerminalApi["addPayment"]>(async () => {
      attempts += 1

      return attempts === 1
        ? { ok: true, sale: saleOf({ paidAmount: 10, payments: [paymentOf("CASH", 10)] }) }
        : {
            ok: true,
            sale: saleOf({
              paidAmount: 25,
              payments: [paymentOf("CASH", 10), paymentOf("PIX", 15, 0, "pay-2")],
            }),
          }
    })
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await typeHuman(setup, "1000")
      await pressEnter(setup)
      await expectFrame(setup, "Pago: R$ 10,00 de R$ 25,00")

      // o segundo pagamento troca a forma: PIX
      await pressArrow(setup, "right")
      await expectFrame(setup, "Método: DINHEIRO · [PIX]")

      await typeHuman(setup, "1500")
      await expectFrame(setup, "› Valor: R$ 15,00")
      await pressEnter(setup)

      const frame = await expectFrame(setup, "Pago: R$ 25,00 de R$ 25,00")

      expect(frame).toContain("1. DINHEIRO — R$ 10,00")
      expect(frame).toContain("2. PIX — R$ 15,00")
      expect(addPayment).toHaveBeenCalledTimes(2)
      expect(addPayment.mock.calls[1]?.[1]).toEqual({ method: "PIX", amount: 15 })
    } finally {
      setup.renderer.destroy()
    }
  })

  test("DINHEIRO com recebido: o `tenderedAmount` vai no corpo e o troco do servidor fica em destaque", async () => {
    const addPayment = mock(
      async (): Promise<AddPaymentOutcome> => ({
        ok: true,
        sale: saleOf({
          paidAmount: 10,
          changeAmount: 10,
          payments: [paymentOf("CASH", 10, 10)],
        }),
      }),
    )
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      await expectFrame(setup, "Pagamento (F9)")
      await expectFrame(setup, "› Valor: R$ 0,00")

      await typeHuman(setup, "1000")
      await pressTab(setup)
      await expectFrame(setup, "› Recebido: R$ 0,00")

      await typeHuman(setup, "2000")
      await expectFrame(setup, "› Recebido: R$ 20,00")

      await pressEnter(setup)

      const frame = await expectFrame(setup, "TROCO: R$ 10,00")

      expect(addPayment).toHaveBeenCalledWith(
        SALE_ID,
        { method: "CASH", amount: 10, tenderedAmount: 20 },
        expect.any(String),
      )
      expect(frame).toContain("1. DINHEIRO — R$ 10,00 · troco R$ 10,00")
      expect(frame).toContain("Pago: R$ 10,00 de R$ 25,00")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o recebido só existe no DINHEIRO: fora dele o campo sai de cena e o TAB não o alcança", async () => {
    const setup = await renderHarness(apiStub())

    try {
      await expectFrame(setup, "Recebido: R$ 0,00")

      await pressArrow(setup, "right")

      const frame = await expectFrame(setup, "Método: DINHEIRO · [PIX]")

      expect(frame).not.toContain("Recebido:")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("PaymentScreen: conclusão e tela de sucesso (1127a)", () => {
  const RECEIPT: ReceiptView = { number: 42, total: 25, changeAmount: 7.5 }

  test("F9 conclui com a chave e o receipt vira a tela de sucesso com o troco", async () => {
    const addPayment = mock(
      async (): Promise<AddPaymentOutcome> => ({
        ok: true,
        sale: saleOf({ paidAmount: 10, payments: [paymentOf("CASH", 10)] }),
      }),
    )
    const completeSale = mock(async (): Promise<CompleteSaleOutcome> => ({ ok: true, receipt: RECEIPT }))
    const onCompleted = mock(() => {})
    const setup = await renderHarness(apiStub({ addPayment, completeSale }), payingState(), undefined, onCompleted)

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await typeHuman(setup, "1000")
      await pressEnter(setup)
      await expectFrame(setup, "Pago: R$ 10,00 de R$ 25,00")

      await pressNamed(setup, KeyCodes.F9)

      const frame = await expectFrame(setup, "Venda 42 concluída")

      expect(completeSale).toHaveBeenCalledWith(SALE_ID, expect.any(String))
      expect(onCompleted).toHaveBeenCalledTimes(1)
      expect(frame).toContain("TOTAL: R$ 25,00")
      expect(frame).toContain("TROCO: R$ 7,50") // o troco do servidor em destaque (BR-05)
      expect(frame).toContain("ENTER inicia a próxima venda")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F9 repetido depois de uma falha transitória reusa a chave da conclusão", async () => {
    let attempts = 0
    const completeSale = mock<TerminalApi["completeSale"]>(async () => {
      attempts += 1

      return attempts === 1 ? sendFailure() : { ok: true, receipt: RECEIPT }
    })
    const setup = await renderHarness(apiStub({ completeSale }))

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await pressNamed(setup, KeyCodes.F9)

      const failed = await expectFrame(setup, "falha ao concluir — F9 tenta de novo")

      expect(failed).toContain("Pagamento (F9)") // não saiu do pagamento
      expect(completeSale).toHaveBeenCalledTimes(1)

      await pressNamed(setup, KeyCodes.F9)
      await expectFrame(setup, "Venda 42 concluída")

      // a tentativa repetida é o mesmo fechamento: repetir a chave evita uma segunda baixa (§8)
      expect(completeSale).toHaveBeenCalledTimes(2)
      expect(completeSale.mock.calls[1]?.[1]).toBe(completeSale.mock.calls[0]?.[1])
    } finally {
      setup.renderer.destroy()
    }
  })

  test("recusa do servidor fica na tela com o digitado e não avança", async () => {
    const addPayment = mock<TerminalApi["addPayment"]>(async () => ({
      ok: false,
      kind: "rejected",
      message: "valor acima do que falta na venda — ajuste o valor",
    }))
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await typeHuman(setup, "3000")
      await pressEnter(setup)

      const refused = await expectFrame(setup, "valor acima do que falta na venda — ajuste o valor")

      expect(refused).toContain("Pagamento (F9)") // a recusa não sai da tela
      expect(refused).toContain("Valor: R$ 30,00") // o digitado não se perde
      expect(refused).toContain("Pago: R$ 0,00 de R$ 25,00") // nada avançou
      expect(addPayment).toHaveBeenCalledTimes(1)

      // recusa não é gravada no servidor: a próxima tentativa é outra operação, com chave nova
      await pressEnter(setup)
      await until(() => addPayment.mock.calls.length === 2)
      expect(addPayment.mock.calls[1]?.[2]).not.toBe(addPayment.mock.calls[0]?.[2])
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("PaymentScreen: chaves de idempotência e retry (1127a)", () => {
  test("falha transitória reusa a chave do pagamento e o ENTER refaz", async () => {
    let attempts = 0
    const addPayment = mock<TerminalApi["addPayment"]>(async () => {
      attempts += 1

      return attempts === 1
        ? sendFailure()
        : { ok: true, sale: saleOf({ paidAmount: 10, payments: [paymentOf("CASH", 10)] }) }
    })
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await typeHuman(setup, "1000")
      await pressEnter(setup)
      await expectFrame(setup, "falha ao registrar o pagamento — ENTER tenta de novo")

      expect(addPayment).toHaveBeenCalledTimes(1) // sem retry automático

      await pressEnter(setup)
      await expectFrame(setup, "Pago: R$ 10,00 de R$ 25,00")

      // a tentativa repetida é o mesmo pagamento: a mesma chave vira replay, não um segundo (§8)
      expect(addPayment).toHaveBeenCalledTimes(2)
      expect(addPayment.mock.calls[1]?.[2]).toBe(addPayment.mock.calls[0]?.[2])
    } finally {
      setup.renderer.destroy()
    }
  })

  test("mudar o pedido troca a chave do pagamento em curso", async () => {
    const addPayment = mock<TerminalApi["addPayment"]>(async () => sendFailure())
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await typeHuman(setup, "1000")
      await pressEnter(setup)
      await expectFrame(setup, "falha ao registrar o pagamento — ENTER tenta de novo")

      // o operador corrige o valor: a chave do retry anterior não vale mais para este corpo
      await typeHuman(setup, "5")
      await pressEnter(setup)
      await until(() => addPayment.mock.calls.length === 2)
      expect(addPayment.mock.calls[1]?.[1]).toEqual({ method: "CASH", amount: 100.05 })
      expect(addPayment.mock.calls[1]?.[2]).not.toBe(addPayment.mock.calls[0]?.[2])
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("PaymentScreen: ESC e rajada do leitor (1127a)", () => {
  test("ESC volta à venda intacta sem chamar a API", async () => {
    const addPayment = mock(async (): Promise<AddPaymentOutcome> => sendFailure())
    const setup = await renderHarness(apiStub({ addPayment }))

    try {
      await expectFrame(setup, "Pagamento (F9)")
      await typeHuman(setup, "1000")

      await pressEscape(setup)

      const back = await expectFrame(setup, "venda intacta")

      expect(back).not.toContain("Pagamento (F9)")
      expect(addPayment).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a rajada do leitor não registra pagamento nem vira bipe com o pagamento aberto", async () => {
    const addPayment = mock(async (): Promise<AddPaymentOutcome> => sendFailure())
    const actions: Action[] = []
    const setup = await renderHarness(apiStub({ addPayment }), payingState(), (action) => {
      actions.push(action)
    })

    try {
      await expectFrame(setup, "Pagamento (F9)")

      await scanReader(setup, BARCODE)
      await waitMs(60)

      const frame = await expectFrame(setup, "Pagamento (F9)")

      expect(addPayment).not.toHaveBeenCalled() // o terminador do bipe não registra nada
      // o 1º caractere da rajada é ambíguo (o mesmo achado do scanner): o resto é descartado
      expect(frame).toContain("› Valor: R$ 0,07")
      expect(actions.some((action) => action.type === "barcodeScanned")).toBe(false)
    } finally {
      setup.renderer.destroy()
    }
  })
})
