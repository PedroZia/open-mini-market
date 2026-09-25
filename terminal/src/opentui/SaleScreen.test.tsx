/** @jsxImportSource @opentui/react */
import { describe, expect, test } from "bun:test"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type { CustomerOption } from "../api/terminalApi"
import type { SaleItemView, SaleOpenState, SaleView } from "../core/state"
import { SaleScreen } from "./SaleScreen"

/**
 * Layout e lista da tela de venda (1125a): o quadro em 80×24 e 120×40, a janela que segue a seleção
 * (F-04), os totais que vêm do `state.sale` (BR-12) e a hora que entra por prop.
 *
 * A tela não chama a API neste passo — o bipe, a leitura manual e as mutações chegam no 1125b/c —,
 * então o teste monta o estado do reducer direto e dirige as teclas pelo `mockInput`. Nada de
 * cálculo de dinheiro no teste: as fixtures são o que o servidor devolveria (BR-12).
 */

/** Hora local fixa: o cabeçalho mostra `14:32:05` em qualquer fuso (o `getHours` é local). */
const NOW = new Date(2026, 8, 25, 14, 32, 5)

const OPERADOR = { id: "u1", name: "Ana Souza" }
const CAIXA = { id: "r1", name: "Caixa 01" }

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

/** Venda do servidor; os totais só mudam quando o teste quer provar que a tela os exibe (BR-12). */
function saleOf(items: SaleItemView[], totals: Partial<SaleView> = {}): SaleView {
  return {
    id: "sale-1",
    items,
    subtotal: 0,
    discountAmount: 0,
    total: 0,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId: null,
    ...totals,
  }
}

type Setup = Awaited<ReturnType<typeof testRender>>

type RenderOptions = {
  width?: number
  height?: number
  customer?: CustomerOption | null
  store?: string | null
  online?: boolean
}

function renderSale(state: SaleOpenState, options: RenderOptions = {}) {
  return testRender(
    <SaleScreen
      state={state}
      now={NOW}
      customer={options.customer ?? null}
      store={options.store ?? null}
      online={options.online ?? true}
    />,
    { width: options.width ?? 80, height: options.height ?? 24 },
  )
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
