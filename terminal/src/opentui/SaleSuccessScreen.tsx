/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent } from "@opentui/core"
import type { Dispatch } from "react"

import { formatAmount } from "../core/money"
import type { Action } from "../core/reducer"
import type { ReceiptView } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Venda concluída (1113) portada para a UI nova (1127a): o resumo que o servidor devolveu no
 * `complete` — número, total e troco — no lugar da venda, esperando o ENTER que começa a próxima.
 * A TUI não calcula nada aqui (BR-12): os três valores são os do corpo da resposta, e o troco
 * (BR-05: só o dinheiro tem) fica em destaque quando existe. Quem limpa o resumo é o reducer
 * (`receiptDismissed`), não esta tela — com o resumo fora, a venda vazia espera o primeiro bipe.
 *
 * O leitor fica desligado enquanto o resumo está à vista (`barcodeEnabled: false`, §11.3): o que
 * esta tela consome é o ENTER do operador, e o resto das teclas segue o caminho de sempre (ESC
 * continua sendo a última saída do entry, como no corpo da venda).
 */
export function SaleSuccessScreen({
  receipt,
  dispatch,
}: {
  receipt: ReceiptView
  dispatch: Dispatch<Action>
}) {
  useGlobalKeyboard({ onKey: handleKey, barcodeEnabled: false })

  function handleKey(event: KeyEvent): boolean {
    if (keyEventToKeyName(event) !== "ENTER") {
      return false
    }

    dispatch({ type: "receiptDismissed" })
    return true
  }

  const hasChange = receipt.changeAmount > 0

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text fg={theme.success} attributes={TextAttributes.BOLD} wrapMode="none">
        {`Venda ${receipt.number} concluída`}
      </text>
      <text> </text>
      <text attributes={TextAttributes.BOLD} wrapMode="none">
        {`TOTAL: ${formatAmount(receipt.total)}`}
      </text>
      {/* o troco é o do servidor (BR-05) e fica em destaque quando existe */}
      <text
        fg={hasChange ? theme.success : theme.text}
        attributes={hasChange ? TextAttributes.BOLD : undefined}
        wrapMode="none"
      >
        {`TROCO: ${formatAmount(receipt.changeAmount)}`}
      </text>
      <text> </text>
      <text fg={theme.muted} wrapMode="none">
        ENTER inicia a próxima venda
      </text>
    </box>
  )
}
