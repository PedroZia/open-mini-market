/** @jsxImportSource @opentui/react */
import { useTerminalDimensions } from "@opentui/react"
import { useState } from "react"

import { useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Regiões do shell alvo (80×24): cabeçalho (1 linha), corpo (cresce), campo focado (1 linha) e barra
 * de status (1 linha).
 *
 * Ainda não há fluxo: nenhum atalho despachado, nenhuma chamada de API. O teclado/leitor passam pelo
 * hook global (`keyboard.ts`), que resolve a tecla e alimenta o scanner do `core/`; a barra de
 * status mostra o que chegou para exercitar os adaptadores de verdade. O `<input>` focado existe
 * para provar que a rajada é interceptada **antes** do campo (a limpeza do que sobra nele é do
 * 1125); as telas de verdade entram nos passos 1124+.
 */
export function Shell() {
  const { width, height } = useTerminalDimensions()
  /** Último código bruto fechado pelo leitor (o destino real é a venda, 1125). */
  const [lastBarcode, setLastBarcode] = useState<string | null>(null)
  const { lastKey } = useGlobalKeyboard({ onBarcode: (event) => setLastBarcode(event.barcode) })

  return (
    <box flexDirection="column" width="100%" height="100%">
      <box height={1}>
        <text fg={theme.header}>{`PDV Minimercado · UI OpenTUI — fundação  ${width}x${height}`}</text>
      </box>

      <box flexGrow={1} flexDirection="column">
        <text fg={theme.text}>UI OpenTUI — fundação</text>
        <text fg={theme.muted}>as telas do PDV entram nos próximos passos</text>
      </box>

      <box height={1} flexDirection="row">
        <text fg={theme.muted}>Código: </text>
        <input focused width={40} placeholder="leitura manual + ENTER" />
      </box>

      <box height={1}>
        <text fg={theme.muted}>{`ESC sai · tecla: ${lastKey ?? "-"} · leitura: ${lastBarcode ?? "-"}`}</text>
      </box>
    </box>
  )
}
