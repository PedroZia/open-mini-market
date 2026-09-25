/**
 * Tema da UI nova (OpenTUI) — cores nomeadas do PDV, com fallback monocromático.
 *
 * Sem paleta configurável (roadmap 1123a): o único interruptor é o fallback, ligado por `NO_COLOR`
 * (<https://no-color.org>, presente e não vazio) ou `TERM=dumb`. Nele os tokens ficam `undefined`,
 * ou seja, a cor padrão do terminal — o destaque passa a ser o próprio conteúdo (ex.: `>`).
 *
 * As capacidades do renderer ficaram de fora de propósito: `false` nelas significa "não suportado
 * **ou** ainda não detectado" (docs do renderer), então não servem como decisão de cor no boot.
 */

export type Theme = {
  /** `false` quando o terminal está sem cores (todos os tokens `undefined`). */
  readonly color: boolean
  readonly header: string | undefined
  readonly text: string | undefined
  readonly muted: string | undefined
  readonly accent: string | undefined
  readonly success: string | undefined
  readonly warning: string | undefined
  readonly danger: string | undefined
}

/** Cores nomeadas do PDV (mesmas da Ink: ciano, verde, vermelho e amarelo). */
const COLOR_THEME: Theme = Object.freeze({
  color: true,
  header: "cyan",
  text: undefined,
  muted: "gray",
  accent: "cyan",
  success: "green",
  warning: "yellow",
  danger: "red",
})

/** Fallback monocromático: tudo na cor padrão do terminal. */
const MONO_THEME: Theme = Object.freeze({
  color: false,
  header: undefined,
  text: undefined,
  muted: undefined,
  accent: undefined,
  success: undefined,
  warning: undefined,
  danger: undefined,
})

/** `false` só quando o ambiente pede explicitamente um terminal sem cores. */
function hasColor(env: Record<string, string | undefined>): boolean {
  const noColor = env["NO_COLOR"]
  if (noColor !== undefined && noColor !== "") {
    return false
  }

  return env["TERM"] !== "dumb"
}

export function resolveTheme(env: Record<string, string | undefined> = process.env): Theme {
  return hasColor(env) ? COLOR_THEME : MONO_THEME
}

/** Tema resolvido no boot (o ambiente não muda durante a execução). */
export const theme: Theme = resolveTheme()
