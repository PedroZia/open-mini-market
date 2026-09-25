/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import type { Dispatch } from "react"

import { resolveShortcut } from "../core/keys"
import type { Action } from "../core/reducer"
import type { ApiProblem } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Falha bloqueante (§11.4) portada para a UI nova (1124b): mostra o `problem+json` (status, `code` e
 * `detail`) e o operador reconhece com ENTER ou ESC, voltando ao estado de origem **sem perder
 * nada** — quem guarda a origem é o reducer (1103). A resolução da tecla é a mesma do 1105, em
 * contexto `error` (`core/keys`), com o `KeyName` vindo do adaptador da OpenTUI.
 *
 * Aqui ESC é consumido de propósito: na tela de erro ele é o "voltar" (como na Ink), não a saída do
 * entry — deixar o `installExitKey` agir derrubaria o PDV em vez de reconhecer a falha.
 */
export function ErrorScreen({
  problem,
  dispatch,
}: {
  problem: ApiProblem
  dispatch: Dispatch<Action>
}) {
  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    const name = keyEventToKeyName(event)
    const shortcut = name === null ? null : resolveShortcut(name, { screen: "error", modal: null })

    if (shortcut === null || (shortcut.type !== "confirm" && shortcut.type !== "cancel")) {
      return false
    }

    dispatch(shortcut)
    return true
  }

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text attributes={TextAttributes.BOLD} fg={theme.danger}>
        Falha na operação
      </text>
      <text> </text>
      <text>{`${problem.status} — ${problem.code ?? "sem código"} — ${problem.detail}`}</text>
      <text> </text>
      <text fg={theme.muted}>ENTER/ESC para voltar</text>
    </box>
  )
}
