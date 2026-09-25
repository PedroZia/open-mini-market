/** @jsxImportSource @opentui/react */
import { TextAttributes, type KeyEvent, type ScrollBoxRenderable } from "@opentui/core"
import { useRenderer, useTerminalDimensions } from "@opentui/react"
import { useEffect, useRef, useState } from "react"

import type { CustomerOption } from "../api/terminalApi"
import { formatAmount } from "../core/money"
import { moveSelection, selectionIndex } from "../core/sale"
import type { SaleItemView, SaleOpenState } from "../core/state"
import { useGlobalKeyboard } from "./keyboard"
import { theme } from "./theme"

/**
 * Tela de venda (passos 1108 a 1110, §11.3) montada na UI nova (1125a): o layout do operador —
 * cabeçalho com loja, caixa, operador, hora e cliente, lista dos itens com o selecionado destacado,
 * painel de totais e barra de status com conexão e atalhos. O que a tela faz neste passo é o layout
 * e a navegação: o bipe e a leitura manual chegam no 1125b (com a fila de envio e o `api`/`dispatch`
 * que eles exigem), a quantidade do item no 1125c e os modais no 1126.
 *
 * A tela não calcula nada (BR-12): subtotal, desconto e total saem de `state.sale`, como o servidor
 * mandou; antes do primeiro bipe a venda é `null` e a tela mostra os zeros de exibição com o convite
 * ao primeiro bipe. A hora entra por prop — quem a mantém viva é o `useClock` do shell, porque o
 * `new Date()` do render da Ink congelava —, então o desenho é determinístico no teste.
 *
 * O nome do cliente é a anotação local do shell (1112): só aparece quando a venda do servidor aponta
 * para o mesmo `customerId`; o vínculo real é o do servidor. A loja também é rótulo: vem do
 * `GET /auth/me`, uma vez por login, e sem ela o cabeçalho segue.
 *
 * A lista é do `<scrollbox>` (F-04): em vez do `slice(-10)` da Ink — onde a seleção podia sair da
 * área visível e `+`/`-`/DEL agiam em item invisível —, a janela rola atrás do item selecionado e o
 * que ficou acima vira a linha "… N itens acima". A seleção é **local da tela** (não vai ao
 * reducer): `null` acompanha o último item e as setas fixam o índice com clamp nas pontas, sem
 * ciclo, como na Ink. O topo da janela é calculado no `saleWindow` e aplicado com `scrollTo`, então
 * a contagem acima e o que está à vista são o mesmo número.
 *
 * O quadro tem regiões fixas em 80×24 (cabeçalho, lista, totais, rodapé de **uma** linha e barra de
 * status): nada sobe ou desce quando o feedback ou o aviso aparecem — a "tela que dança" do
 * diagnóstico. O aviso do shell (1117) tem linha reservada acima da lista; a barra de status leva a
 * conexão e o caixa/operador/hora, com os atalhos (§11.3) logo abaixo.
 */
export type SaleScreenProps = {
  /** Estado do reducer: operador, caixa e a venda como o servidor devolveu (1103). */
  state: SaleOpenState
  /** Hora do cabeçalho: `useClock` no shell; data fixa no teste. */
  now: Date
  /** Cliente vinculado com o nome que a busca local capturou (1112); `null` na venda anônima. */
  customer: CustomerOption | null
  /** Loja do cabeçalho (`GET /auth/me`, 1117); `null` quando não chegou — o cabeçalho segue sem ela. */
  store: string | null
  /** Conexão com o servidor (1117): `false` mostra SEM CONEXÃO na barra de status. */
  online: boolean
}

/**
 * Piso da lista quando o terminal é pequeno ou o cabeçalho ganha a linha do cliente.
 *
 * Em 80×24 com o quadro cheio sobram 11 linhas para a lista — 10 delas quando a contagem do que
 * ficou acima ocupa uma linha, exatamente a janela prometida pelo §11.3 — e o resto da altura vira
 * janela em terminal maior (F-04).
 */
const MIN_ITEM_ROWS = 3

/**
 * Linhas fixas fora da lista em 80×24: cabeçalho 2 (título e operador/hora), aviso 1 (linha
 * reservada: quando o recado chega, nada desce), vazias 2, totais 3, rodapé 1 (feedback), conexão 1
 * e atalhos 3. Cliente e contagem entram como linhas extras.
 */
const FIXED_ROWS = 13

/** Atalhos da operação (§11.3) nas mesmas três linhas de 80 colunas da Ink. */
const SHORTCUT_ROWS = [
  "F1 Ajuda · F2 Preço · F3 Cancelar item · F4 Cancelar venda · F5 Desconto",
  "F6 Cliente · F7 Sangria · F8 Suprimento · F9 Pagamento · F10 Fechar caixa",
  "F11 Autoteste do leitor · F12 Trocar operador · ↑↓ itens · +/- qtd · DEL remove",
]

export function SaleScreen({ state, now, customer, store, online }: SaleScreenProps) {
  const renderer = useRenderer()
  const { height } = useTerminalDimensions()
  /** Item selecionado: `null` acompanha o último; as setas fixam o índice (1108/1110). */
  const [selected, setSelected] = useState<number | null>(null)
  /** O scrollbox não é focado: quem rola a janela é a seleção desta tela. */
  const scrollRef = useRef<ScrollBoxRenderable | null>(null)

  const sale = state.sale
  const items = sale?.items ?? []
  const selectedIndex = selectionIndex(selected, items.length)
  /**
   * Nome do cliente no cabeçalho: o vínculo real é o `customerId` que o servidor devolveu; o nome é
   * a anotação local que o shell capturou na busca (1112) — sem os dois casando, a venda é anônima
   * para esta tela.
   */
  const customerName =
    sale !== null && customer !== null && sale.customerId === customer.id ? customer.name : null
  const extraRows = customerName === null ? 0 : 1
  const room = Math.max(MIN_ITEM_ROWS, height - FIXED_ROWS - extraRows)
  const { top, rows } = saleWindow(room, items.length, selectedIndex)

  useGlobalKeyboard({ onKey: handleKey })

  /**
   * A janela segue a seleção (F-04): rola até o topo calculado — cada item ocupa uma linha, então o
   * índice do primeiro visível é o próprio deslocamento. No primeiro efeito o layout ainda não
   * existe (`scrollHeight` é 0) e o scroll é repetido no primeiro frame do renderer, como no spike.
   */
  useEffect(() => {
    const scrollToWindow = () => scrollRef.current?.scrollTo(top)

    scrollToWindow()
    renderer.once("frame", scrollToWindow)
    return () => {
      renderer.off("frame", scrollToWindow)
    }
  }, [renderer, top])

  /**
   * Teclado da venda (1125a): só as setas, que movem a seleção com clamp nas pontas. O resto
   * devolve `false` e segue o caminho normal do hook global — o bipe e a leitura manual são do 1125b.
   */
  function handleKey(event: KeyEvent): boolean {
    if (event.eventType === "release") {
      return false
    }

    if (event.name !== "up" && event.name !== "down") {
      return false
    }

    if (items.length === 0) {
      return true // lista vazia: não há seleção a mover (o `moveSelection` do core devolveria -1)
    }

    setSelected((current) => moveSelection(current, event.name === "up" ? -1 : 1, items.length))
    return true
  }

  return (
    <box flexDirection="column" width="100%" height="100%">
      <text fg={theme.header} attributes={TextAttributes.BOLD} wrapMode="none">
        {`PDV minimercado${store === null ? "" : ` · ${store}`} · ${state.register.name}`}
      </text>
      <text wrapMode="none">{`Operador: ${state.operator.name} · ${formatTime(now)}`}</text>
      {customerName === null ? null : <text wrapMode="none">{`Cliente: ${customerName}`}</text>}
      {/* linha do aviso é reservada: o recado do shell (1117) não empurra o quadro quando chega */}
      <text fg={theme.warning} wrapMode="none">
        {state.notice ?? " "}
      </text>
      <text> </text>
      {top === 0 ? null : (
        <text fg={theme.muted} wrapMode="none">{`… ${top} ${top === 1 ? "item" : "itens"} acima`}</text>
      )}
      <scrollbox ref={scrollRef} height={rows} scrollbarOptions={{ showArrows: false }}>
        {items.length === 0 ? (
          <text fg={theme.muted} wrapMode="none">
            bipar o primeiro item para iniciar a venda
          </text>
        ) : (
          items.map((item, index) => (
            <ItemRow key={item.productId} item={item} selected={index === selectedIndex} />
          ))
        )}
      </scrollbox>
      <text> </text>
      <text wrapMode="none">{`Subtotal: ${formatAmount(sale?.subtotal ?? 0)}`}</text>
      <text wrapMode="none">{`Desconto: ${formatAmount(sale?.discountAmount ?? 0)}`}</text>
      <text attributes={TextAttributes.BOLD} wrapMode="none">
        {`TOTAL: ${formatAmount(sale?.total ?? 0)}`}
      </text>
      {/* rodapé de uma linha: o desfecho da última operação (bipe, quantidade, remoção) entra aqui a
          partir do 1125b; a linha já fica reservada para o quadro não dançar quando ele aparecer */}
      <text> </text>
      {/* barra de status base: conexão, caixa/operador/hora (1117) e, abaixo, os atalhos da operação */}
      <text fg={online ? theme.success : theme.danger} wrapMode="none">
        {`Conexão: ${online ? "conectado" : "SEM CONEXÃO"} · ${state.register.name} · ${state.operator.name} · ${formatTime(now)}`}
      </text>
      {SHORTCUT_ROWS.map((row) => (
        <text key={row} fg={theme.muted} wrapMode="none">
          {row}
        </text>
      ))}
    </box>
  )
}

/** Linha de um item: o selecionado vai destacado — sem seta, o último, como no 1108 da Ink. */
function ItemRow({ item, selected }: { item: SaleItemView; selected: boolean }) {
  return (
    <text
      fg={selected ? theme.accent : theme.text}
      attributes={selected ? TextAttributes.BOLD : undefined}
      wrapMode="none"
    >
      {`${selected ? "›" : " "} ${formatQuantity(item.quantity)} x ${item.name} — ${formatAmount(item.lineTotal)}`}
    </text>
  )
}

/**
 * Janela da lista (F-04): o índice do primeiro item visível (`top`) e a altura do `<scrollbox>`.
 *
 * `room` é o espaço que sobra para a lista no quadro. Com item acima da janela, a linha da contagem
 * ocupa uma das linhas e o topo desce um item — o mesmo equilíbrio da Ink, que mostrava dez itens e
 * resumia o resto numa linha. Sem item acima (lista curta ou vazia), a janela usa o quadro inteiro.
 */
function saleWindow(
  room: number,
  itemCount: number,
  selected: number,
): { top: number; rows: number } {
  const top = Math.min(Math.max(selected - (room - 1), 0), Math.max(itemCount - room, 0))
  if (top === 0) {
    return { top: 0, rows: room }
  }

  const rows = room - 1
  return { top: Math.min(Math.max(selected - (rows - 1), 0), Math.max(itemCount - rows, 0)), rows }
}

/** Hora do cabeçalho em pt-BR, sempre com dois dígitos: `14:32:05`. */
function formatTime(now: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0")
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

/** Quantidade em pt-BR: inteira como `2`, fracionária como `0,750` (a de kg vem do servidor). */
function formatQuantity(quantity: number): string {
  return Number.isInteger(quantity) ? String(quantity) : quantity.toFixed(3).replace(".", ",")
}
