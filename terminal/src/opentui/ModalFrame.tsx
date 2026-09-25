/** @jsxImportSource @opentui/react */
import type { ReactNode } from "react"

import { theme } from "./theme"

/**
 * Quadro único dos modais da UI nova (1126a): borda arredondada com o **título** na borda de cima e
 * o **rodapé** de dicas na de baixo (`bottomTitle`), conteúdo no meio, uma linha por `<text>` como
 * nas telas. Todos os modais da fase (F1, F2, F5, F6, F12, DEL...) desenham por aqui, então o
 * operador reconhece o mesmo quadro em qualquer um deles.
 *
 * O modal bloqueia por **saída de cena**: quem o usa o renderiza no lugar do corpo da tela (o
 * `SaleScreen` faz isso com o estado `modal`), e o leitor da tela fica desligado pelo shell
 * (`barcodeEnabled`, §11.3). O handler de teclado é do componente do modal — o `ModalFrame` é só a
 * moldura, sem opinião sobre o que a tecla faz.
 */

export type ModalFrameProps = {
  /** Título na borda de cima: o que o modal é (ex.: `Ajuda — atalhos da venda (F1)`). */
  title: string
  /** Dicas na borda de baixo: o que o ENTER/ESC fazem ali (ex.: `ENTER confirma · ESC cancela`). */
  hints: string
  /** Conteúdo do modal (uma linha por `<text>`, sem cálculo de layout). */
  children: ReactNode
}

/** Largura fixa: 66 colunas de borda a borda — o mapa de teclas do F1 cabe inteiro, sem cortar. */
const WIDTH = 66

export function ModalFrame({ title, hints, children }: ModalFrameProps) {
  return (
    <box
      width={WIDTH}
      flexDirection="column"
      borderStyle="rounded"
      borderColor={theme.accent}
      title={title}
      titleAlignment="center"
      bottomTitle={hints}
      bottomTitleAlignment="center"
    >
      {children}
    </box>
  )
}
