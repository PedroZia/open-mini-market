/** @jsxImportSource @opentui/react */
import { createCliRenderer, type CliRenderer, type KeyEvent } from "@opentui/core"
import { createRoot } from "@opentui/react"

import { Shell } from "./shell"

/**
 * Shutdown idempotente de toda saída. `renderer.destroy()` já é idempotente (docs do ciclo de
 * vida), mas o guard deixa explícito que chamar de novo — erro depois de ESC, sinal depois de
 * Ctrl+C — não faz nada.
 */
export function createShutdown(renderer: CliRenderer): () => void {
  return () => {
    if (!renderer.isDestroyed) {
      renderer.destroy()
    }
  }
}

/**
 * ESC é a última saída e fica **fora** do React: o ErrorBoundary embutido do `createRoot` desmonta
 * a árvore quando um filho lança (achado do spike 1121), então o listener não pode morar nela.
 */
export function installExitKey(renderer: CliRenderer, shutdown: () => void): void {
  const onKey = (key: KeyEvent) => {
    if (key.name === "escape") {
      key.preventDefault()
      shutdown()
    }
  }

  renderer.keyInput.prependListener("keypress", onKey)
  renderer.once("destroy", () => {
    renderer.keyInput.off("keypress", onKey)
  })
}

/**
 * Entry: dono do renderer e de `renderer.destroy()` em toda saída.
 *
 * - normal: ESC (acima, fora do React);
 * - Ctrl+C e sinais (SIGINT/SIGTERM/...): `exitOnCtrlC` + handlers padrão do renderer, que chamam
 *   `destroy()`;
 * - erro de boot/mount: `catch` abaixo derruba o renderer antes de propagar; erro dentro de um
 *   componente fica no ErrorBoundary embutido do `createRoot` e não derruba o processo.
 */
export async function main(): Promise<void> {
  const renderer = await createCliRenderer({ exitOnCtrlC: true, targetFps: 30 })
  const shutdown = createShutdown(renderer)

  installExitKey(renderer, shutdown)

  try {
    createRoot(renderer).render(<Shell />)
  } catch (error) {
    shutdown()
    throw error
  }
}

if (import.meta.main) {
  await main()
}
