/** @jsxImportSource @opentui/react */
import { expect, test } from "bun:test"
import { testRender } from "@opentui/react/test-utils"

import { Shell } from "./shell"

/**
 * Aceite do 1123a: teste de fumaça do shell em 80×24, com as três regiões visíveis e dentro das
 * colunas/linhas do alvo.
 */

const FUNDACAO = "UI OpenTUI — fundação"

function frameLines(frame: string): string[] {
  const lines = frame.split("\n")
  if (lines.at(-1) === "") {
    lines.pop()
  }
  return lines
}

test("shell em 80x24 mostra cabeçalho, corpo e barra de status", async () => {
  const setup = await testRender(<Shell />, { width: 80, height: 24 })
  try {
    await setup.renderOnce()
    const frame = setup.captureCharFrame()
    const lines = frameLines(frame)

    expect(lines.length).toBe(24)
    expect(lines.every((line) => line.length <= 80)).toBe(true)

    // cabeçalho: primeira linha, com a dimensão viva do terminal
    expect(lines.at(0) ?? "").toContain("80x24")
    expect(lines.at(0) ?? "").toContain(FUNDACAO)

    // corpo: entre as duas barras
    const body = lines.findIndex((line) => line.includes(FUNDACAO) && !line.includes("80x24"))
    expect(body).toBeGreaterThan(0)
    expect(body).toBeLessThan(lines.length - 1)
    expect(frame).toContain("as telas do PDV entram nos próximos passos")

    // barra de status: última linha
    expect(lines.at(-1) ?? "").toContain("ESC sai")
  } finally {
    setup.renderer.destroy()
  }
})

test("shell acompanha o resize para 120x40", async () => {
  const setup = await testRender(<Shell />, { width: 80, height: 24 })
  try {
    await setup.renderOnce()

    setup.resize(120, 40)
    const frame = await setup.waitForFrame((value) => value.includes("120x40"))

    expect(frameLines(frame).length).toBe(40)
    expect(frame).toContain(FUNDACAO)
    expect(frame).toContain("ESC sai")
  } finally {
    setup.renderer.destroy()
  }
})

test("filho que lança não derruba o processo: boundary embutido do createRoot mostra o erro", async () => {
  const Boom = (): never => {
    throw new Error("fundacao-boom")
  }

  const setup = await testRender(<Boom />, { width: 40, height: 6 })
  try {
    await setup.renderOnce()
    expect(setup.captureCharFrame()).toContain("fundacao-boom")
  } finally {
    setup.renderer.destroy()
  }
})
