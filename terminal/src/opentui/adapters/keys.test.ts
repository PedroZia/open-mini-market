import { expect, test } from "bun:test"
import { KeyEvent, type ParsedKey } from "@opentui/core"
import { KeyCodes } from "@opentui/core/testing"

import type { KeyName } from "../../core/keys"
import { keyEventToKeyName } from "./keys"

/**
 * Aceite do 1123b (adaptador puro): cada `KeyName` do `core/keys` sai do `KeyEvent` da OpenTUI pelo
 * nome canônico, e release/ctrl/meta/texto comum não são atalho.
 */

/** `KeyEvent` como o parser entrega no `keypress` (o adaptador só lê estes campos). */
function press(name: string, sequence: string, overrides: Partial<ParsedKey> = {}): KeyEvent {
  return new KeyEvent({
    name,
    sequence,
    ctrl: false,
    meta: false,
    shift: false,
    option: false,
    number: false,
    raw: sequence,
    eventType: "press",
    source: "raw",
    ...overrides,
  })
}

test("F1–F12 viram KeyName pelo nome canônico (sem tabela de sequências)", () => {
  const fKeys: ReadonlyArray<readonly [string, KeyName]> = [
    ["f1", "F1"],
    ["f2", "F2"],
    ["f3", "F3"],
    ["f4", "F4"],
    ["f5", "F5"],
    ["f6", "F6"],
    ["f7", "F7"],
    ["f8", "F8"],
    ["f9", "F9"],
    ["f10", "F10"],
    ["f11", "F11"],
    ["f12", "F12"],
  ]

  for (const [name, expected] of fKeys) {
    expect(keyEventToKeyName(press(name, ""))).toBe(expected)
  }
})

test("ENTER, ESC, TAB, setas, BACKSPACE e DEL viram KeyName", () => {
  expect(keyEventToKeyName(press("return", KeyCodes.RETURN))).toBe("ENTER")
  expect(keyEventToKeyName(press("escape", KeyCodes.ESCAPE))).toBe("ESC")
  expect(keyEventToKeyName(press("tab", KeyCodes.TAB))).toBe("TAB")
  expect(keyEventToKeyName(press("up", KeyCodes.ARROW_UP))).toBe("UP")
  expect(keyEventToKeyName(press("down", KeyCodes.ARROW_DOWN))).toBe("DOWN")
  expect(keyEventToKeyName(press("left", KeyCodes.ARROW_LEFT))).toBe("LEFT")
  expect(keyEventToKeyName(press("right", KeyCodes.ARROW_RIGHT))).toBe("RIGHT")
  expect(keyEventToKeyName(press("backspace", KeyCodes.BACKSPACE))).toBe("BACKSPACE")
  expect(keyEventToKeyName(press("delete", KeyCodes.DELETE))).toBe("DEL")
})

test("+ e - viram PLUS/MINUS pelo sequence", () => {
  expect(keyEventToKeyName(press("+", "+"))).toBe("PLUS")
  expect(keyEventToKeyName(press("-", "-"))).toBe("MINUS")
})

test("release e ctrl/meta não são atalho", () => {
  expect(keyEventToKeyName(press("f5", KeyCodes.F5, { eventType: "release" }))).toBeNull()
  expect(keyEventToKeyName(press("up", KeyCodes.ARROW_UP, { eventType: "release" }))).toBeNull()
  expect(keyEventToKeyName(press("return", KeyCodes.RETURN, { eventType: "release" }))).toBeNull()
  expect(keyEventToKeyName(press("f5", KeyCodes.F5, { ctrl: true }))).toBeNull()
  expect(keyEventToKeyName(press("f5", KeyCodes.F5, { meta: true }))).toBeNull()
  expect(keyEventToKeyName(press("1", "1", { ctrl: true }))).toBeNull()
})

test("texto comum não é atalho", () => {
  expect(keyEventToKeyName(press("7", "7"))).toBeNull()
  expect(keyEventToKeyName(press("a", "a"))).toBeNull()
  expect(keyEventToKeyName(press("space", " "))).toBeNull()
  expect(keyEventToKeyName(press("*", "*"))).toBeNull()
})
