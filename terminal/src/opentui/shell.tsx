/** @jsxImportSource @opentui/react */
import { useTerminalDimensions } from "@opentui/react"

import { theme } from "./theme"

/**
 * Regiões do shell alvo (80×24): cabeçalho (1 linha), corpo (cresce) e barra de status (1 linha).
 *
 * A fundação não tem fluxo: nenhum estado, nenhum atalho, nenhuma chamada de API. As telas chegam
 * nos passos 1124+ e os adaptadores de teclado/leitor no 1123b; por ora o corpo é só o placeholder
 * com o nome da UI.
 */
export function Shell() {
  const { width, height } = useTerminalDimensions()

  return (
    <box flexDirection="column" width="100%" height="100%">
      <box height={1}>
        <text fg={theme.header}>{`PDV Minimercado · UI OpenTUI — fundação  ${width}x${height}`}</text>
      </box>

      <box flexGrow={1} flexDirection="column">
        <text fg={theme.text}>UI OpenTUI — fundação</text>
        <text fg={theme.muted}>as telas do PDV entram nos próximos passos</text>
      </box>

      <box height={1}>
        <text fg={theme.muted}>ESC sai</text>
      </box>
    </box>
  )
}
