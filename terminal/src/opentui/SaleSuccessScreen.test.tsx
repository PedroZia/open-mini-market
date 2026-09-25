/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test } from "bun:test"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type { Action } from "../core/reducer"
import type { ReceiptView } from "../core/state"
import { SaleSuccessScreen } from "./SaleSuccessScreen"

/**
 * Tela de sucesso (1113) portada para a UI nova (1127a): o resumo do servidor no lugar da venda —
 * número, total e o troco dele (BR-12) — e o ENTER que começa a próxima venda despachando
 * `receiptDismissed` (quem limpa o resumo é o reducer). O teste da tela é direto: o `dispatch` é
 * espião e prova o que ela relatou.
 */

const RECEIPT: ReceiptView = { number: 42, total: 25, changeAmount: 7.5 }

type Setup = Awaited<ReturnType<typeof testRender>>
type DispatchSpy = ReturnType<typeof mock<(action: Action) => void>>

function dispatchSpy(): DispatchSpy {
  return mock((action: Action) => {
    void action
  })
}

function renderSuccess(receipt: ReceiptView = RECEIPT, dispatch: DispatchSpy = dispatchSpy()) {
  return testRender(<SaleSuccessScreen receipt={receipt} dispatch={dispatch} />, {
    width: 80,
    height: 24,
  })
}

/** Espera o frame alcançar o texto (a tela renderiza fora do passo da tecla que o causou). */
function expectFrame(setup: Setup, text: string): Promise<string> {
  return setup.waitForFrame((frame) => frame.includes(text))
}

/** Espera o despacho da tecla, que o parser da OpenTUI pode segurar (ESC sozinho tem ~20 ms). */
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

describe("SaleSuccessScreen", () => {
  test("mostra o número e o total do servidor com o troco em destaque", async () => {
    const setup = await renderSuccess()

    try {
      const frame = await expectFrame(setup, "Venda 42 concluída")

      expect(frame).toContain("TOTAL: R$ 25,00")
      expect(frame).toContain("TROCO: R$ 7,50")
      expect(frame).toContain("ENTER inicia a próxima venda")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("sem troco a linha fica zerada (venda sem dinheiro não tem troco, BR-05)", async () => {
    const setup = await renderSuccess({ number: 43, total: 10, changeAmount: 0 })

    try {
      const frame = await expectFrame(setup, "Venda 43 concluída")

      expect(frame).toContain("TROCO: R$ 0,00")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ENTER dispensa o resumo e começa a próxima venda", async () => {
    const dispatch = dispatchSpy()
    const setup = await renderSuccess(RECEIPT, dispatch)

    try {
      await expectFrame(setup, "Venda 42 concluída")

      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "receiptDismissed" })
      expect(dispatch).toHaveBeenCalledTimes(1)
    } finally {
      setup.renderer.destroy()
    }
  })
})
