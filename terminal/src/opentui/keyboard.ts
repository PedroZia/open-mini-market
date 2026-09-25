import type { KeyEvent } from "@opentui/core"
import { useRenderer } from "@opentui/react"
import { useEffect, useRef, useState } from "react"

import type { KeyName } from "../core/keys"
import { createScanner, type ScannerEvent } from "../core/scanner"
import { keyEventToKeyName } from "./adapters/keys"
import { isPrintable, keyEventToScannerChar } from "./adapters/scanner"

/**
 * Teclado global da UI nova (§11.3): o listener mora no `renderer.keyInput` com `prependListener`,
 * então roda **antes** do renderable focado — o mesmo caminho do spike 1121 — e é aqui que a tecla
 * vira `KeyName` (`core/keys`) e o caractere vira leitura (`core/scanner`), sem canal cru.
 *
 * Regras de consumo (quem chama `preventDefault`/`stopPropagation`, e por quê):
 * - teclas do mapa (F1–F12, setas, DEL): consumidas, não vazam para o campo focado; ENTER/TAB/`+`/`-`
 *   são caractere de leitura e seguem as regras da rajada abaixo (o humano ainda os usa no campo);
 * - rajada do leitor: o terminador que fecha o bipe é consumido (não pode submeter o formulário) e
 *   os caracteres seguintes ao primeiro, em intervalo < 50 ms, também — senão a rajada vira texto no
 *   campo (o 1º caractere é ambíguo: o bipe só fecha no terminador; a **limpeza do campo é do 1125**);
 * - BACKSPACE fica com o campo (edição de texto, fora do mapa §11.3) e **ESC fica com o entry**
 *   (`installExitKey`, a última saída): consumir ESC com `stopPropagation` impediria o destroy, que
 *   é registrado antes deste hook.
 *
 * O `core/scanner` continua sendo a única fonte de verdade do que é leitura (timing, terminador,
 * multiplicador `n*`); aqui só se decide o que o campo focado pode receber.
 */

/** Mesmo limiar do `core/scanner` (`BURST_MAX_INTERVAL_MS`): rajada é o que não pode virar texto. */
const BURST_MAX_INTERVAL_MS = 50

export type UseGlobalKeyboardOptions = {
  /**
   * Recebe cada bipe fechado, com o código **bruto** e a quantidade do multiplicador — o hook não
   * interpreta nada (BR-14). Quem decide o destino é a tela (1125: reducer da venda).
   */
  onBarcode: (event: ScannerEvent) => void
}

export type GlobalKeyboard = {
  /** Último `KeyName` resolvido pelo adaptador — feedback da barra de status do shell. */
  lastKey: KeyName | null
}

export function useGlobalKeyboard({ onBarcode }: UseGlobalKeyboardOptions): GlobalKeyboard {
  const renderer = useRenderer()
  /** Um scanner por shell/tela, com o timing medido aqui (mesma regra da Ink). */
  const [scanner] = useState(createScanner)
  const [lastKey, setLastKey] = useState<KeyName | null>(null)
  /** Instante do último caractere imprimível: rajada (< 50 ms) não vira texto no campo focado. */
  const lastCharAtRef = useRef<number | null>(null)
  /** O callback mais novo entra por ref: o listener não se re-registra a cada render. */
  const onBarcodeRef = useRef(onBarcode)

  useEffect(() => {
    onBarcodeRef.current = onBarcode
  })

  useEffect(() => {
    const onKey = (event: KeyEvent): void => {
      const keyName = keyEventToKeyName(event)
      if (keyName !== null) {
        setLastKey(keyName)
      }

      const char = keyEventToScannerChar(event)
      if (char === null) {
        // BACKSPACE é edição de texto (fora do mapa §11.3) e ESC é a última saída do entry
        if (keyName !== null && keyName !== "ESC" && keyName !== "BACKSPACE") {
          event.preventDefault()
          event.stopPropagation()
        }

        return
      }

      const at = performance.now()
      const scan = scanner.feed(char, at)

      if (scan !== null) {
        // o terminador do bipe não insere/submete no campo; o buffer do scanner já foi limpo
        event.preventDefault()
        lastCharAtRef.current = null
        onBarcodeRef.current(scan)
        return
      }

      if (isPrintable(char)) {
        const fast =
          lastCharAtRef.current !== null && at - lastCharAtRef.current < BURST_MAX_INTERVAL_MS

        if (fast) {
          event.preventDefault()
        }

        lastCharAtRef.current = at
      }
    }

    renderer.keyInput.prependListener("keypress", onKey)
    return () => {
      renderer.keyInput.off("keypress", onKey)
    }
  }, [renderer, scanner])

  return { lastKey }
}
