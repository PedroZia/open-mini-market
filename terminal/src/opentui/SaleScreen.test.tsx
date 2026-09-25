/** @jsxImportSource @opentui/react */
import { describe, expect, mock, spyOn, test } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act, useReducer } from "react"

import type {
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CreateSaleOutcome,
  CustomerOption,
  ProductOption,
  ProductStockOutcome,
  SearchProductsOutcome,
  SendFailure,
  SaleItemIntent,
  SaleItemMutationOutcome,
  TerminalApi,
} from "../api/terminalApi"
import type { Action } from "../core/reducer"
import { reduce } from "../core/reducer"
import type { ApiProblem, SaleItemView, SaleOpenState, SaleView } from "../core/state"
import { SaleScreen } from "./SaleScreen"

/**
 * Tela de venda: o layout do 1125a (o quadro em 80×24 e 120×40, a janela que segue a seleção, os
 * totais que vêm do `state.sale` — BR-12 — e a hora que entra por prop), a operação do 1125b — o
 * bipe do leitor, a leitura digitada no campo, o multiplicador `3*`, o 404/422 com aviso, a falha
 * transitória com retry no ENTER e o bell —, a quantidade do item selecionado do 1125c (`+`/`-`
 * com `PATCH` absoluto, passo de `UN`/`KG`, uma mutação por vez e o aviso do DEL no limite) e os
 * modais do 1126a/1126b (F1, DEL/F3, F2 e F5 no `ModalFrame`, com o leitor desligado).
 *
 * O harness tem o reducer real (1103) por trás da tela, como o shell, e a camada de API dublada com
 * `mock` (o runner do `src/opentui` é o `bun:test`): o que se testa é a operação da tela, nunca o
 * HTTP (esse é do api-client).
 */

/** Hora local fixa: o cabeçalho mostra `14:32:05` em qualquer fuso (o `getHours` é local). */
const NOW = new Date(2026, 8, 25, 14, 32, 5)

const OPERADOR = { id: "u1", name: "Ana Souza" }
const CAIXA = { id: "r1", name: "Caixa 01" }

/** Venda que o `createSale` abre; o id é o mesmo que o `addSaleItem` recebe. */
const SALE_ID = "sale-1"
/** Código conhecido do bipe e o código que o servidor não resolve (404). */
const BARCODE = "7891000100103"
const MISSING = "7899999999999"

/** Produto que o servidor devolveu para o bipe; os valores são os dele, a tela só exibe (BR-12). */
const ARROZ: SaleItemView = {
  productId: "p1",
  name: "Arroz 5kg",
  unit: "UN",
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
}

/** O mesmo produto com o multiplicador `3*` aplicado pelo servidor. */
const ARROZ_3: SaleItemView = { ...ARROZ, quantity: 3, lineTotal: 74.7 }

/** Segundo produto, para provar que a fila do bipe andou depois da mutação (1125c). */
const FEIJAO: SaleItemView = {
  productId: "p2",
  name: "Feijão 1kg",
  unit: "UN",
  quantity: 1,
  unitPrice: 8.9,
  lineTotal: 8.9,
}

/** Banana a granel (`KG`): o `+`/`-` nela anda de 0,1, não de 1 (1125c). */
const BANANA: SaleItemView = {
  productId: "p3",
  name: "Banana prata",
  unit: "KG",
  quantity: 1.2,
  unitPrice: 5.99,
  lineTotal: 7.19,
}

/** Texto do campo de leitura vazio (o placeholder do `<input>`), como o operador o vê. */
const MANUAL_PLACEHOLDER = "bipe ou digite o código e ENTER"

/** Problema que nenhum fluxo deste teste usa — os demais métodos só fecham o contrato. */
const API_PROBLEM: ApiProblem = { status: 0, code: null, detail: "não usado neste teste" }

/** Falha de envio do contrato (rede/5xx), como o `SendFailure` das telas de venda. */
function sendFailure(): SendFailure {
  return { ok: false, kind: "retryable", problem: API_PROBLEM }
}

/** Estado da operação como o reducer o entrega à tela, com a venda que o servidor devolveu. */
function stateWith(sale: SaleView | null, extra: Partial<SaleOpenState> = {}): SaleOpenState {
  return {
    kind: "saleOpen",
    operator: OPERADOR,
    register: CAIXA,
    sessionId: "s1",
    sale,
    pendingScan: null,
    receipt: null,
    ...extra,
  }
}

/** Itens como o servidor os devolveu: o produto `n` vale `n` reais e cabe numa linha. */
function farmItems(count: number): SaleItemView[] {
  return Array.from({ length: count }, (_, index) => {
    const number = index + 1

    return {
      productId: `p${number}`,
      name: `Produto ${String(number).padStart(2, "0")}`,
      unit: "UN",
      quantity: 1,
      unitPrice: number,
      lineTotal: number,
    }
  })
}

/**
 * Venda do servidor: por padrão os totais seguem as linhas, que é o que ele devolveria; os testes
 * que querem provar que a tela exibe o total dele passam valores próprios (BR-12).
 */
function saleOf(items: SaleItemView[], totals: Partial<SaleView> = {}): SaleView {
  const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0)

  return {
    id: SALE_ID,
    items,
    subtotal,
    discountAmount: 0,
    total: subtotal,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId: null,
    ...totals,
  }
}

/** Dublê da camada de API: só o que a venda usa importa; o resto existe para satisfazer o tipo. */
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
    createSale: mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleOf([]) })),
    getSale: mock(async () => ({ ok: false as const, problem: API_PROBLEM })),
    addSaleItem: mock(async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ]) })),
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

type RenderOptions = {
  width?: number
  height?: number
  customer?: CustomerOption | null
  store?: string | null
  online?: boolean
  /** Dublê da API quando o teste precisa observar as chamadas dele (os modais do 1126b). */
  api?: TerminalApi
  /** `dispatch` observável: prova o que a tela relatou ao reducer sem trocar de tela. */
  dispatch?: (action: Action) => void
}

/** Tela pura do 1125a: o estado entra pronto e o `dispatch` é um dublê sem reducer. */
function renderSale(state: SaleOpenState, options: RenderOptions = {}) {
  return testRender(
    <SaleScreen
      state={state}
      api={options.api ?? apiStub()}
      dispatch={options.dispatch ?? mock(() => {})}
      now={NOW}
      customer={options.customer ?? null}
      store={options.store ?? null}
      online={options.online ?? true}
    />,
    { width: options.width ?? 80, height: options.height ?? 24 },
  )
}

/**
 * Shell mínimo do teste (como o App faz): o reducer real (1103) por trás da tela. O `onAction` ouve
 * o que a tela despacha — é por ele que o teste prova `saleUpdated` e `scanDismissed`.
 */
function SaleHarness({
  api,
  initial,
  onAction,
}: {
  api: TerminalApi
  initial: SaleOpenState
  onAction?: (action: Action) => void
}) {
  const [state, dispatch] = useReducer(reduce, initial)

  if (state.kind !== "saleOpen") {
    throw new Error(`estado inesperado no harness da venda: ${state.kind}`)
  }

  return (
    <SaleScreen
      state={state}
      api={api}
      dispatch={(action) => {
        onAction?.(action)
        dispatch(action)
      }}
      now={NOW}
      customer={null}
      store={null}
      online
    />
  )
}

/** Venda abrindo a operação de verdade: reducer real + API dublada, em 80×24 como o alvo. */
function renderHarness(
  api: TerminalApi,
  initial: SaleOpenState = stateWith(null),
  onAction?: (action: Action) => void,
) {
  return testRender(<SaleHarness api={api} initial={initial} onAction={onAction} />, {
    width: 80,
    height: 24,
  })
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** Linhas do quadro, sem o padding da grade: a largura da linha é a do conteúdo visível. */
function frameLines(frame: string): string[] {
  const lines = frame.split("\n")

  if (lines.at(-1) === "") {
    lines.pop()
  }

  return lines.map((line) => line.trimEnd())
}

/** Aceite de layout: o quadro cabe no terminal e nenhuma linha passa das colunas dele. */
function expectLayout(frame: string, width: number, height: number): void {
  const lines = frameLines(frame)
  expect(lines.length).toBeLessThanOrEqual(height)

  for (const line of lines) {
    expect(line.length).toBeLessThanOrEqual(width)
  }
}

/** Linhas destacadas da lista: as que começam com o marcador do item selecionado. */
function highlighted(frame: string): string[] {
  return frameLines(frame).filter((line) => line.startsWith("› "))
}

/** O item destacado do quadro; a barra de rolagem pode encostar na linha, então a asserção é parcial. */
function selectedLine(frame: string): string {
  const marked = highlighted(frame)
  expect(marked).toHaveLength(1)
  return marked[0] ?? ""
}

/** Linhas da lista, na ordem do quadro: cada item é uma linha e o resto da tela tem rótulo. */
function itemLines(frame: string): string[] {
  return frameLines(frame).filter((line) => line.includes(" x Produto "))
}

async function pressArrow(setup: Setup, direction: "up" | "down"): Promise<void> {
  await act(async () => {
    setup.mockInput.pressArrow(direction)
  })
}

/** `+`/`-` da quantidade (1125c): a tecla do mapa que o `onKey` da tela consome. */
async function pressQuantity(setup: Setup, key: "+" | "-"): Promise<void> {
  await act(async () => {
    setup.mockInput.pressKey(key)
  })
}

/** Rajada do leitor: os caracteres chegam colados e o `\r` fecha o bipe, como no spike (1121). */
async function scanReader(setup: Setup, code: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code)
  })
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** Leitura manual: o operador digita no campo a 60 ms por tecla (humano) e fecha no ENTER. */
async function scanManual(setup: Setup, code: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(code, 60)
  })
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** Tecla nomeada (DEL, F1, F3...): o `mockInput` do `testRender` emite a sequência do terminal. */
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

/** ENTER do operador: o mesmo `pressEnter` do parser, usado pelos formulários dos modais. */
async function pressEnter(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** TAB: troca o campo em foco no modal de desconto (valor → motivo). */
async function pressTab(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressTab()
  })
}

/**
 * Digitação humana: 60 ms por tecla, como o `scanManual` (1125b). O modal de desconto descarta a
 * rajada do leitor (caracteres em < 50 ms, 1126b), então o teste digita na velocidade do operador.
 */
async function typeHuman(setup: Setup, text: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(text, 60)
  })
}

/** `dispatch` observável: prova o que a tela relatou ao reducer sem o reducer trocar de tela. */
function dispatchSpy() {
  return mock((action: Action) => {
    void action
  })
}

/** Espera o efeito assíncrono aparecer no dublê (mesma espera ativa do teste da abertura de caixa). */
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

/** Pausa do teste: o guard da rajada (1126b) mede o intervalo entre os caracteres. */
async function waitMs(ms: number): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

/**
 * O mapa do §11.3 como o operador o lê na ajuda (1126a): as mesmas linhas do `HelpModal` portado
 * da Ink — tecla e descrição, na ordem da tela.
 */
const HELP_LINES: ReadonlyArray<readonly [string, string]> = [
  ["F1", "esta ajuda"],
  ["F2", "consulta de preço e estoque, sem vender"],
  ["F3", "cancela o item selecionado"],
  ["F4", "cancela a venda em andamento"],
  ["F5", "desconto na venda (valor, percentual e motivo)"],
  ["F6", "cliente na venda (busca por nome ou CPF)"],
  ["F7", "sangria: retira dinheiro da gaveta"],
  ["F8", "suprimento: coloca dinheiro na gaveta"],
  ["F9", "pagamento e conclusão da venda"],
  ["F10", "fechamento do caixa"],
  ["F11", "autoteste do leitor de código de barras"],
  ["F12", "troca o operador do caixa"],
  ["ENTER", "confirma o bipe, a escolha na lista e a próxima venda"],
  ["ESC", "fecha o modal e volta para a venda"],
  ["↑ ↓", "navega nos itens da venda e nas listas"],
  ["+ -", "altera a quantidade do item selecionado"],
  ["DEL", "remove o item selecionado (com confirmação)"],
]

describe("SaleScreen: quadro e estado vazio (1125a)", () => {
  test("sem venda: zeros de exibição, convite ao primeiro bipe e o quadro em 80×24", async () => {
    const setup = await renderSale(stateWith(null))

    try {
      const frame = await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      expect(frame).toContain("PDV minimercado · Caixa 01")
      expect(frame).toContain("Operador: Ana Souza · 14:32:05")
      expect(frame).toContain("Subtotal: R$ 0,00")
      expect(frame).toContain("Desconto: R$ 0,00")
      expect(frame).toContain("TOTAL: R$ 0,00")
      expect(highlighted(frame)).toEqual([])
      expectLayout(frame, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a barra de status mostra conexão, caixa/operador/hora e os atalhos", async () => {
    const online = await renderSale(stateWith(null))

    try {
      const frame = await expectFrame(online, "Conexão: conectado · Caixa 01 · Ana Souza · 14:32:05")

      expect(frame).toContain("F1 Ajuda")
      expect(frame).toContain("F11 Autoteste do leitor")
      expect(frame).toContain("F12 Trocar operador")
      expect(frame).toContain("↑↓ itens")
      expectLayout(frame, 80, 24)
    } finally {
      online.renderer.destroy()
    }
  })

  test("sem conexão a barra acusa SEM CONEXÃO", async () => {
    const setup = await renderSale(stateWith(null), { online: false })

    try {
      expect(await expectFrame(setup, "Conexão: SEM CONEXÃO")).toContain("SEM CONEXÃO")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("SaleScreen: lista e janela rolante (1125a)", () => {
  test("com 20 itens a janela mostra os últimos 10 e conta os de cima", async () => {
    const setup = await renderSale(stateWith(saleOf(farmItems(20))))

    try {
      const frame = await expectFrame(setup, "› 1 x Produto 20 — R$ 20,00")

      expect(frame).toContain("… 10 itens acima")
      expect(itemLines(frame)).toHaveLength(10)
      expect(frame).toContain("Produto 11") // o primeiro da janela
      expect(frame).not.toContain("Produto 10") // este ficou acima dela
      expect(selectedLine(frame)).toContain("› 1 x Produto 20 — R$ 20,00")
      expectLayout(frame, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("em 120×40 a janela cresce e a venda de 20 itens cabe inteira", async () => {
    const setup = await renderSale(stateWith(saleOf(farmItems(20))), { width: 120, height: 40 })

    try {
      const frame = await expectFrame(setup, "› 1 x Produto 20 — R$ 20,00")

      expect(frame).toContain("Produto 01")
      expect(frame).not.toContain("itens acima")
      expect(itemLines(frame)).toHaveLength(20)
      expectLayout(frame, 120, 40)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("as setas movem a seleção com clamp e a janela segue o item selecionado", async () => {
    const setup = await renderSale(stateWith(saleOf(farmItems(20))))

    try {
      // sem seta, o destaque é o último item e a janela mostra os dez últimos
      await expectFrame(setup, "› 1 x Produto 20 — R$ 20,00")

      // uma subida: a seleção fixa o índice e o topo da janela desce junto
      await pressArrow(setup, "up")
      const afterUp = await expectFrame(setup, "› 1 x Produto 19 — R$ 19,00")

      expect(afterUp).toContain("… 9 itens acima")
      expect(afterUp).toContain("Produto 10")
      expect(afterUp).not.toContain("Produto 09")

      // subindo até o item 10, a janela o acompanha: o topo volta ao primeiro item da venda
      for (let index = 0; index < 9; index += 1) {
        await pressArrow(setup, "up")
      }

      const middle = await expectFrame(setup, "› 1 x Produto 10 — R$ 10,00")

      expect(middle).not.toContain("itens acima")
      expect(middle).toContain("Produto 01")
      expect(middle).not.toContain("Produto 20") // saiu da janela, mas a venda não mudou

      // ↑ na ponta não dá a volta: a seleção fica no primeiro item
      for (let index = 0; index < 25; index += 1) {
        await pressArrow(setup, "up")
      }

      const first = await expectFrame(setup, "› 1 x Produto 01 — R$ 1,00")
      expect(selectedLine(first)).toContain("› 1 x Produto 01 — R$ 1,00")

      // ↓ na outra ponta também trava no último (clamp, sem ciclo)
      for (let index = 0; index < 30; index += 1) {
        await pressArrow(setup, "down")
      }

      const last = await expectFrame(setup, "› 1 x Produto 20 — R$ 20,00")
      expect(selectedLine(last)).toContain("› 1 x Produto 20 — R$ 20,00")
      expect(last).toContain("… 10 itens acima")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("SaleScreen: totais e cabeçalho (1125a)", () => {
  test("os totais são os do estado do servidor, nunca a soma dos itens (BR-12)", async () => {
    const items = farmItems(2) // a soma das linhas é R$ 3,00 — os totais abaixo são outros
    const first = await renderSale(
      stateWith(saleOf(items, { subtotal: 50, discountAmount: 5, total: 45 })),
    )

    try {
      const frame = await expectFrame(first, "TOTAL: R$ 45,00")

      expect(frame).toContain("Subtotal: R$ 50,00")
      expect(frame).toContain("Desconto: R$ 5,00")
      expect(frame).not.toContain("TOTAL: R$ 3,00")
    } finally {
      first.renderer.destroy()
    }

    const second = await renderSale(
      stateWith(saleOf(items, { subtotal: 10, discountAmount: 0, total: 10 })),
    )

    try {
      const frame = await expectFrame(second, "TOTAL: R$ 10,00")

      expect(frame).toContain("Subtotal: R$ 10,00")
      expect(frame).toContain("Desconto: R$ 0,00")
      expect(frame).not.toContain("TOTAL: R$ 45,00")
    } finally {
      second.renderer.destroy()
    }
  })

  test("loja, cliente vinculado e aviso do shell aparecem no cabeçalho", async () => {
    const customer: CustomerOption = { id: "c1", name: "Maria", taxId: null }
    const sale = { ...saleOf(farmItems(1)), customerId: "c1" }
    const setup = await renderSale(stateWith(sale, { notice: "venda retomada — itens preservados" }), {
      store: "Matriz",
      customer,
    })

    try {
      const frame = await expectFrame(setup, "Cliente: Maria")

      expect(frame).toContain("PDV minimercado · Matriz · Caixa 01")
      expect(frame).toContain("venda retomada — itens preservados")
      expectLayout(frame, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("cliente anotado que a venda não aponta não vira cabeçalho", async () => {
    const customer: CustomerOption = { id: "c9", name: "Maria", taxId: null }
    const setup = await renderSale(stateWith(saleOf(farmItems(1))), { customer })

    try {
      const frame = await expectFrame(setup, "Produto 01")

      expect(frame).not.toContain("Cliente:")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("SaleScreen: bipe e leitura manual (1125b)", () => {
  test("o bipe abre a venda, adiciona o item com o código bruto e confirma no rodapé", async () => {
    const createSale = mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleOf([]) }))
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(apiStub({ createSale, addSaleItem }))

    try {
      await scanReader(setup, BARCODE)

      const frame = await expectFrame(setup, "adicionado: 1 x Arroz 5kg — R$ 24,90")

      expect(createSale).toHaveBeenCalledTimes(1)
      expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 })
      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90")
      expect(frame).toContain("TOTAL: R$ 24,90")
      expectLayout(frame, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a leitura digitada no campo vira item e o campo é limpo depois do ENTER", async () => {
    const createSale = mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleOf([]) }))
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(apiStub({ createSale, addSaleItem }))

    try {
      await scanManual(setup, BARCODE)

      const frame = await expectFrame(setup, "adicionado: 1 x Arroz 5kg — R$ 24,90")

      expect(createSale).toHaveBeenCalledTimes(1)
      expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 })
      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90")
      expect(frame).not.toContain(BARCODE)
      expect(frame).toContain(MANUAL_PLACEHOLDER) // o campo voltou a ficar vazio
    } finally {
      setup.renderer.destroy()
    }
  })

  test("3* antes do bipe manda quantidade 3 na mesma chamada", async () => {
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ_3]) }),
    )
    const setup = await renderHarness(apiStub({ addSaleItem }))

    try {
      // o `3` e o `*` são digitados devagar (80 ms): o `*` fecha o multiplicador do próximo bipe
      await act(async () => {
        await setup.mockInput.pressKeys(["3", "*"], 80)
      })
      await scanReader(setup, BARCODE)

      await expectFrame(setup, "adicionado: 3 x Arroz 5kg — R$ 74,70")
      expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 3 })
    } finally {
      setup.renderer.destroy()
    }
  })

  test("3* digitado no campo vale como multiplicador da leitura manual", async () => {
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ_3]) }),
    )
    const setup = await renderHarness(apiStub({ addSaleItem }))

    try {
      await scanManual(setup, `3*${BARCODE}`)

      const frame = await expectFrame(setup, "adicionado: 3 x Arroz 5kg — R$ 74,70")

      expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 3 })
      expect(frame).not.toContain(`3*${BARCODE}`)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("404 avisa, não põe item na venda e o bipe é consumido", async () => {
    const createSale = mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleOf([]) }))
    const addSaleItem = mock(
      async (_saleId: string, item: SaleItemIntent): Promise<AddSaleItemOutcome> =>
        item.barcode === MISSING
          ? { ok: false, kind: "notFound", barcode: MISSING }
          : { ok: true, sale: saleOf([ARROZ]) },
    )
    const actions: Action[] = []
    const setup = await renderHarness(apiStub({ createSale, addSaleItem }), stateWith(null), (action) =>
      actions.push(action),
    )

    try {
      await scanReader(setup, MISSING)

      const frame = await expectFrame(
        setup,
        `produto não encontrado: ${MISSING} — cadastro rápido ainda não disponível`,
      )

      expect(actions).toContainEqual({ type: "scanDismissed" })
      expect(frame).toContain("bipar o primeiro item para iniciar a venda") // a venda criada segue vazia
      expect(frame).not.toContain("adicionado")

      // o próximo bipe reusa a venda que o 404 abriu: nada é criado de novo (BR-11 não tem o que refazer)
      await scanReader(setup, BARCODE)
      const after = await expectFrame(setup, "adicionado: 1 x Arroz 5kg — R$ 24,90")

      expect(createSale).toHaveBeenCalledTimes(1)
      expect(after).toContain("› 1 x Arroz 5kg — R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("422 avisa com a mensagem do servidor e a venda continua utilizável", async () => {
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: false,
        kind: "rejected",
        barcode: BARCODE,
        message: "produto inativo no cadastro — fale com o gerente",
      }),
    )
    const actions: Action[] = []
    const setup = await renderHarness(apiStub({ addSaleItem }), stateWith(null), (action) =>
      actions.push(action),
    )

    try {
      await scanReader(setup, BARCODE)

      const frame = await expectFrame(setup, "produto inativo no cadastro — fale com o gerente")

      expect(actions).toContainEqual({ type: "scanDismissed" })
      expect(frame).toContain("bipar o primeiro item para iniciar a venda")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha transitória mantém o bipe na fila e o ENTER refaz o envio", async () => {
    let attempts = 0
    const createSale = mock(async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleOf([]) }))
    const addSaleItem = mock(async (): Promise<AddSaleItemOutcome> => {
      attempts += 1

      return attempts === 1 ? sendFailure() : { ok: true, sale: saleOf([ARROZ]) }
    })
    const setup = await renderHarness(apiStub({ createSale, addSaleItem }))

    try {
      await scanReader(setup, BARCODE)

      const failed = await expectFrame(setup, "falha ao enviar o bipe — ENTER tenta de novo")

      expect(addSaleItem).toHaveBeenCalledTimes(1) // sem retry automático: o ENTER é sob demanda
      expect(createSale).toHaveBeenCalledTimes(1)
      expect(failed).not.toContain("adicionado")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const frame = await expectFrame(setup, "adicionado: 1 x Arroz 5kg — R$ 24,90")

      expect(addSaleItem).toHaveBeenCalledTimes(2)
      expect(addSaleItem).toHaveBeenLastCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 })
      expect(createSale).toHaveBeenCalledTimes(1) // a venda do primeiro bipe é reaproveitada
      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a rajada do leitor não deixa o código no campo (o primeiro caractere é limpo no bipe)", async () => {
    const setup = await renderHarness(apiStub())

    try {
      await scanReader(setup, BARCODE)

      const frame = await expectFrame(setup, "adicionado: 1 x Arroz 5kg — R$ 24,90")

      expect(frame).not.toContain(BARCODE)
      expect(frame).not.toContain("7891000100")
      expect(frame).toContain(MANUAL_PLACEHOLDER)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o bipe aceito toca o bell e o 404 não toca", async () => {
    const bell = spyOn(process.stdout, "write")
    const bells = () => bell.mock.calls.filter(([chunk]) => chunk === "\u0007").length
    const addSaleItem = mock(
      async (_saleId: string, item: SaleItemIntent): Promise<AddSaleItemOutcome> =>
        item.barcode === MISSING
          ? { ok: false, kind: "notFound", barcode: MISSING }
          : { ok: true, sale: saleOf([ARROZ]) },
    )
    const setup = await renderHarness(apiStub({ addSaleItem }))

    try {
      await scanReader(setup, BARCODE)
      await expectFrame(setup, "adicionado: 1 x Arroz 5kg — R$ 24,90")
      expect(bells()).toBe(1)

      await scanReader(setup, MISSING)
      await expectFrame(setup, `produto não encontrado: ${MISSING}`)
      expect(bells()).toBe(1) // o aviso não toca o bell
    } finally {
      bell.mockRestore()
      setup.renderer.destroy()
    }
  })
})

describe("SaleScreen: quantidade do item selecionado (1125c)", () => {
  test("`+` manda o PATCH com a quantidade absoluta e aplica a venda do servidor", async () => {
    const servidor = saleOf([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }])
    const changeSaleItemQuantity = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: servidor }),
    )
    const actions: Action[] = []
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity }),
      stateWith(saleOf([ARROZ])),
      (action) => actions.push(action),
    )

    try {
      await pressQuantity(setup, "+")

      const frame = await expectFrame(setup, "quantidade: 2 x Arroz 5kg — R$ 49,80")

      expect(changeSaleItemQuantity).toHaveBeenCalledWith(SALE_ID, "p1", 2)
      expect(actions).toContainEqual({ type: "saleUpdated", sale: servidor })
      expect(frame).toContain("› 2 x Arroz 5kg — R$ 49,80")
      expect(frame).toContain("TOTAL: R$ 49,80") // os totais são os da resposta, não a conta local
      expectLayout(frame, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("`-` manda a quantidade menor, também absoluta, com passo 1 em UN e 0,1 em KG", async () => {
    const unitStep = mock(
      async (): Promise<SaleItemMutationOutcome> => ({
        ok: true,
        sale: saleOf([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]),
      }),
    )
    const setupUnit = await renderHarness(
      apiStub({ changeSaleItemQuantity: unitStep }),
      stateWith(saleOf([ARROZ_3])),
    )

    try {
      await pressQuantity(setupUnit, "-")

      await expectFrame(setupUnit, "quantidade: 2 x Arroz 5kg — R$ 49,80")
      expect(unitStep).toHaveBeenCalledWith(SALE_ID, "p1", 2)
    } finally {
      setupUnit.renderer.destroy()
    }

    // a granel o passo é 0,1 e a quantidade vai sem ruído de ponto flutuante (1,2 → 1,1)
    const kgStep = mock(
      async (): Promise<SaleItemMutationOutcome> => ({
        ok: true,
        sale: saleOf([{ ...BANANA, quantity: 1.1, lineTotal: 6.59 }]),
      }),
    )
    const setupKg = await renderHarness(
      apiStub({ changeSaleItemQuantity: kgStep }),
      stateWith(saleOf([BANANA])),
    )

    try {
      await pressQuantity(setupKg, "-")

      const frame = await expectFrame(setupKg, "quantidade: 1,100 x Banana prata — R$ 6,59")

      expect(kgStep).toHaveBeenCalledWith(SALE_ID, "p3", 1.1)
      expect(frame).toContain("› 1,100 x Banana prata — R$ 6,59")
    } finally {
      setupKg.renderer.destroy()
    }
  })

  test("`-` que zeraria não chama a API: avisa para usar o DEL (a remoção é o 1126)", async () => {
    const changeSaleItemQuantity = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressQuantity(setup, "-")

      const frame = await expectFrame(setup, "use DEL para remover o item")

      expect(changeSaleItemQuantity).not.toHaveBeenCalled()
      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90") // o item fica como estava
      expect(frame).toContain("TOTAL: R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("404 do item que sumiu avisa e mantém a venda como está", async () => {
    const changeSaleItemQuantity = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: "notFound" }),
    )
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressQuantity(setup, "+")

      const frame = await expectFrame(setup, "item já não está na venda: Arroz 5kg")

      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90")
      expect(frame).toContain("TOTAL: R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha transitória avisa e a mesma tecla refaz", async () => {
    let attempts = 0
    const changeSaleItemQuantity = mock(async (): Promise<SaleItemMutationOutcome> => {
      attempts += 1

      return attempts === 1
        ? sendFailure()
        : { ok: true, sale: saleOf([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]) }
    })
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressQuantity(setup, "+")

      const failed = await expectFrame(setup, "falha ao falar com o servidor — +/- tenta de novo")

      expect(changeSaleItemQuantity).toHaveBeenCalledTimes(1) // sem retry automático: a tecla é sob demanda
      expect(failed).toContain("› 1 x Arroz 5kg — R$ 24,90")

      await pressQuantity(setup, "+")

      await expectFrame(setup, "quantidade: 2 x Arroz 5kg — R$ 49,80")
      expect(changeSaleItemQuantity).toHaveBeenCalledTimes(2)
      expect(changeSaleItemQuantity).toHaveBeenLastCalledWith(SALE_ID, "p1", 2)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("uma mutação por vez: com o PATCH em voo a tecla é ignorada e o rodapé mostra enviando…", async () => {
    let release!: () => void
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    const changeSaleItemQuantity = mock(async (): Promise<SaleItemMutationOutcome> => {
      await pending
      return { ok: true, sale: saleOf([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]) }
    })
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressQuantity(setup, "+")
      await expectFrame(setup, "enviando…")

      await pressQuantity(setup, "+") // segunda tecla com a primeira ainda em voo
      expect(changeSaleItemQuantity).toHaveBeenCalledTimes(1)

      await act(async () => {
        release()
      })

      const frame = await expectFrame(setup, "quantidade: 2 x Arroz 5kg — R$ 49,80")

      expect(changeSaleItemQuantity).toHaveBeenCalledTimes(1)
      expect(frame).not.toContain("enviando…")
      expect(frame).toContain("› 2 x Arroz 5kg — R$ 49,80")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("um bipe que chega durante a mutação é drenado no pump do finally", async () => {
    let release!: () => void
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    const changeSaleItemQuantity = mock(async (): Promise<SaleItemMutationOutcome> => {
      await pending
      return { ok: true, sale: saleOf([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]) }
    })
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: true,
        sale: saleOf([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }, FEIJAO]),
      }),
    )
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity, addSaleItem }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressQuantity(setup, "+")
      await expectFrame(setup, "enviando…")

      await scanReader(setup, BARCODE) // o bipe chega com a mutação em voo
      expect(addSaleItem).not.toHaveBeenCalled() // a fila espera a mutação terminar

      await act(async () => {
        release()
      })

      const frame = await expectFrame(setup, "adicionado: 1 x Feijão 1kg — R$ 8,90")

      expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 })
      expect(frame).toContain("› 1 x Feijão 1kg — R$ 8,90")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("SaleScreen: modais — ajuda e remoção de item (1126a)", () => {
  test("DEL abre a confirmação do item selecionado e o ENTER remove, com a confirmação no rodapé", async () => {
    const removido = saleOf([ARROZ])
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: removido }),
    )
    const actions: Action[] = []
    const setup = await renderHarness(
      apiStub({ removeSaleItem }),
      stateWith(saleOf([ARROZ, FEIJAO])),
      (action) => actions.push(action),
    )

    try {
      await pressNamed(setup, KeyCodes.DELETE)

      const frame = await expectFrame(setup, "remover Feijão 1kg?")

      expect(frame).toContain("Remover item") // o título do `ModalFrame`
      expect(frame).toContain("ENTER confirma · ESC cancela")
      expect(removeSaleItem).not.toHaveBeenCalled() // nada vai à API antes do ENTER

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const after = await expectFrame(setup, "removido: Feijão 1kg")

      expect(removeSaleItem).toHaveBeenCalledWith(SALE_ID, "p2")
      expect(actions).toContainEqual({ type: "saleUpdated", sale: removido })
      expect(after).toContain("TOTAL: R$ 24,90") // os totais são os da resposta do servidor (BR-12)
      expect(after).not.toContain("remover Feijão")
      expect(highlighted(after)).toHaveLength(1)
      expectLayout(after, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ESC fecha a confirmação sem chamar a API e sem mexer na venda", async () => {
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(
      apiStub({ removeSaleItem }),
      stateWith(saleOf([ARROZ, FEIJAO])),
    )

    try {
      await pressNamed(setup, KeyCodes.DELETE)
      await expectFrame(setup, "remover Feijão 1kg?")

      await pressEscape(setup)

      const frame = await expectFrame(setup, "› 1 x Feijão 1kg — R$ 8,90")

      expect(frame).not.toContain("remover Feijão")
      expect(frame).toContain("TOTAL: R$ 33,80") // a venda segue como estava
      expect(removeSaleItem).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F3 faz o mesmo que o DEL: uma confirmação só e o ENTER remove", async () => {
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(
      apiStub({ removeSaleItem }),
      stateWith(saleOf([ARROZ, FEIJAO])),
    )

    try {
      await pressNamed(setup, KeyCodes.F3)

      await expectFrame(setup, "remover Feijão 1kg?")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      await expectFrame(setup, "removido: Feijão 1kg")
      expect(removeSaleItem).toHaveBeenCalledTimes(1) // a confirmação não duplicou
      expect(removeSaleItem).toHaveBeenCalledWith(SALE_ID, "p2")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("remover o último item volta ao estado vazio do 1125a", async () => {
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([]) }),
    )
    const setup = await renderHarness(apiStub({ removeSaleItem }), stateWith(saleOf([ARROZ])))

    try {
      await pressNamed(setup, KeyCodes.DELETE)
      await expectFrame(setup, "remover Arroz 5kg?")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const frame = await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      expect(frame).toContain("TOTAL: R$ 0,00")
      expect(highlighted(frame)).toEqual([])
      expect(frame).not.toContain("remover Arroz")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("rajada do leitor com a confirmação à vista não vira item nem confirma a remoção", async () => {
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ, FEIJAO]) }),
    )
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(
      apiStub({ addSaleItem, removeSaleItem }),
      stateWith(saleOf([ARROZ, FEIJAO])),
    )

    try {
      await pressNamed(setup, KeyCodes.DELETE)
      await expectFrame(setup, "remover Feijão 1kg?")

      await scanReader(setup, BARCODE) // a rajada inteira, terminador incluso

      const frame = await setup.waitForFrame((current) => current.includes("remover Feijão 1kg?"))

      expect(addSaleItem).not.toHaveBeenCalled() // o leitor está desligado (§11.3)
      expect(removeSaleItem).not.toHaveBeenCalled() // o terminador do bipe não é o ENTER humano
      expect(frame).toContain("ENTER confirma · ESC cancela")

      await pressEscape(setup)

      const back = await expectFrame(setup, "› 1 x Feijão 1kg — R$ 8,90")

      expect(back).toContain("TOTAL: R$ 33,80") // a venda não foi mexida pela rajada
      expect(addSaleItem).not.toHaveBeenCalled() // o bipe engolido não reaparece depois
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha transitória na remoção mantém a confirmação à vista e o ENTER refaz", async () => {
    let attempts = 0
    const removeSaleItem = mock(async (): Promise<SaleItemMutationOutcome> => {
      attempts += 1

      return attempts === 1 ? sendFailure() : { ok: true, sale: saleOf([ARROZ]) }
    })
    const setup = await renderHarness(
      apiStub({ removeSaleItem }),
      stateWith(saleOf([ARROZ, FEIJAO])),
    )

    try {
      await pressNamed(setup, KeyCodes.DELETE)
      await expectFrame(setup, "remover Feijão 1kg?")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const failed = await expectFrame(setup, "falha ao remover o item — ENTER tenta de novo")

      expect(removeSaleItem).toHaveBeenCalledTimes(1) // sem retry automático: o ENTER é sob demanda
      expect(failed).toContain("remover Feijão 1kg?") // a confirmação segue à vista

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const frame = await expectFrame(setup, "removido: Feijão 1kg")

      expect(removeSaleItem).toHaveBeenCalledTimes(2)
      expect(frame).toContain("› 1 x Arroz 5kg — R$ 24,90")
      expect(frame).not.toContain("remover Feijão")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("404 na remoção avisa e fecha a confirmação sem mexer na venda", async () => {
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: "notFound" }),
    )
    const setup = await renderHarness(
      apiStub({ removeSaleItem }),
      stateWith(saleOf([ARROZ, FEIJAO])),
    )

    try {
      await pressNamed(setup, KeyCodes.DELETE)
      await expectFrame(setup, "remover Feijão 1kg?")

      await act(async () => {
        setup.mockInput.pressEnter()
      })

      const frame = await expectFrame(setup, "item já não está na venda: Feijão 1kg")

      expect(frame).not.toContain("remover Feijão")
      expect(frame).toContain("› 1 x Feijão 1kg — R$ 8,90") // a venda segue como estava
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F1 abre a ajuda com o mapa de teclas e o ESC fecha; com ela à vista a venda não age", async () => {
    const changeSaleItemQuantity = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([]) }),
    )
    const setup = await renderHarness(
      apiStub({ changeSaleItemQuantity, removeSaleItem }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressNamed(setup, KeyCodes.F1)

      const frame = await expectFrame(setup, "Ajuda — atalhos da venda (F1)")

      for (const [keys, description] of HELP_LINES) {
        expect(frame).toContain(`${keys} — ${description}`)
      }

      expect(frame).toContain("ESC fecha e volta para a venda")
      expectLayout(frame, 80, 24)

      // com a ajuda à vista a venda não age: a quantidade e a remoção da tela ficam inertes
      await pressQuantity(setup, "+")
      await pressNamed(setup, KeyCodes.DELETE)

      expect(changeSaleItemQuantity).not.toHaveBeenCalled()
      expect(removeSaleItem).not.toHaveBeenCalled()

      await pressEscape(setup)

      const back = await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(back).toContain("TOTAL: R$ 24,90")
      expect(back).not.toContain("Ajuda — atalhos")
      expect(back).not.toContain("remover Arroz")
    } finally {
      setup.renderer.destroy()
    }
  })
})

describe("SaleScreen: modais — consulta de preço e desconto (1126b)", () => {
  /** Produtos como o servidor os devolve na consulta (F2): nome, preço e unidade são dele (BR-12). */
  const ARROZ_PRODUCT: ProductOption = { id: "p1", name: "Arroz 5kg", price: 24.9, unit: "UN" }
  const FEIJAO_PRODUCT: ProductOption = { id: "p2", name: "Feijão 1kg", price: 8.5, unit: "KG" }

  /** Venda como o servidor a devolveu depois do desconto (BR-12): desconto de R$ 5,00 e total novo. */
  const discounted = saleOf([ARROZ], { subtotal: 24.9, discountAmount: 5, total: 19.9 })

  test("F2 abre a consulta: o código conhecido mostra nome, preço, unidade e saldo sem mexer na venda", async () => {
    const resolveBarcode = mock(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { ...ARROZ_PRODUCT, quantity: null },
      }),
    )
    const productStock = mock(
      async (): Promise<ProductStockOutcome> => ({
        ok: true,
        stock: { quantity: 3, minQuantity: 5, lowStock: true },
      }),
    )
    const searchProducts = mock(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [] }),
    )
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const removeSaleItem = mock(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleOf([]) }),
    )
    const setup = await renderHarness(
      apiStub({ resolveBarcode, productStock, searchProducts, addSaleItem, removeSaleItem }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressNamed(setup, KeyCodes.F2)

      const opened = await expectFrame(setup, "Consulta de preço (F2)")

      expect(opened).toContain("digite o código de barras ou o nome e ENTER consulta")
      expect(opened).toContain("ENTER consulta · ESC fecha")
      expect(resolveBarcode).not.toHaveBeenCalled() // abrir não consulta nada

      await typeHuman(setup, BARCODE)
      await pressEnter(setup)

      const detail = await expectFrame(setup, "Saldo: 3 · mínimo 5 · ESTOQUE BAIXO")

      expect(detail).toContain("Produto: Arroz 5kg")
      expect(detail).toContain("Preço: R$ 24,90 · UN")
      expect(resolveBarcode).toHaveBeenCalledWith(BARCODE) // o código vai bruto ao servidor (BR-14)
      expect(productStock).toHaveBeenCalledWith("p1") // o saldo é o do produto que ele devolveu
      expect(searchProducts).not.toHaveBeenCalled() // código conhecido não vira busca por nome
      expectLayout(detail, 80, 24)

      await pressEscape(setup)

      const back = await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(back).toContain("TOTAL: R$ 24,90") // consulta não é venda: nada mudou (aceite do 1126b)
      expect(back).not.toContain("Consulta de preço (F2)")
      expect(addSaleItem).not.toHaveBeenCalled()
      expect(removeSaleItem).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("o código desconhecido cai na busca por nome: lista com setas e o saldo refeito no selecionado", async () => {
    const resolveBarcode = mock(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: "notFound",
        problem: { status: 404, code: "PRODUCT_NOT_FOUND", detail: "produto não encontrado" },
      }),
    )
    const searchProducts = mock(
      async (): Promise<SearchProductsOutcome> => ({
        ok: true,
        products: [ARROZ_PRODUCT, FEIJAO_PRODUCT],
      }),
    )
    // o saldo é por produto: trocar a seleção tem de refazer a consulta (aceite do 1126b)
    const productStock = mock(
      async (productId: string): Promise<ProductStockOutcome> => ({
        ok: true,
        stock:
          productId === ARROZ_PRODUCT.id
            ? { quantity: 12, minQuantity: 5, lowStock: false }
            : { quantity: 2, minQuantity: 5, lowStock: true },
      }),
    )
    const addSaleItem = mock(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleOf([ARROZ]) }),
    )
    const setup = await renderHarness(
      apiStub({ resolveBarcode, searchProducts, productStock, addSaleItem }),
      stateWith(saleOf([ARROZ])),
    )

    try {
      await pressNamed(setup, KeyCodes.F2)
      await expectFrame(setup, "Consulta de preço (F2)")

      await typeHuman(setup, "arroz")
      await pressEnter(setup)

      const list = await expectFrame(setup, "› Arroz 5kg — R$ 24,90")

      expect(list).toContain("Feijão 1kg — R$ 8,50")
      expect(list).toContain("↑↓ escolhe · ENTER consulta · ESC fecha")
      expect(resolveBarcode).toHaveBeenCalledWith("arroz") // o termo vai como o operador digitou
      expect(searchProducts).toHaveBeenCalledWith("arroz")
      expect(productStock).not.toHaveBeenCalled() // a lista ainda não consultou saldo de ninguém
      expectLayout(list, 80, 24)

      await pressEnter(setup)

      const first = await expectFrame(setup, "Saldo: 12 · mínimo 5")

      expect(first).toContain("Produto: Arroz 5kg")
      expect(first).not.toContain("ESTOQUE BAIXO") // saldo acima do mínimo
      expect(productStock).toHaveBeenCalledWith("p1")

      await pressArrow(setup, "down")

      const moved = await expectFrame(setup, "› Feijão 1kg — R$ 8,50")

      expect(moved).not.toContain("› Arroz 5kg — R$ 24,90") // a seta moveu o destaque

      await pressEnter(setup)

      const detail = await expectFrame(setup, "Saldo: 2 · mínimo 5 · ESTOQUE BAIXO")

      expect(detail).toContain("Produto: Feijão 1kg")
      expect(detail).toContain("Preço: R$ 8,50 · KG")
      expect(productStock).toHaveBeenCalledWith("p2") // o saldo foi refeito para o novo selecionado

      await pressEscape(setup)

      const back = await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(back).toContain("TOTAL: R$ 24,90")
      expect(addSaleItem).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })

  test("recusa e falha transitória do saldo ficam no modal; o ENTER refaz a consulta", async () => {
    const resolveBarcode = mock(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { ...FEIJAO_PRODUCT, quantity: null },
      }),
    )
    let attempts = 0
    const productStock = mock(async (): Promise<ProductStockOutcome> => {
      attempts += 1

      if (attempts === 1) {
        return sendFailure()
      }

      return attempts === 2
        ? { ok: false, kind: "rejected", message: "produto não encontrado — faça a consulta de novo" }
        : { ok: true, stock: { quantity: 7, minQuantity: 5, lowStock: false } }
    })
    const setup = await renderHarness(apiStub({ resolveBarcode, productStock }), stateWith(saleOf([ARROZ])))

    try {
      await pressNamed(setup, KeyCodes.F2)
      await typeHuman(setup, "7891000100103")
      await pressEnter(setup)

      const transient = await expectFrame(setup, "falha ao consultar o saldo — ENTER tenta de novo")

      expect(transient).toContain("Consulta de preço (F2)") // o modal segue aberto
      expect(transient).not.toContain("Saldo:")

      await pressEnter(setup) // retry: recomeça pela consulta do código, que é idempotente

      const rejected = await expectFrame(setup, "produto não encontrado — faça a consulta de novo")

      expect(rejected).toContain("Consulta de preço (F2)") // a recusa também não fecha o modal
      expect(productStock).toHaveBeenCalledTimes(2)

      await pressEnter(setup)

      const frame = await expectFrame(setup, "Saldo: 7 · mínimo 5")

      expect(frame).toContain("Produto: Feijão 1kg")
      expect(resolveBarcode).toHaveBeenCalledTimes(3) // cada retry recomeça pelo termo do campo
      expect(productStock).toHaveBeenCalledTimes(3)

      await pressEscape(setup)

      await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F5 aplica o desconto: valor em centavos mascarado, motivo obrigatório e venda do servidor", async () => {
    const applyDiscount = mock(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted }),
    )
    const actions: Action[] = []
    const setup = await renderHarness(
      apiStub({ applyDiscount }),
      stateWith(saleOf([ARROZ])),
      (action) => actions.push(action),
    )

    try {
      await pressNamed(setup, KeyCodes.F5)

      const opened = await expectFrame(setup, "Desconto na venda (F5)")

      expect(opened).toContain("› Valor: R$ 0,00")
      expect(opened).toContain("digite o valor em centavos: 1250 vira R$ 12,50")
      expect(opened).toContain("TAB troca o campo · ←/→ no tipo · ENTER aplica · ESC cancela")

      // ENTER sem nada: valor e motivo são obrigatórios (BR-04) e nada vai à API
      await pressEnter(setup)
      await expectFrame(setup, "informe o valor do desconto")

      await typeHuman(setup, "1250")
      await expectFrame(setup, "› Valor: R$ 12,50") // a máscara de centavos da abertura de caixa

      await pressEnter(setup)

      const missing = await expectFrame(setup, "informe o motivo do desconto")

      expect(missing).toContain("Desconto na venda (F5)")
      expect(applyDiscount).not.toHaveBeenCalled()

      await pressTab(setup)
      await expectFrame(setup, "› Motivo:")
      await typeHuman(setup, "cliente antigo")
      await expectFrame(setup, "Motivo: cliente antigo")

      await pressEnter(setup)

      const frame = await expectFrame(setup, "desconto: R$ 5,00 — total R$ 19,90")

      expect(applyDiscount).toHaveBeenCalledWith(SALE_ID, {
        type: "VALUE",
        value: 12.5,
        reason: "cliente antigo",
      })
      expect(actions).toContainEqual({ type: "saleUpdated", sale: discounted })
      expect(frame).not.toContain("Desconto na venda (F5)") // o modal saiu de cena
      expect(frame).toContain("TOTAL: R$ 19,90") // os totais são os do servidor (BR-12)
      expectLayout(frame, 80, 24)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F5 alterna VALOR/PERCENTUAL no tipo (TAB e ←/→) e aplica PERCENTUAL com o inteiro", async () => {
    const percent = saleOf([ARROZ], { subtotal: 24.9, discountAmount: 2.49, total: 22.41 })
    const applyDiscount = mock(async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: percent }))
    const actions: Action[] = []
    const setup = await renderHarness(
      apiStub({ applyDiscount }),
      stateWith(saleOf([ARROZ])),
      (action) => actions.push(action),
    )

    try {
      await pressNamed(setup, KeyCodes.F5)

      const opened = await expectFrame(setup, "Tipo: [VALOR] · PERCENTUAL")

      expect(opened).toContain("› Valor: R$ 0,00") // abre no VALOR, com a máscara de centavos
      expect(opened).toContain("TAB troca o campo · ←/→ no tipo · ENTER aplica · ESC cancela")

      await pressTab(setup) // valor → motivo
      await pressTab(setup) // motivo → tipo
      await expectFrame(setup, "› Tipo: [VALOR] · PERCENTUAL")

      await pressNamed(setup, KeyCodes.ARROW_RIGHT)

      await expectFrame(setup, "› Tipo: VALOR · [PERCENTUAL]")

      await pressNamed(setup, KeyCodes.ARROW_LEFT) // ← volta para VALOR

      await expectFrame(setup, "› Tipo: [VALOR] · PERCENTUAL")

      await pressNamed(setup, KeyCodes.ARROW_RIGHT) // → para PERCENTUAL de novo

      await expectFrame(setup, "› Tipo: VALOR · [PERCENTUAL]")

      await pressTab(setup) // tipo → valor

      const percentField = await expectFrame(setup, "› Valor: 0%") // a máscara do tipo muda

      expect(percentField).toContain("digite o percentual inteiro: 10 vira 10%")

      await typeHuman(setup, "10")
      await expectFrame(setup, "› Valor: 10%")

      await pressTab(setup) // valor → motivo
      await typeHuman(setup, "promo")
      await expectFrame(setup, "› Motivo: promo")

      await pressEnter(setup)

      const frame = await expectFrame(setup, "desconto: R$ 2,49 — total R$ 22,41")

      expect(applyDiscount).toHaveBeenCalledWith(SALE_ID, {
        type: "PERCENT",
        value: 10, // no PERCENTUAL os dígitos já são o inteiro (a máscara é a do servidor, BR-12)
        reason: "promo",
      })
      expect(actions).toContainEqual({ type: "saleUpdated", sale: percent })
      expect(frame).not.toContain("Desconto na venda (F5)")
      expect(frame).toContain("TOTAL: R$ 22,41")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a recusa do desconto fica no modal com a mensagem do servidor, sem fechar", async () => {
    const applyDiscount = mock(
      async (): Promise<ApplyDiscountOutcome> => ({
        ok: false,
        kind: "rejected",
        message: "desconto excede o limite de 10% da loja",
      }),
    )
    const setup = await renderHarness(apiStub({ applyDiscount }), stateWith(saleOf([ARROZ])))

    try {
      await pressNamed(setup, KeyCodes.F5)
      await expectFrame(setup, "Desconto na venda (F5)")
      await typeHuman(setup, "1250")
      await pressTab(setup)
      await typeHuman(setup, "cliente antigo")
      await pressEnter(setup)

      const frame = await expectFrame(setup, "desconto excede o limite de 10% da loja")

      expect(frame).toContain("Desconto na venda (F5)") // o modal não fechou
      expect(frame).toContain("Valor: R$ 12,50") // nem perdeu o que foi digitado
      expect(frame).toContain("› Motivo: cliente antigo") // o foco ficou onde o operador digitou
      expect(applyDiscount).toHaveBeenCalledTimes(1)

      await pressEscape(setup)

      const back = await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(back).toContain("TOTAL: R$ 24,90") // a venda segue como estava
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha transitória mantém o modal com o aviso e o ENTER refaz a aplicação", async () => {
    let attempts = 0
    const applyDiscount = mock(async (): Promise<ApplyDiscountOutcome> => {
      attempts += 1

      return attempts === 1 ? sendFailure() : { ok: true, sale: discounted }
    })
    const setup = await renderHarness(apiStub({ applyDiscount }), stateWith(saleOf([ARROZ])))

    try {
      await pressNamed(setup, KeyCodes.F5)
      await expectFrame(setup, "Desconto na venda (F5)")
      await typeHuman(setup, "1250")
      await pressTab(setup)
      await typeHuman(setup, "cliente antigo")
      await pressEnter(setup)

      const failed = await expectFrame(setup, "falha ao aplicar o desconto — ENTER tenta de novo")

      expect(failed).toContain("Desconto na venda (F5)") // o formulário continua à vista
      expect(applyDiscount).toHaveBeenCalledTimes(1) // sem retry automático

      await pressEnter(setup)

      const frame = await expectFrame(setup, "desconto: R$ 5,00 — total R$ 19,90")

      expect(applyDiscount).toHaveBeenCalledTimes(2)
      expect(frame).not.toContain("Desconto na venda (F5)")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("falha bloqueante do desconto vai para a tela de erro com o modal fechado", async () => {
    const problem: ApiProblem = { status: 409, code: "SALE_NOT_OPEN", detail: "venda não está aberta" }
    const applyDiscount = mock(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: false, kind: "failed", problem }),
    )
    const dispatch = dispatchSpy()
    const setup = await renderSale(stateWith(saleOf([ARROZ])), {
      api: apiStub({ applyDiscount }),
      dispatch,
    })

    try {
      await pressNamed(setup, KeyCodes.F5)
      await expectFrame(setup, "Desconto na venda (F5)")
      await typeHuman(setup, "1250")
      await pressTab(setup)
      await typeHuman(setup, "cliente antigo")
      await pressEnter(setup)

      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "apiFailed", problem })

      // o modal sai de cena e a venda volta: a tela de erro é do shell, que reage ao `apiFailed`
      const frame = await expectFrame(setup, "PDV minimercado · Caixa 01")

      expect(frame).not.toContain("Desconto na venda (F5)")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("F5 sem venda criada não abre (a venda nasce no bipe); o F2 abre mesmo sem venda", async () => {
    const applyDiscount = mock(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: saleOf([]) }),
    )
    const resolveBarcode = mock(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: "notFound",
        problem: { status: 404, code: "PRODUCT_NOT_FOUND", detail: "produto não encontrado" },
      }),
    )
    const setup = await renderHarness(
      apiStub({ applyDiscount, resolveBarcode }),
      stateWith(null),
    )

    try {
      await pressNamed(setup, KeyCodes.F5)
      // sem venda o F5 é ignorado: a venda continua respondendo (o F1 abre a ajuda logo depois)
      await pressNamed(setup, KeyCodes.F1)

      const help = await expectFrame(setup, "Ajuda — atalhos da venda (F1)")

      expect(help).not.toContain("Desconto na venda (F5)")
      expect(applyDiscount).not.toHaveBeenCalled()

      await pressEscape(setup)
      await pressNamed(setup, KeyCodes.F2)

      const lookup = await expectFrame(setup, "Consulta de preço (F2)")

      expect(lookup).not.toContain("bipar o primeiro item para iniciar a venda") // o corpo saiu

      await pressEscape(setup)

      const back = await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

      expect(back).toContain("TOTAL: R$ 0,00")
      expect(resolveBarcode).not.toHaveBeenCalled() // consultar não cria venda nem lê nada sozinho
    } finally {
      setup.renderer.destroy()
    }
  })

  test("a rajada do leitor no desconto não vira valor nem aplica (o terminador não é o ENTER)", async () => {
    const applyDiscount = mock(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted }),
    )
    const setup = await renderHarness(apiStub({ applyDiscount }), stateWith(saleOf([ARROZ])))

    try {
      await pressNamed(setup, KeyCodes.F5)
      await expectFrame(setup, "Desconto na venda (F5)")

      // o motivo é digitado antes: se o terminador do bipe aplicasse, a API seria chamada
      await pressTab(setup) // valor → motivo
      await typeHuman(setup, "cliente antigo")
      await pressTab(setup) // motivo → tipo
      await pressTab(setup) // tipo → valor (o bipe cai no campo do valor)
      await waitMs(60) // o operador parou de digitar: o primeiro caractere do bipe não é rajada

      await act(async () => {
        await setup.mockInput.typeText(BARCODE) // os caracteres, todos em rajada
        setup.mockInput.pressEnter() // e o terminador colado neles
      })

      const frame = await expectFrame(setup, "Valor: R$ 0,07")

      expect(frame).toContain("Desconto na venda (F5)")
      expect(frame).toContain("Motivo: cliente antigo")
      expect(applyDiscount).not.toHaveBeenCalled() // o bipe não aplicou desconto nenhum

      await pressEscape(setup)

      const back = await expectFrame(setup, "› 1 x Arroz 5kg — R$ 24,90")

      expect(back).toContain("TOTAL: R$ 24,90")
      expect(applyDiscount).not.toHaveBeenCalled()
    } finally {
      setup.renderer.destroy()
    }
  })
})
