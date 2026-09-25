import type { KeyEvent } from "@opentui/core"

import type { KeyName } from "../../core/keys"

/**
 * Adaptador puro `KeyEvent` (OpenTUI) → `KeyName` (`core/keys.ts`), o mesmo vocabulário do mapa de
 * atalhos (§11.3). O parser da OpenTUI entrega o **nome canônico** da tecla
 * (`f1`..`f12`, `return`, `escape`, `tab`, `up`..`right`, `backspace`, `delete`), então não existe
 * tabela de sequências cruas: quem traduz o evento bruto é este adaptador.
 *
 * Ficam de fora:
 * - `release` (o binding só entrega `press`/`repeat` por padrão; o kitty manda release e ele não é
 *   atalho);
 * - `ctrl`/`meta` (combo é do sistema/formulário, nunca do mapa — mesma regra do `resolveShortcut`);
 * - texto comum (dígitos, letras, espaço, `*`): não é atalho, fica com o campo ou com o leitor.
 *
 * `+`/`-` vêm no `sequence` (o `name` é o próprio caractere), tanto no teclado principal quanto no
 * numérico.
 */
const NAMED_KEYS: Readonly<Record<string, KeyName>> = {
  f1: "F1",
  f2: "F2",
  f3: "F3",
  f4: "F4",
  f5: "F5",
  f6: "F6",
  f7: "F7",
  f8: "F8",
  f9: "F9",
  f10: "F10",
  f11: "F11",
  f12: "F12",
  return: "ENTER",
  escape: "ESC",
  tab: "TAB",
  up: "UP",
  down: "DOWN",
  left: "LEFT",
  right: "RIGHT",
  backspace: "BACKSPACE",
  delete: "DEL",
}

/** Nome lógico da tecla, ou `null` quando ela é texto/evento que não pertence ao mapa (§11.3). */
export function keyEventToKeyName(event: KeyEvent): KeyName | null {
  if (event.eventType === "release" || event.ctrl || event.meta) {
    return null
  }

  const named = NAMED_KEYS[event.name]
  if (named !== undefined) {
    return named
  }

  if (event.sequence === "+") {
    return "PLUS"
  }

  return event.sequence === "-" ? "MINUS" : null
}
