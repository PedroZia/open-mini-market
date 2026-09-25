/** @jsxImportSource @opentui/react */
import { expect, test } from "bun:test"
import { createTestRenderer } from "@opentui/core/testing"

import { createShutdown, installExitKey } from "./index"

/** Aceite do 1123a: shutdown em toda saída — ESC de última saída e destroy idempotente. */

test("ESC do entry destrói o renderer mesmo sem a árvore React", async () => {
  const setup = await createTestRenderer({ width: 20, height: 4 })
  const shutdown = createShutdown(setup.renderer)
  installExitKey(setup.renderer, shutdown)

  try {
    setup.mockInput.pressEscape()
    // o parser segura um ESC sozinho por ~20 ms (ambiguidade com sequências), achado do spike
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(setup.renderer.isDestroyed).toBe(true)
  } finally {
    shutdown()
  }
})

test("shutdown é idempotente depois do destroy", async () => {
  const setup = await createTestRenderer({ width: 20, height: 4 })
  const shutdown = createShutdown(setup.renderer)
  installExitKey(setup.renderer, shutdown)

  try {
    shutdown()
    expect(setup.renderer.isDestroyed).toBe(true)
    expect(() => shutdown()).not.toThrow()
  } finally {
    shutdown()
  }
})
