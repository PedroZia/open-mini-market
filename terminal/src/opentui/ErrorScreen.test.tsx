/** @jsxImportSource @opentui/react */
import { describe, expect, mock, test, type Mock } from "bun:test"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import type { Action } from "../core/reducer"
import type { ApiProblem } from "../core/state"
import { ErrorScreen } from "./ErrorScreen"

/**
 * Contrato da tela de erro com o reducer (1103): ela exibe o `problem+json` e reconhece a falha com
 * ENTER/ESC, despachando `confirm`/`cancel` — quem volta para a origem é o reducer. A resolução da
 * tecla é a do contexto `error` (`core/keys`), como na Ink.
 */

const PROBLEM: ApiProblem = { status: 503, code: "UNAVAILABLE", detail: "servidor fora do ar" }

type Setup = Awaited<ReturnType<typeof testRender>>
type DispatchSpy = Mock<(action: Action) => void>

/** Espião do despacho: a assinatura é a do `Dispatch<Action>` e o argumento é o que o `expect` lê. */
function dispatchSpy(): DispatchSpy {
  return mock((action: Action) => {
    void action
  })
}

function renderScreen(problem: ApiProblem = PROBLEM, dispatch: DispatchSpy = dispatchSpy()) {
  return testRender(<ErrorScreen problem={problem} dispatch={dispatch} />, {
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

describe("ErrorScreen", () => {
  test("mostra status, código e detalhe do problem+json", async () => {
    const setup = await renderScreen()

    try {
      const frame = await expectFrame(setup, "503 — UNAVAILABLE — servidor fora do ar")

      expect(frame).toContain("Falha na operação")
      expect(frame).toContain("ENTER/ESC para voltar")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("status 0 sem código aparece como 'sem código'", async () => {
    const problem: ApiProblem = { status: 0, code: null, detail: "Falha de rede ao chamar a API." }
    const setup = await renderScreen(problem)

    try {
      await expectFrame(setup, "0 — sem código — Falha de rede ao chamar a API.")
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ENTER reconhece a falha e despacha confirm", async () => {
    const dispatch = dispatchSpy()
    const setup = await renderScreen(PROBLEM, dispatch)

    try {
      await act(async () => {
        setup.mockInput.pressEnter()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "confirm" })
      expect(dispatch).toHaveBeenCalledTimes(1)
    } finally {
      setup.renderer.destroy()
    }
  })

  test("ESC também reconhece a falha e despacha cancel", async () => {
    const dispatch = dispatchSpy()
    const setup = await renderScreen(PROBLEM, dispatch)

    try {
      await act(async () => {
        setup.mockInput.pressEscape()
      })
      await until(() => dispatch.mock.calls.length > 0)

      expect(dispatch).toHaveBeenCalledWith({ type: "cancel" })
      expect(dispatch).toHaveBeenCalledTimes(1)
    } finally {
      setup.renderer.destroy()
    }
  })
})
