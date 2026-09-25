/** @jsxImportSource @opentui/react */
import { expect, jest, test } from "bun:test"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import { useClock } from "./clock"

/**
 * Relógio vivo da venda (1125a): a Ink passava `new Date()` no render do shell e a hora congelava
 * até a próxima ação do operador. O hook é testado com os **fake timers** do `bun:test`: o instante é
 * mockado antes do mount e o intervalo só dispara quando o teste avança o relógio, então nada
 * depende de tempo real — a hora do app é um tique por segundo.
 */

/** Sonda do hook: o frame mostra o instante em milissegundos (comparação independente de fuso). */
function ClockProbe() {
  const now = useClock(1000)
  return <text>{String(now.getTime())}</text>
}

test("o relógio nasce na hora corrente e anda a cada segundo", async () => {
  jest.useFakeTimers()
  jest.setSystemTime(new Date("2026-09-25T14:32:05Z"))

  const setup = await testRender(<ClockProbe />, { width: 40, height: 3 })

  try {
    await setup.renderOnce()
    expect(setup.captureCharFrame()).toContain("1790346725000")

    await act(async () => {
      jest.advanceTimersByTime(1000)
    })
    await setup.renderOnce()
    expect(setup.captureCharFrame()).toContain("1790346726000")

    await act(async () => {
      jest.advanceTimersByTime(2000)
    })
    await setup.renderOnce()
    expect(setup.captureCharFrame()).toContain("1790346728000")
  } finally {
    jest.useRealTimers()
    setup.renderer.destroy()
  }
})
