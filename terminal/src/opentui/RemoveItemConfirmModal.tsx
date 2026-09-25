/** @jsxImportSource @opentui/react */
import type { KeyEvent } from "@opentui/core"
import { useRef } from "react"

import type { SaleItemView } from "../core/state"
import { isPrintable } from "./adapters/scanner"
import { BURST_MAX_INTERVAL_MS, useGlobalKeyboard } from "./keyboard"
import { ModalFrame } from "./ModalFrame"
import { theme } from "./theme"

/**
 * Confirmação da remoção do item (DEL e F3, passo 1110 portado no 1126a): o item selecionado na
 * venda, com o nome à vista, e o `DELETE` só quando o operador confirma — ESC fecha **sem** chamar
 * nada e a venda fica como estava.
 *
 * O modal é dono do próprio teclado (padrão do `LoginScreen`): como o `prependListener` do hook
 * global põe o listener mais novo na frente, ele recebe a tecla antes do listener da venda — o
 * `onKey` da venda devolve `false` enquanto isto está à vista e o scanner dela está desligado
 * (§11.3). O que o modal não usa, ele engole: nada vaza para a venda nem para o campo de leitura.
 *
 * A rajada do leitor que ainda assim chega aqui (o scanner pode ter sido desligado no meio de um
 * bipe) é reconhecida pela **velocidade**: caracteres em rajada são engolidos e cronometrados, e o
 * ENTER que vem logo depois é o terminador do bipe — não o "sim" do operador, que sempre chega
 * sozinho. Sem essa regra, o terminador da rajada confirmaria a remoção e a venda seria mexida por
 * um bipe (§11.3, aceite do 1126a).
 *
 * ENTER com o `DELETE` em voo não empilha chamada (uma mutação por vez, a mesma trava da venda) e a
 * falha transitória mantém o modal à vista com o aviso — o ENTER refaz. Quem decide o desfecho (a
 * venda do servidor, o 404, a tela de erro) é sempre a `runMutation` da tela de venda.
 */

export type RemoveItemConfirmModalProps = {
  /** Item que o DEL/F3 selecionou: o nome vai à vista e o `productId` é o alvo do `DELETE`. */
  item: SaleItemView
  /** `DELETE` em voo: o ENTER repetido não dispara duas chamadas e o ESC espera a resposta. */
  busy: boolean
  /** Aviso da falha transitória (o ENTER refaz); `null` na primeira tentativa. */
  notice: string | null
  /** ENTER do operador: a tela de venda chama a API e traduz o desfecho. */
  onConfirm: () => void
  /** ESC: fecha sem chamar nada. */
  onCancel: () => void
}

export function RemoveItemConfirmModal({
  item,
  busy,
  notice,
  onConfirm,
  onCancel,
}: RemoveItemConfirmModalProps) {
  /** Instante do último caractere imprimível: diz se o ENTER é humano ou o terminador do bipe. */
  const lastCharAt = useRef<number | null>(null)

  useGlobalKeyboard({ onKey: handleKey })

  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name === "escape") {
      // uma mutação por vez: com o DELETE em voo o modal espera a resposta antes de sair
      if (!busy) {
        onCancel()
      }

      return true
    }

    // caractere imprimível é rajada do leitor (ou digitação perdida): engolido e cronometrado
    if (isPrintable(event.sequence)) {
      lastCharAt.current = performance.now()
      return true
    }

    if (event.name === "return") {
      const at = performance.now()
      const burst = lastCharAt.current !== null && at - lastCharAt.current < BURST_MAX_INTERVAL_MS
      lastCharAt.current = null

      if (!burst && !busy) {
        onConfirm()
      }

      return true
    }

    // setas, TAB e F: quem as usa são os modais do 1126b/c/d; aqui elas morrem
    return true
  }

  return (
    <ModalFrame title="Remover item" hints="ENTER confirma · ESC cancela">
      <text wrapMode="none">{`remover ${item.name}?`}</text>
      <text fg={theme.muted} wrapMode="none">
        {"o item sai da venda e o servidor recalcula os totais"}
      </text>
      {notice === null ? null : (
        <text fg={theme.warning} wrapMode="none">
          {notice}
        </text>
      )}
      {busy ? (
        <text fg={theme.muted} wrapMode="none">
          {"removendo…"}
        </text>
      ) : null}
    </ModalFrame>
  )
}
