/** @jsxImportSource @opentui/react */
import { expect, test } from "bun:test"
import type { KeyEvent } from "@opentui/core"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act, useState } from "react"

import type { ScannerEvent } from "../core/scanner"
import { createShutdown, installExitKey } from "./index"
import { useGlobalKeyboard } from "./keyboard"

/**
 * Aceite do 1123b (tela): o hook global intercepta a rajada **antes** do `<input>` focado, entrega o
 * código bruto pelo callback, não deixa a rajada virar texto e não engole o ESC de última saída.
 */

const BARCODE = "7891000000001"

/** Sonda do hook com um `<input>` focado — o callback é o que a venda (1125) liga no reducer. */
function Probe({ onBarcode }: { onBarcode: (event: ScannerEvent) => void }) {
  const { lastKey } = useGlobalKeyboard({ onBarcode })

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text>{`tecla: ${lastKey ?? "-"}`}</text>
      <input focused width={40} />
    </box>
  )
}

async function renderProbe(setup: Awaited<ReturnType<typeof testRender>>): Promise<string> {
  await setup.renderOnce()
  return setup.captureCharFrame()
}

test("rajada fecha no callback com o código bruto e não vira texto no input", async () => {
  const reads: ScannerEvent[] = []
  const setup = await testRender(<Probe onBarcode={(event) => reads.push(event)} />, {
    width: 60,
    height: 6,
  })

  try {
    await act(async () => {
      await setup.mockInput.typeText(BARCODE)
    })
    await act(async () => {
      setup.mockInput.pressEnter()
    })

    expect(reads).toEqual([{ type: "barcodeScanned", barcode: BARCODE, quantity: 1 }])
    // o 1º caractere da rajada pode entrar antes de o bipe fechar (o `\r` vem por último): a limpeza
    // do input é do 1125; o aceite aqui é o código inteiro não ter virado texto no campo
    expect(await renderProbe(setup)).not.toContain(BARCODE)
  } finally {
    setup.renderer.destroy()
  }
})

test("3* do operador vira quantidade 3 no evento do scanner", async () => {
  const reads: ScannerEvent[] = []
  const setup = await testRender(<Probe onBarcode={(event) => reads.push(event)} />, {
    width: 60,
    height: 6,
  })

  try {
    await act(async () => {
      await setup.mockInput.pressKeys(["3", "*"], 80)
    })
    await act(async () => {
      await setup.mockInput.typeText(BARCODE)
    })
    await act(async () => {
      setup.mockInput.pressEnter()
    })

    expect(reads).toEqual([{ type: "barcodeScanned", barcode: BARCODE, quantity: 3 }])
  } finally {
    setup.renderer.destroy()
  }
})

test("digitação humana (80 ms) não é leitura e entra no campo", async () => {
  const reads: ScannerEvent[] = []
  const setup = await testRender(<Probe onBarcode={(event) => reads.push(event)} />, {
    width: 60,
    height: 6,
  })

  try {
    await act(async () => {
      await setup.mockInput.typeText("1234567", 80)
    })

    expect(reads).toEqual([])
    expect(await renderProbe(setup)).toContain("1234567")
  } finally {
    setup.renderer.destroy()
  }
})

test("tecla do mapa (F3) é consumida antes dos demais listeners; texto comum passa", async () => {
  const setup = await testRender(<Probe onBarcode={() => {}} />, { width: 60, height: 6 })
  const seen: string[] = []
  setup.renderer.keyInput.on("keypress", (event: KeyEvent) => {
    seen.push(event.name)
  })

  try {
    await act(async () => {
      setup.mockInput.pressKey(KeyCodes.F3)
    })
    await act(async () => {
      setup.mockInput.pressKey("a")
    })

    expect(seen).toEqual(["a"])
  } finally {
    setup.renderer.destroy()
  }
})

/**
 * Sonda que liga/desliga o leitor pelo `/` (fora do mapa, não vira leitura): o mesmo
 * `scanner.setEnabled(false)` que a venda faz enquanto um modal está à vista (1126a).
 */
function ToggleProbe({ onBarcode }: { onBarcode: (event: ScannerEvent) => void }) {
  const [enabled, setEnabled] = useState(true)

  useGlobalKeyboard({
    onBarcode,
    barcodeEnabled: enabled,
    onKey: (event) => {
      if (event.sequence === "/") {
        setEnabled((current) => !current)
        return true
      }

      return false
    },
  })

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text>{`leitor: ${enabled ? "ligado" : "desligado"}`}</text>
      <input focused width={40} />
    </box>
  )
}

test("leitor desligado (§11.3) ignora a rajada e o buffer velho não vale ao religar", async () => {
  const reads: ScannerEvent[] = []
  const setup = await testRender(<ToggleProbe onBarcode={(event) => reads.push(event)} />, {
    width: 60,
    height: 6,
  })

  try {
    // `3*` com o leitor ligado deixa o multiplicador pendente para o próximo bipe
    await act(async () => {
      await setup.mockInput.pressKeys(["3", "*"], 80)
    })

    await act(async () => {
      setup.mockInput.pressKey("/")
    })

    await act(async () => {
      await setup.mockInput.typeText(BARCODE)
    })
    await act(async () => {
      setup.mockInput.pressEnter()
    })

    expect(reads).toEqual([]) // desligado: a rajada não emite nada

    // religar descarta buffer e multiplicador antigos: o próximo bipe vale 1, não 3
    await act(async () => {
      setup.mockInput.pressKey("/")
    })
    await act(async () => {
      await setup.mockInput.typeText(BARCODE)
    })
    await act(async () => {
      setup.mockInput.pressEnter()
    })

    expect(reads).toEqual([{ type: "barcodeScanned", barcode: BARCODE, quantity: 1 }])
  } finally {
    setup.renderer.destroy()
  }
})

test("o hook não engole o ESC de última saída do entry", async () => {
  const setup = await testRender(<Probe onBarcode={() => {}} />, { width: 60, height: 6 })
  const shutdown = createShutdown(setup.renderer)
  installExitKey(setup.renderer, shutdown)

  try {
    await act(async () => {
      setup.mockInput.pressEscape()
    })
    // o parser segura um ESC sozinho por ~20 ms (ambiguidade com sequências), achado do spike
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(setup.renderer.isDestroyed).toBe(true)
  } finally {
    shutdown()
  }
})
