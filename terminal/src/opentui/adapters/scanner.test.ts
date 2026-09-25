import { expect, test } from "bun:test"
import { KeyEvent, type ParsedKey } from "@opentui/core"
import { KeyCodes } from "@opentui/core/testing"

import { keyEventToScannerChar } from "./scanner"

/**
 * Aceite do 1123b (adaptador puro): o `KeyEvent` vira o caractere que o `core/scanner` come — ENTER
 * e TAB são terminadores, texto imprimível (espaço e `*` inclusive) passa como veio, e release,
 * ctrl/meta, ESC, setas e F1–F12 não são leitura.
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

test("ENTER e TAB viram os terminadores do leitor", () => {
  expect(keyEventToScannerChar(press("return", KeyCodes.RETURN))).toBe("\r")
  expect(keyEventToScannerChar(press("tab", KeyCodes.TAB))).toBe("\t")
})

test("caractere imprimível passa como veio (dígito, letra, espaço e o * do multiplicador)", () => {
  expect(keyEventToScannerChar(press("7", "7"))).toBe("7")
  expect(keyEventToScannerChar(press("a", "a"))).toBe("a")
  expect(keyEventToScannerChar(press("space", " "))).toBe(" ")
  expect(keyEventToScannerChar(press("*", "*"))).toBe("*")
  expect(keyEventToScannerChar(press("+", "+"))).toBe("+")
})

test("teclas sem texto não são leitura", () => {
  expect(keyEventToScannerChar(press("escape", KeyCodes.ESCAPE))).toBeNull()
  expect(keyEventToScannerChar(press("up", KeyCodes.ARROW_UP))).toBeNull()
  expect(keyEventToScannerChar(press("down", KeyCodes.ARROW_DOWN))).toBeNull()
  expect(keyEventToScannerChar(press("left", KeyCodes.ARROW_LEFT))).toBeNull()
  expect(keyEventToScannerChar(press("right", KeyCodes.ARROW_RIGHT))).toBeNull()
  expect(keyEventToScannerChar(press("delete", KeyCodes.DELETE))).toBeNull()
  expect(keyEventToScannerChar(press("backspace", KeyCodes.BACKSPACE))).toBeNull()
  expect(keyEventToScannerChar(press("f1", KeyCodes.F1))).toBeNull()
  expect(keyEventToScannerChar(press("f12", KeyCodes.F12))).toBeNull()
})

test("release e ctrl/meta não são leitura", () => {
  expect(keyEventToScannerChar(press("7", "7", { eventType: "release" }))).toBeNull()
  expect(keyEventToScannerChar(press("return", KeyCodes.RETURN, { eventType: "release" }))).toBeNull()
  expect(keyEventToScannerChar(press("7", "7", { ctrl: true }))).toBeNull()
  expect(keyEventToScannerChar(press("7", "7", { meta: true }))).toBeNull()
  expect(keyEventToScannerChar(press("return", KeyCodes.RETURN, { ctrl: true }))).toBeNull()
})
