import { expect, test } from "bun:test"

import { resolveTheme, theme } from "./theme"

/** Aceite do 1123a: tema com fallback monocromático — sem paleta configurável. */

test("fora do NO_COLOR o tema nomeia as cores do PDV", () => {
  const resolved = resolveTheme({ TERM: "xterm-256color" })

  expect(resolved.color).toBe(true)
  expect(resolved.header).toBe("cyan")
  expect(resolved.accent).toBe("cyan")
  expect(resolved.success).toBe("green")
  expect(resolved.warning).toBe("yellow")
  expect(resolved.danger).toBe("red")
  expect(resolved.muted).toBe("gray")
  expect(resolved.text).toBeUndefined()
})

test("NO_COLOR cai no fallback monocromático (só a cor padrão do terminal)", () => {
  const resolved = resolveTheme({ NO_COLOR: "1", TERM: "xterm-256color" })

  expect(resolved.color).toBe(false)
  expect(
    Object.values(resolved).every((value) => value === false || value === undefined),
  ).toBe(true)
})

test("NO_COLOR vazio não desliga as cores (spec no-color.org)", () => {
  expect(resolveTheme({ NO_COLOR: "", TERM: "xterm-256color" }).color).toBe(true)
})

test("TERM=dumb cai no fallback monocromático", () => {
  expect(resolveTheme({ TERM: "dumb" }).color).toBe(false)
})

test("o tema resolvido no boot é imutável", () => {
  expect(Object.isFrozen(theme)).toBe(true)
})
