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
 * - handler da tela (`onKey`, 1124a): tem a **primeira palavra** e o que ele consome não vira leitura
 *   nem chega ao renderable focado — é assim que o formulário de login recebe texto (a senha é
 *   mascarada pela tela, porque o `<input>` do OpenTUI não tem máscara);
 * - teclas do mapa (F1–F12, setas, DEL): consumidas, não vazam para o campo focado; ENTER/TAB/`+`/`-`
 *   são caractere de leitura e seguem as regras da rajada abaixo (o humano ainda os usa no campo);
 * - rajada do leitor: o terminador que fecha o bipe é consumido (não pode submeter o formulário) e
 *   os caracteres seguintes ao primeiro, em intervalo < 50 ms, também — senão a rajada vira texto no
 *   campo (o 1º caractere é ambíguo: o bipe só fecha no terminador; a **limpeza do campo é do 1125**);
 * - BACKSPACE fica com o campo (edição de texto, fora do mapa §11.3) e **ESC fica com o entry**
 *   (`installExitKey`, a última saída): consumir ESC com `stopPropagation` impediria o destroy, que
 *   é registrado antes deste hook;
 * - modal bloqueante (1126a): o componente do modal registra o **próprio** hook (o padrão do
 *   `LoginScreen`) e, como cada `prependListener` entra na frente, o modal recebe a tecla antes
 *   deste listener — enquanto ele está à vista, o `onKey` da tela devolve `false` e o scanner dela
 *   fica desligado por `barcodeEnabled`, então a tecla não vaza nem para a venda nem para o campo.
 *
 * O `core/scanner` continua sendo a única fonte de verdade do que é leitura (timing, terminador,
 * multiplicador `n*`); aqui só se decide o que o campo focado pode receber.
 */

/**
 * Mesmo limiar do `core/scanner` (`BURST_MAX_INTERVAL_MS`): rajada é o que não pode virar texto.
 * Exportado para os modais (1126a) reconhecerem o ENTER colado nos caracteres como o terminador do
 * bipe — e não como o "sim" do operador.
 */
export const BURST_MAX_INTERVAL_MS = 50

export type UseGlobalKeyboardOptions = {
  /**
   * Recebe cada bipe fechado, com o código **bruto** e a quantidade do multiplicador — o hook não
   * interpreta nada (BR-14). Quem decide o destino é a tela (1125: reducer da venda). Opcional
   * porque a tela de login (1124a) não tem bipe: o que ela consome não chega ao scanner.
   */
  onBarcode?: (event: ScannerEvent) => void
  /**
   * Handler da tela, com prioridade sobre o resto do hook (1124a): recebe a tecla **antes** do
   * scanner e devolve `true` quando a consumiu — texto do formulário, ENTER que envia, TAB que
   * troca o foco. A tecla consumida não vira leitura nem chega ao renderable focado; `false` segue
   * o caminho normal (mapa, rajada, campo). ESC não é consumível: é a última saída do entry.
   */
  onKey?: (event: KeyEvent) => boolean
  /**
   * Leitor ligado (padrão) ou desligado (§11.3): com um modal bloqueante à vista a tela passa
   * `false` e o `core/scanner` descarta buffer e multiplicador e ignora a entrada — a rajada do
   * leitor não vira item nem quando o modal fecha (o hook global fica atrás do handler do modal,
   * 1126a).
   */
  barcodeEnabled?: boolean
}

export type GlobalKeyboard = {
  /** Último `KeyName` resolvido pelo adaptador — feedback da barra de status do shell. */
  lastKey: KeyName | null
}

export function useGlobalKeyboard({ onBarcode, onKey, barcodeEnabled = true }: UseGlobalKeyboardOptions): GlobalKeyboard {
  const renderer = useRenderer()
  /** Um scanner por shell/tela, com o timing medido aqui (mesma regra da Ink). */
  const [scanner] = useState(createScanner)
  const [lastKey, setLastKey] = useState<KeyName | null>(null)
  /** Instante do último caractere imprimível: rajada (< 50 ms) não vira texto no campo focado. */
  const lastCharAtRef = useRef<number | null>(null)
  /** Os callbacks mais novos entram por ref: o listener não se re-registra a cada render. */
  const onBarcodeRef = useRef(onBarcode)
  const onKeyRef = useRef(onKey)

  useEffect(() => {
    onBarcodeRef.current = onBarcode
    onKeyRef.current = onKey
  })

  /** O leitor obedece ao contexto da tela (§11.3); desligar descarta buffer e multiplicador. */
  useEffect(() => {
    scanner.setEnabled(barcodeEnabled)
  }, [scanner, barcodeEnabled])

  useEffect(() => {
    const handleKeyPress = (event: KeyEvent): void => {
      const keyName = keyEventToKeyName(event)
      if (keyName !== null) {
        setLastKey(keyName)
      }

      // a tela tem a primeira palavra: o que ela consome não vira leitura nem vai para o campo
      if (onKeyRef.current?.(event) === true) {
        event.preventDefault()
        event.stopPropagation()
        return
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
        onBarcodeRef.current?.(scan)
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

    renderer.keyInput.prependListener("keypress", handleKeyPress)
    return () => {
      renderer.keyInput.off("keypress", handleKeyPress)
    }
  }, [renderer, scanner])

  return { lastKey }
}
