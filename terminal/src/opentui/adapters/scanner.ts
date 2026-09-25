import type { KeyEvent } from "@opentui/core"

/**
 * Adaptador puro `KeyEvent` (OpenTUI) → caractere do leitor (`core/scanner.ts`), na forma que o
 * `feed(char, atMs)` espera: ENTER vira `\r`, TAB vira `\t` e o resto é o **caractere** de
 * `sequence` (dígito, letra, espaço, `*` do multiplicador). O timing e a decisão de "isto é rajada"
 * continuam com o `core/scanner`: aqui não se mede nada (F-01).
 *
 * Ignorados: release e ctrl/meta (não são leitura), ESC, setas e F1–F12 (não têm texto) e qualquer
 * `sequence` que não seja um caractere imprimível — o que cobre DEL (`ESC [ 3 ~`) e BACKSPACE
 * (`\x7f`), que editam o campo em vez de virar código (BR-14: quem interpreta o código é o servidor).
 */
const NON_READING_NAMES: ReadonlySet<string> = new Set([
  "escape",
  "up",
  "down",
  "left",
  "right",
  ...Array.from({ length: 12 }, (_, index) => `f${index + 1}`),
])

/** Caractere do bipe que o evento carrega, ou `null` quando a tecla não é leitura. */
export function keyEventToScannerChar(event: KeyEvent): string | null {
  if (event.eventType === "release" || event.ctrl || event.meta) {
    return null
  }

  if (NON_READING_NAMES.has(event.name)) {
    return null
  }

  // o terminador do bipe não vem no `sequence` para todo terminal (kitty/modifyOtherKeys): é o nome
  // canônico que diz que a tecla é ENTER/TAB
  if (event.name === "return") {
    return "\r"
  }

  if (event.name === "tab") {
    return "\t"
  }

  return isPrintable(event.sequence) ? event.sequence : null
}

/** Mesma regra do `core/scanner`: um caractere com representação na tela (espaço incluso). */
export function isPrintable(char: string): boolean {
  return char.length === 1 && char >= " " && char !== "\u007f"
}
