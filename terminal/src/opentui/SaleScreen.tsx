/** @jsxImportSource @opentui/react */
import {
  TextAttributes,
  type InputRenderable,
  type KeyEvent,
  type ScrollBoxRenderable,
  type SubmitEvent,
} from "@opentui/core"
import { useRenderer, useTerminalDimensions } from "@opentui/react"
import { useEffect, useRef, useState, type Dispatch } from "react"

import type {
  CashMovementIntent,
  CashMovementKind,
  CashMovementView,
  CustomerOption,
  SaleDiscountIntent,
  SendFailure,
  TerminalApi,
} from "../api/terminalApi"
import { formatAmount } from "../core/money"
import { resolveShortcut } from "../core/keys"
import type { Action } from "../core/reducer"
import { moveSelection, nextQuantity, selectionIndex, touchedItem } from "../core/sale"
import { createScanner, type Scanner, type ScannerEvent } from "../core/scanner"
import type { ApiProblem, SaleItemView, SaleOpenState, SaleView, ScanIntent } from "../core/state"
import { keyEventToKeyName } from "./adapters/keys"
import { CancelSaleModal } from "./CancelSaleModal"
import { CashMovementModal, type CashMovementApplyResult } from "./CashMovementModal"
import { CustomerModal, type CustomerApplyResult } from "./CustomerModal"
import { DiscountModal, type DiscountApplyResult } from "./DiscountModal"
import { HelpModal } from "./HelpModal"
import { useGlobalKeyboard } from "./keyboard"
import { PriceLookupModal } from "./PriceLookupModal"
import { RemoveItemConfirmModal } from "./RemoveItemConfirmModal"
import { SwitchOperatorModal } from "./SwitchOperatorModal"
import { theme } from "./theme"

/**
 * Tela de venda (passos 1108 a 1110, §11.3) montada na UI nova (1125a–1125c): o layout do operador —
 * cabeçalho com loja, caixa, operador, hora e cliente, lista dos itens com o selecionado destacado,
 * campo de leitura sempre visível, painel de totais e barra de status — e a operação do F-01: vender
 * pelo código, bipado ou digitado, e corrigir a quantidade pelo `+`/`-` (1125c).
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
 * Teclado (§11.3): a rajada do leitor é interceptada pelo **hook global** (1123b) antes de qualquer
 * campo focado e chega pelo `onBarcode` com o código **bruto** e a quantidade do multiplicador
 * (BR-14); desta tela, o `onKey` resolve os atalhos da venda pelo **contexto** do `core/keys`
 * (`resolveShortcut` com `{ screen: 'saleOpen', modal: null }`, como o `useRawShortcuts` da Ink):
 * setas, `+`/`-`, DEL/F3 e o F1. O campo de leitura é a leitura **manual**: o ENTER entrega o texto
 * ao mesmo `core/scanner` como uma rajada sintética (caracteres + `\r` no mesmo instante, com o
 * `n*` valendo como multiplicador), então digitar o código vale tanto quanto bipá-lo. O que a rajada
 * do leitor deixou no campo é limpo no bipe — o primeiro caractere chega antes de o `\r` fechar a
 * leitura.
 *
 * Modais (1126a–1126d): a ajuda do F1, a confirmação do DEL/F3, a consulta de preço do F2, o
 * desconto do F5, o cliente do F6, a troca de operador do F12, o cancelamento da venda do F4 e a
 * gaveta do F7/F8 são estado **desta** tela (`modal`) e saem no `ModalFrame` no lugar do corpo da
 * venda — o leitor fica desligado (`barcodeEnabled: modal === null`, §11.3) e o `handleKey` daqui
 * devolve `false`: quem trata a tecla é o handler do próprio modal, que o hook global chama
 * **antes** deste listener (o `prependListener` põe o mais novo na frente) e que consome o que é
 * dele. Sem modal, a tela age como sempre. O F9 (1127a) e o F10 (1127b) não são modais: o reducer
 * troca a tela (`paymentStarted`/`cashClosingStarted`) e o shell monta o
 * `PaymentScreen`/`ClosingCashScreen` no lugar desta.
 *
 * O F6 (cliente) e o F12 (troca de operador) são anotações do **shell**: os dois modais devolvem o
 * fato por `onCustomerChanged` (o nome do cabeçalho é a seleção local da busca, não a resposta do
 * servidor) e `onOperatorSwitched` (o caixa em uso vira o preferido do login seguinte); a venda
 * recalculada continua vindo do `saleUpdated` da `runMutation`, como nas demais mutações. O F4
 * (1126d) é o outro lado disso: o servidor cancela a venda, o reducer volta ao estado vazio
 * (`saleCancelled`) e o cliente anotado — que era daquela venda — é esquecido. O F7/F8 (1126d) são
 * do **caixa aberto**, não da venda (BR-10): o movimento vai à API pela mesma trava de mutação da
 * venda e a confirmação do rodapé é o movimento que o servidor gravou (BR-12).
 *
 * O envio é uma fila local (`queueRef`) drenada pelo `pump`, um bipe por vez, na ordem em que
 * chegaram: sem venda criada, o primeiro bipe abre a venda (`POST /sales`) e o item entra em
 * seguida; sucesso vira `saleUpdated`, com a confirmação no rodapé e o bell; 404/422 avisam e o bipe
 * é consumido (`scanDismissed`); falha transitória mantém o bipe no topo e o ENTER refaz; bloqueante
 * vai para a tela de erro (`apiFailed`).
 *
 * A quantidade do item selecionado (1125c) é uma mutação por vez (`mutatingRef`, com a mesma trava
 * do envio): o `+`/`-` manda a quantidade **absoluta** no `PATCH` (nada de soma local — BR-12) e a
 * resposta do servidor vira `saleUpdated`; `-` que zeraria não vai à API e avisa para usar o DEL
 * (a remoção é o 1126). Enquanto há mutação ou bipe em voo o rodapé mostra "enviando…" e a tecla não
 * empilha chamada; no `finally` a trava cai e o `pump` drena o bipe que chegou no meio — nenhum bipe
 * se perde e a venda não é mexida por duas chamadas ao mesmo tempo. A remoção do 1126a entra pela
 * mesma `runMutation`, que devolve o desfecho para a confirmação decidir se sai de cena ou fica
 * esperando o ENTER refazer. O cadastro rápido do 404 é do 1128.
 *
 * A lista é do `<scrollbox>` (F-04): em vez do `slice(-10)` da Ink — onde a seleção podia sair da
 * área visível e `+`/`-`/DEL agiam em item invisível —, a janela rola atrás do item selecionado e o
 * que ficou acima vira a linha "… N itens acima". A seleção é **local da tela** (não vai ao
 * reducer): `null` acompanha o último item e as setas fixam o índice com clamp nas pontas, sem
 * ciclo, como na Ink. O topo da janela é calculado no `saleWindow` e aplicado com `scrollTo`, então
 * a contagem acima e o que está à vista são o mesmo número.
 *
 * O quadro tem regiões fixas em 80×24 (cabeçalho, lista, campo de leitura, totais, rodapé de
 * **uma** linha e barra de status): nada sobe ou desce quando o feedback ou o aviso aparecem — a
 * "tela que dança" do diagnóstico. O aviso do shell (1117) tem linha reservada acima da lista; a
 * barra de status leva a conexão e o caixa/operador/hora, com os atalhos (§11.3) logo abaixo.
 */
export type SaleScreenProps = {
  /** Estado do reducer: operador, caixa e a venda como o servidor devolveu (1103). */
  state: SaleOpenState
  /** Hora do cabeçalho: `useClock` no shell; data fixa no teste. */
  now: Date
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi
  /** Despacho do shell; toda transição nasce no reducer. */
  dispatch: Dispatch<Action>
  /** Cliente vinculado com o nome que a busca local capturou (1112); `null` na venda anônima. */
  customer: CustomerOption | null
  /** Loja do cabeçalho (`GET /auth/me`, 1117); `null` quando não chegou — o cabeçalho segue sem ela. */
  store: string | null
  /** Conexão com o servidor (1117): `false` mostra SEM CONEXÃO na barra de status. */
  online: boolean
  /**
   * Cliente vinculado mudou (F6, 1126c): o `App` guarda a anotação local do nome no cabeçalho —
   * `null` quando o vínculo foi removido. A venda em si já foi para o reducer pelo `saleUpdated`.
   */
  onCustomerChanged: (customer: CustomerOption | null) => void
  /**
   * Sessão encerrada pelo F12 (1126c): o `App` esquece as anotações locais, lembra o caixa em uso
   * para o login seguinte e volta ao login pelo reducer (`sessionEnded`).
   */
  onOperatorSwitched: () => void
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
 * reservada: quando o recado chega, nada desce), vazia 1, campo de leitura 1, totais 3, rodapé 1
 * (feedback), conexão 1 e atalhos 3. Cliente e contagem entram como linhas extras.
 */
const FIXED_ROWS = 13

/** Atalhos da operação (§11.3) nas mesmas três linhas de 80 colunas da Ink. */
const SHORTCUT_ROWS = [
  "F1 Ajuda · F2 Preço · F3 Cancelar item · F4 Cancelar venda · F5 Desconto",
  "F6 Cliente · F7 Sangria · F8 Suprimento · F9 Pagamento · F10 Fechar caixa",
  "F11 Autoteste do leitor · F12 Trocar operador · ↑↓ itens · +/- qtd · DEL remove",
]

/** Falha transitória do envio: o bipe fica na fila e o ENTER refaz (§11.3, retry manual). */
const SEND_FAILURE_NOTICE = "falha ao enviar o bipe — ENTER tenta de novo"

/** Falha transitória da mutação: o item fica como está e a mesma tecla refaz — não há fila de mutação. */
const QUANTITY_FAILURE_NOTICE = "falha ao falar com o servidor — +/- tenta de novo"

/** Falha transitória da remoção (1126a): a confirmação fica à vista e o ENTER refaz o `DELETE`. */
const REMOVE_FAILURE_NOTICE = "falha ao remover o item — ENTER tenta de novo"

/** Falha transitória do desconto (1126b): o formulário fica à vista e o ENTER refaz o `PUT`. */
const DISCOUNT_FAILURE_NOTICE = "falha ao aplicar o desconto — ENTER tenta de novo"

/** Falha transitória do vínculo do cliente (F6, 1126c): o modal fica à vista e o ENTER refaz o `PUT`. */
const LINK_FAILURE_NOTICE = "falha ao vincular o cliente — ENTER tenta de novo"

/** Falha transitória da remoção do vínculo (F6, 1126c): o modal fica à vista e o DEL refaz o `DELETE`. */
const UNLINK_FAILURE_NOTICE = "falha ao remover o cliente — DEL tenta de novo"

/** Confirmação do F4 (1126d): a venda foi descartada pelo servidor e a tela voltou ao estado vazio. */
const SALE_CANCELLED_NOTICE = "venda cancelada"

/**
 * Confirmação do F7/F8 (1126d) no rodapé: o movimento que o servidor gravou, na voz de cada um —
 * o valor exibido é o dele, da mesma transação (BR-12).
 */
const CASH_MOVEMENT_LABELS: Readonly<Record<CashMovementKind, string>> = {
  withdrawal: "sangria registrada",
  supply: "suprimento registrado",
}

/** `-` que zeraria a quantidade não vai à API: quem remove o item é o DEL (com confirmação, 1126). */
const REMOVE_HINT = "use DEL para remover o item"

/** Item que sumiu entre a leitura e a ação (404 `SALE_ITEM_NOT_FOUND`): a venda segue como está. */
const missingItem = (name: string) => `item já não está na venda: ${name}`

/** Dica do campo de leitura: o bipe não precisa ser digitado, mas o campo é o caminho manual (F-01). */
const MANUAL_PLACEHOLDER = "bipe ou digite o código e ENTER"

/** `n*` no começo do texto digitado: o multiplicador do próximo bipe (regra do `core/scanner`). */
const MANUAL_MULTIPLIER = /^(\d+)\*/

/**
 * Intervalo sintético entre os dígitos e o `*` do multiplicador na leitura manual: é o mesmo limiar
 * da rajada (`core/scanner`), então o `*` chega "devagar" e vale como multiplicador, não como
 * conteúdo do código (BR-14).
 */
const MANUAL_MULTIPLIER_GAP_MS = 50

/** Estado do rodapé: o desfecho da última operação, em uma linha só para o frame caber nas 24. */
type Feedback =
  | { kind: "success"; text: string }
  | { kind: "notice"; text: string }
  | { kind: "failure"; text: string }

/**
 * Modal bloqueante aberto sobre a venda (1126a–1126d): a ajuda do F1, a confirmação do DEL/F3, a
 * consulta de preço do F2, o desconto do F5, o cliente do F6, a troca de operador do F12, o
 * cancelamento do F4 (o id da venda fica guardado: alvo fixo do `POST .../cancel`) e a gaveta do
 * F7/F8. O item guardado na confirmação é o alvo fixo do `DELETE` — com o modal à vista a seleção
 * não se move.
 */
type SaleModal =
  | { kind: "help" }
  | { kind: "removeItemConfirm"; item: SaleItemView }
  | { kind: "priceLookup" }
  | { kind: "discount" }
  | { kind: "customer" }
  | { kind: "switchOperator" }
  | { kind: "cancelSale"; saleId: string }
  | { kind: "cashMovement"; movement: CashMovementKind }

/**
 * Desfecho de uma mutação da venda (1125c/1126a/1126b) para quem a pediu decidir o que fica à
 * vista: `applied` é a venda que o servidor recalculou e já entrou no estado; `notFound` é o item
 * que sumiu entre a leitura e a ação; `rejected` é a recusa que o **próprio modal** mostra (o
 * desconto do F5); `retryable` pede a mesma tecla de novo; `failed` já foi para a tela de erro.
 */
type MutationResult =
  | { kind: "applied" }
  | { kind: "notFound" }
  | { kind: "rejected"; message: string }
  | { kind: "retryable" }
  | { kind: "failed" }

/** Desfecho de uma mutação da venda, como as rotas a devolvem (item, remoção ou desconto). */
type SaleMutationOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; kind: "notFound" }
  | { ok: false; kind: "rejected"; message: string }
  | SendFailure

export function SaleScreen({
  state,
  now,
  api,
  dispatch,
  customer,
  store,
  online,
  onCustomerChanged,
  onOperatorSwitched,
}: SaleScreenProps) {
  const renderer = useRenderer()
  const { height } = useTerminalDimensions()
  /** Item selecionado: `null` acompanha o último; as setas fixam o índice (1108/1110). */
  const [selected, setSelected] = useState<number | null>(null)
  /** O scrollbox não é focado: quem rola a janela é a seleção desta tela. */
  const scrollRef = useRef<ScrollBoxRenderable | null>(null)
  /** Campo de leitura manual: o único renderable focado — a rajada do leitor passa por cima dele. */
  const inputRef = useRef<InputRenderable | null>(null)
  /** Buffer da leitura manual (o do leitor é do hook global): alimentado no ENTER do campo. */
  const [manualScanner] = useState(createScanner)
  /** Bipes fechados aguardando envio, na ordem em que chegaram: nenhum bipe se perde. */
  const queueRef = useRef<ScanIntent[]>([])
  /** Um envio por vez; quem já está enviando pega a fila atualizada, não empilha chamadas. */
  const sendingRef = useRef(false)
  /** Mutação de item em voo (`PATCH`): trava `+`/`-` (e o DEL do 1126) e põe "enviando…" no rodapé. */
  const mutatingRef = useRef(false)
  /** Estado da última render: o `pump` roda fora do render e lê o id da venda daqui. */
  const latest = useRef(state)
  /** Texto do campo de leitura manual: o bipe e o ENTER o limpam (o renderable também é zerado). */
  const [manual, setManual] = useState("")
  /** Desfecho da última operação no rodapé: verde no sucesso, amarelo no aviso, vermelho na falha. */
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  /** Espelho de `mutatingRef` para o rodapé: a trava em si é do ref (lida fora do render). */
  const [mutating, setMutating] = useState(false)
  /** Modal bloqueante à vista (1126a): `null` é a venda; com modal o corpo sai de cena. */
  const [modal, setModal] = useState<SaleModal | null>(null)
  /** `DELETE` da confirmação em voo: trava o ENTER repetido e mostra "removendo…" no modal. */
  const [removing, setRemoving] = useState(false)
  /** Aviso transitório da remoção, dentro do modal: a confirmação fica à vista e o ENTER refaz. */
  const [removalNotice, setRemovalNotice] = useState<string | null>(null)

  useEffect(() => {
    latest.current = state
  })

  /**
   * Teclado da tela no hook global: atalhos da venda resolvidos por contexto (setas, `+`/`-`, DEL/F3
   * e F1) e o ENTER do retry do bipe; o bipe chega no `onBarcode`. Com modal à vista o leitor fica
   * **desligado** (§11.3) — o `handleKey` daqui devolve `false` e quem trata a tecla é o modal.
   */
  useGlobalKeyboard({ onBarcode: handleBarcode, onKey: handleKey, barcodeEnabled: modal === null })

  const sale = state.sale
  const items = sale?.items ?? []
  const selectedIndex = selectionIndex(selected, items.length)
  /** Item que o `+`/`-` (1125c) e o DEL (1126) usam como alvo: fora da lista não há alvo. */
  const selectedItem = selectedIndex < 0 ? null : (items[selectedIndex] ?? null)
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
   * Teclado da venda resolvido pelo contexto do `core/keys` (§11.3, como o `useRawShortcuts` da
   * Ink): as setas movem a seleção com clamp nas pontas, `+`/`-` mexem na quantidade do item
   * selecionado, DEL e F3 abrem a **mesma** confirmação de remoção, o F1 abre a ajuda, o F2 a
   * consulta de preço (sem venda criada inclusive), o F5 o desconto (só com venda criada), o F6 o
   * cliente (também só com venda criada), o F12 a troca de operador (sempre: sem venda é confirmação
   * direta), o F4 o cancelamento da venda (só com venda criada, como o desconto) e o F7/F8 a gaveta
   * (sempre: sangrar e suprir são do caixa aberto, BR-10) — tudo consumido, não chega ao campo de
   * leitura —, e o ENTER refaz o bipe que ficou na fila depois de uma falha transitória. O F9 (1127a)
   * abre o pagamento e o F10 (1127b) o fechamento de caixa — os dois substituem esta tela no shell
   * (a venda vai preservada para o ESC do fechamento e quem recusa fechar com venda aberta é o
   * servidor, 409 `SESSION_HAS_OPEN_SALES`); o F11 (1129) não age aqui: a tecla devolve `false` e o
   * hook global a engole pelo mapa.
   *
   * Com um modal à vista (1126a–1126d) a tela **não** age: o handler devolve `false` e quem
   * trata a tecla é o handler do próprio modal, que roda antes deste (o `prependListener` do hook
   * global põe o listener mais novo na frente) e consome o que é dele — nenhuma tecla vaza entre os
   * dois.
   */
  function handleKey(event: KeyEvent): boolean {
    if (modal !== null) {
      return false
    }

    const keyName = keyEventToKeyName(event)
    const shortcut =
      keyName === null ? null : resolveShortcut(keyName, { screen: "saleOpen", modal: null })

    if (shortcut !== null && shortcut.type === "intent") {
      switch (shortcut.name) {
        case "itemUp":
        case "itemDown": {
          if (items.length > 0) {
            // forma funcional: duas setas no mesmo tick (autorepeat, teste) compõem em vez de repetir
            const delta = shortcut.name === "itemUp" ? -1 : 1
            setSelected((current) => moveSelection(current, delta, items.length))
          }

          return true
        }

        case "quantityUp":
          void changeQuantity(1)
          return true

        case "quantityDown":
          void changeQuantity(-1)
          return true

        case "removeItem":
        case "cancelItem": // DEL e F3 fazem o mesmo: uma confirmação, um caminho
          askRemove()
          return true

        case "help":
          setModal({ kind: "help" })
          return true

        case "priceLookup":
          // F2 consulta sem vender: abre mesmo sem venda criada, como na Ink (1116)
          setModal({ kind: "priceLookup" })
          return true

        case "discount":
          // F5 só com venda criada: sem venda não há o que descontar (a venda nasce no primeiro bipe)
          if (sale !== null) {
            setModal({ kind: "discount" })
          }

          return true

        case "customer":
          // F6 só com venda criada (como o desconto): sem venda não há onde vincular cliente
          if (sale !== null) {
            setModal({ kind: "customer" })
          }

          return true

        case "switchOperator":
          // F12 abre sempre (F12 sem venda é confirmação direta): a troca nunca é silenciosa — o
          // modal mostra a venda aberta que será cancelada antes de encerrar a sessão
          setModal({ kind: "switchOperator" })
          return true

        case "cancelSale":
          // F4 só com venda criada (como o desconto): sem venda não há o que cancelar; o id fica
          // guardado no modal — alvo fixo do `POST .../cancel`
          if (sale !== null) {
            setModal({ kind: "cancelSale", saleId: sale.id })
          }

          return true

        case "withdrawal":
        case "supply":
          // F7/F8 são do caixa aberto (BR-10), não da venda: abrem mesmo antes do primeiro bipe
          setModal({
            kind: "cashMovement",
            movement: shortcut.name === "withdrawal" ? "withdrawal" : "supply",
          })
          return true

        case "checkout":
          // F9 abre o pagamento (1127a): sem venda criada não há o que pagar e com o resumo da venda
          // concluída à vista quem decide é o ENTER dele; o reducer ignora a venda vazia (BR-05)
          if (sale !== null && state.receipt === null) {
            dispatch({ type: "paymentStarted" })
          }

          return true

        case "closeCash":
          // F10 abre o fechamento (1127b): a venda em andamento vai preservada para o ESC da tela de
          // fechamento e quem recusa fechar com venda aberta é o servidor (409 SESSION_HAS_OPEN_SALES);
          // com o resumo da venda concluída à vista o ENTER é dele (1127a)
          if (state.receipt === null) {
            dispatch({ type: "cashClosingStarted" })
          }

          return true

        default:
          return false // F11 é do 1129
      }
    }

    // com uma falha de envio à vista, o ENTER refaz a chamada do bipe que ficou na fila
    if (keyName === "ENTER" && feedback?.kind === "failure") {
      void pump()
      return true
    }

    return false
  }

  /**
   * Bipe do leitor (1123b): a rajada já foi interceptada antes do campo, então aqui o evento entra
   * na fila e o envio começa. O primeiro caractere da rajada pode ter entrado no campo antes de o
   * `\r` fechar a leitura (achado do spike) — por isso o campo é zerado no bipe.
   */
  function handleBarcode(scan: ScannerEvent): void {
    clearManual()
    enqueue({ barcode: scan.barcode, quantity: scan.quantity })
  }

  /** Fila + intenção no reducer: o bipe fica pendente até o servidor responder e o envio é o `pump`. */
  function enqueue(scan: ScanIntent): void {
    queueRef.current.push(scan)
    dispatch({ type: "barcodeScanned", barcode: scan.barcode, quantity: scan.quantity })
    void pump()
  }

  /** Limpa o campo de leitura: o `value` do renderable (pode ter recebido a rajada) e o estado dele. */
  function clearManual(): void {
    if (inputRef.current !== null) {
      inputRef.current.value = ""
    }

    setManual("")
  }

  /**
   * ENTER do campo de leitura: o texto digitado é uma leitura (F-01) e entra no `core/scanner` como
   * a rajada do leitor — o `n*` do operador vira o multiplicador, então `3*` funciona digitado como
   * funciona bipado. O código segue **bruto** (BR-14) e o campo sai limpo para a próxima leitura.
   */
  function submitManual(value: string | SubmitEvent): void {
    const text = typeof value === "string" ? value : ""
    clearManual()

    if (text === "") {
      return
    }

    // um caractere sozinho não é leitura (a mesma regra do `MIN_BURST_LENGTH` do scanner)
    const scan = feedManualText(manualScanner, text)
    if (scan === null) {
      return
    }

    enqueue({ barcode: scan.barcode, quantity: scan.quantity })
  }

  /** Uma mutação por vez: com o `PATCH` ou um bipe em voo, `+`/`-` (e o DEL do 1126) não disparam nada. */
  function busy(): boolean {
    return mutatingRef.current || sendingRef.current
  }

  /**
   * `+`/`-` no item selecionado (1125c): a quantidade nova vai **absoluta** no `PATCH` (o `{itemId}`
   * do contrato é o `productId`, decisão do 802/809b) e quem recalcula a linha e os totais é o
   * servidor (BR-01, BR-12) — a resposta vira `saleUpdated`. O passo é por unidade do produto: 1 em
   * `UN` e 0,1 em `KG` (`nextQuantity` do core — granularidade de entrada, não cálculo). `-` que
   * levaria a ≤ 0 não chama a API: quem remove o item é o DEL (1126).
   */
  async function changeQuantity(direction: 1 | -1): Promise<void> {
    const item = selectedItem

    if (item === null || busy()) {
      return
    }

    const quantity = nextQuantity(item.quantity, item.unit, direction)

    if (quantity <= 0) {
      setFeedback({ kind: "notice", text: REMOVE_HINT })
      return
    }

    await runMutation(
      (saleId) => api.changeSaleItemQuantity(saleId, item.productId, quantity),
      item,
      (sale) => describeQuantity(touchedItem(latest.current.sale, sale)),
      QUANTITY_FAILURE_NOTICE,
    )
  }

  /**
   * DEL e F3 (1126a): abre a confirmação do item selecionado — nada vai à API antes do ENTER. É o
   * mesmo caminho dos dois (o mapa do `core/keys` resolve `removeItem` e `cancelItem`), então a
   * confirmação é uma só.
   */
  function askRemove(): void {
    if (selectedItem === null || busy()) {
      return
    }

    setRemovalNotice(null)
    setModal({ kind: "removeItemConfirm", item: selectedItem })
  }

  /** Fecha o modal e limpa o que era dele — a próxima abertura nasce sem aviso velho. */
  function closeModal(): void {
    setModal(null)
    setRemovalNotice(null)
  }

  /** Falha bloqueante de um modal (consulta do F2): o problema vai para a tela de erro e o modal sai. */
  function failModal(problem: ApiProblem): void {
    closeModal()
    dispatch({ type: "apiFailed", problem })
  }

  /**
   * ENTER da confirmação (1126a): o `DELETE` do item que o modal guardou, pela mesma `runMutation`
   * da quantidade (uma mutação por vez, a fila de bipes drenada no `finally`). O desfecho decide o
   * modal: sucesso fecha e o rodapé confirma `removido: <nome>`; 404 fecha e avisa; falha
   * transitória o mantém à vista com o aviso e o ENTER refaz; bloqueante vai para a tela de erro.
   */
  async function removeConfirmedItem(): Promise<void> {
    const item = modal?.kind === "removeItemConfirm" ? modal.item : null

    if (item === null || removing) {
      return
    }

    setRemoving(true)
    const result = await runMutation(
      (saleId) => api.removeSaleItem(saleId, item.productId),
      item,
      () => `removido: ${item.name}`,
      REMOVE_FAILURE_NOTICE,
    )
    setRemoving(false)

    if (result.kind === "retryable") {
      setRemovalNotice(REMOVE_FAILURE_NOTICE) // a confirmação fica à vista: o ENTER refaz
      return
    }

    closeModal() // sucesso, 404 do item que sumiu ou falha bloqueante: a confirmação sai de cena
  }

  /**
   * ENTER do desconto (F5, 1126b): o `PUT` vai pela **mesma** `runMutation` da quantidade e da
   * remoção — uma mutação por vez, com a fila de bipes drenada no `finally` — e o desfecho é
   * traduzido para o formulário: o sucesso registra a venda recalculada pelo servidor e fecha o
   * modal; a recusa (limite da loja, permissão, forma) volta com a mensagem dele para o modal
   * continuar à vista; a falha transitória pede o ENTER de novo; a bloqueante já foi para a tela de
   * erro e o modal sai de cena.
   */
  async function applyDiscountFromModal(intent: SaleDiscountIntent): Promise<DiscountApplyResult> {
    const result = await runMutation(
      (saleId) => api.applyDiscount(saleId, intent),
      null,
      describeDiscount,
      DISCOUNT_FAILURE_NOTICE,
    )

    if (result.kind === "applied") {
      closeModal()
      return { kind: "applied" }
    }

    if (result.kind === "rejected") {
      return { kind: "rejected", message: result.message }
    }

    if (result.kind === "retryable") {
      return { kind: "retryable" }
    }

    closeModal() // item que sumiu (não há no desconto) ou falha bloqueante: nada fica à vista
    return { kind: "failed" }
  }

  /**
   * ENTER do cliente (F6, 1126c): o `PUT /sales/{id}/customer` vai pela **mesma** `runMutation` da
   * quantidade, da remoção e do desconto — uma mutação por vez, com a venda recalculada pelo
   * servidor virando `saleUpdated` — e o desfecho é traduzido para o modal como no desconto. O nome
   * do cabeçalho é a anotação local da busca: o `onCustomerChanged` a leva ao shell.
   */
  async function linkCustomerFromModal(option: CustomerOption): Promise<CustomerApplyResult> {
    const result = await runMutation(
      (saleId) => api.linkCustomer(saleId, option.id),
      null,
      () => `cliente: ${option.name}`,
      LINK_FAILURE_NOTICE,
    )

    return customerResult(result, () => onCustomerChanged(option))
  }

  /**
   * DEL do modal (F6, 1126c): o `DELETE` do vínculo pela mesma `runMutation`; a venda volta anônima
   * e o shell esquece o nome junto (`onCustomerChanged(null)`) — o vínculo real é o do servidor.
   */
  async function unlinkCustomerFromModal(): Promise<CustomerApplyResult> {
    const result = await runMutation(
      (saleId) => api.unlinkCustomer(saleId),
      null,
      () => "cliente removido da venda",
      UNLINK_FAILURE_NOTICE,
    )

    return customerResult(result, () => onCustomerChanged(null))
  }

  /**
   * Desfecho da `runMutation` como o modal do cliente o espera (o mesmo contrato do desconto,
   * 1126b): o sucesso aplica o que só o shell sabe (a anotação do nome) e fecha o modal; a recusa e
   * a falha transitória voltam para ele; o resto (404 do item, que não há no cliente, ou falha
   * bloqueante) sai de cena — o problema bloqueante já foi para a tela de erro.
   */
  function customerResult(result: MutationResult, applied: () => void): CustomerApplyResult {
    if (result.kind === "applied") {
      applied()
      closeModal()
      return { kind: "applied" }
    }

    if (result.kind === "rejected") {
      return { kind: "rejected", message: result.message }
    }

    if (result.kind === "retryable") {
      return { kind: "retryable" }
    }

    closeModal()
    return { kind: "failed" }
  }

  /**
   * F12 confirmado (1126c): a sessão de login terminou e o caixa continua aberto — o modal sai de
   * cena e o shell lembra o caixa em uso para o login seguinte nascer com ele selecionado.
   */
  function switchedOperator(): void {
    closeModal()
    onOperatorSwitched()
  }

  /**
   * F4 confirmado (1126d): o servidor cancelou a venda — o reducer volta ao estado vazio
   * (`saleCancelled`), o cliente anotado (que era daquela venda) é esquecido e o rodapé confirma. A
   * venda cancelada é imutável no servidor; o próximo bipe abre uma venda nova.
   */
  function saleCancelled(): void {
    closeModal()
    onCustomerChanged(null)
    dispatch({ type: "saleCancelled" })
    setFeedback({ kind: "success", text: SALE_CANCELLED_NOTICE })
  }

  /**
   * ENTER da gaveta (F7/F8, 1126d): sangrar/suprir é do **caixa aberto**, não da venda (BR-10), e
   * vai pela mesma trava das mutações da venda — uma por vez, com a fila de bipes drenada no
   * `finally`. Quem grava o movimento, o esperado antes e o depois é o servidor (BR-12): o sucesso
   * fecha o modal e confirma no rodapé com o valor dele; a recusa e a falha transitória voltam para
   * o modal (que reusa a chave no retry); a bloqueante vai para a tela de erro.
   */
  async function sendCashMovement(
    movement: CashMovementKind,
    intent: CashMovementIntent,
    idempotencyKey: string,
  ): Promise<CashMovementApplyResult> {
    const registerId = latest.current.register.id

    mutatingRef.current = true
    setMutating(true)

    try {
      const outcome =
        movement === "withdrawal"
          ? await api.withdrawCash(registerId, intent, idempotencyKey)
          : await api.supplyCash(registerId, intent, idempotencyKey)

      if (outcome.ok) {
        closeModal()
        setFeedback({ kind: "success", text: describeCashMovement(movement, outcome.movement) })
        return { kind: "applied" }
      }

      if (outcome.kind === "rejected") {
        // recusa que o próprio modal mostra (403/400/404/409): o rodapé da venda não muda
        return { kind: "rejected", message: outcome.message }
      }

      if (outcome.kind === "retryable") {
        // o modal fica com o aviso e o ENTER refaz com a mesma chave; o rodapé da venda não muda
        return { kind: "retryable" }
      }

      dispatch({ type: "apiFailed", problem: outcome.problem })
      return { kind: "failed" }
    } finally {
      mutatingRef.current = false
      setMutating(false)
      void pump() // bipes que chegaram durante o movimento esperam aqui
    }
  }

  /**
   * Uma mutação de venda por vez: marca o "enviando…", chama a API e traduz o desfecho para quem a
   * pediu — sucesso vira `saleUpdated` com a venda que o servidor recalculou; o 404 do item que
   * sumiu avisa e a venda fica como está; a recusa do servidor (o desconto do F5) volta como
   * mensagem para o próprio modal; a falha transitória avisa e a mesma tecla refaz; a bloqueante vai
   * para a tela de erro. O `finally` libera a trava e drena a fila de bipes que chegou durante a
   * chamada. O `item` é o alvo do 404 (quantidade e remoção); nas mutações que não têm item (o
   * desconto) ele é `null`.
   */
  async function runMutation(
    send: (saleId: string) => Promise<SaleMutationOutcome>,
    item: SaleItemView | null,
    success: (sale: SaleView) => string,
    failureNotice: string,
  ): Promise<MutationResult> {
    const saleId = latest.current.sale?.id ?? null

    if (saleId === null) {
      // sem venda não há mutação: o modal fecha e a tela segue como está (só acontece fora da venda)
      return { kind: "failed" }
    }

    mutatingRef.current = true
    setMutating(true)

    try {
      const outcome = await send(saleId)

      if (outcome.ok) {
        dispatch({ type: "saleUpdated", sale: outcome.sale })
        setFeedback({ kind: "success", text: success(outcome.sale) })
        return { kind: "applied" }
      }

      if (outcome.kind === "notFound") {
        if (item !== null) {
          setFeedback({ kind: "notice", text: missingItem(item.name) })
        }

        return { kind: "notFound" }
      }

      if (outcome.kind === "rejected") {
        // recusa que o próprio modal mostra (desconto): o rodapé da venda não muda
        return { kind: "rejected", message: outcome.message }
      }

      sendFailed(outcome, failureNotice)
      return { kind: outcome.kind === "retryable" ? "retryable" : "failed" }
    } finally {
      mutatingRef.current = false
      setMutating(false)
      void pump() // bipes que chegaram durante a mutação esperam aqui
    }
  }

  /**
   * Envia a fila de bipes, um por vez, na ordem em que chegaram:
   *
   * - sem venda criada, abre a venda primeiro (`POST /sales`, 201) e registra o id no reducer — é o
   *   item que faz a venda existir de fato; o id fica no estado para o próximo bipe reutilizar
   *   (inclusive quando a venda nasceu vazia por causa de um 404);
   * - cada bipe vira `POST /sales/{id}/items` com o código **bruto** e a quantidade do
   *   multiplicador (BR-14); dois bipes do mesmo produto viram duas chamadas e quem soma é o
   *   servidor (BR-01);
   * - sucesso: `saleUpdated` (limpa o pendente), linha de confirmação e bell;
   * - 404/422: aviso na tela e o bipe é consumido (`scanDismissed`) sem mexer na venda;
   * - falha transitória (rede, timeout, 5xx): o bipe fica no topo da fila e o ENTER refaz;
   * - falha bloqueante (403/400/contrato): vai para a tela de erro guardando a venda; o 409 de
   *   idempotência/concorrência fica na tela e quem relê o estado é o shell (1117).
   */
  async function pump(): Promise<void> {
    if (sendingRef.current || mutatingRef.current) {
      return // já tem envio (ou mutação) em andamento: ele pega o que está na fila
    }

    sendingRef.current = true

    try {
      let saleId = latest.current.sale?.id ?? null

      for (;;) {
        const scan = queueRef.current[0]
        if (scan === undefined) {
          return
        }

        // o bipe que está indo à API é o pendente do reducer
        dispatch({ type: "barcodeScanned", barcode: scan.barcode, quantity: scan.quantity })

        if (saleId === null) {
          const created = await api.createSale()
          if (!created.ok) {
            sendFailed(created)
            return
          }

          saleId = created.sale.id
          dispatch({ type: "saleUpdated", sale: created.sale })
        }

        const added = await api.addSaleItem(saleId, scan)

        if (added.ok) {
          queueRef.current.shift()
          setFeedback({
            kind: "success",
            text: describeAdded(touchedItem(latest.current.sale, added.sale)),
          })
          dispatch({ type: "saleUpdated", sale: added.sale })
          process.stdout.write("\u0007") // bell: o produto entrou na venda
          continue
        }

        if (added.kind === "notFound") {
          queueRef.current.shift()
          setFeedback({
            kind: "notice",
            text: `produto não encontrado: ${added.barcode} — cadastro rápido ainda não disponível`,
          })
          dispatch({ type: "scanDismissed" })
          continue
        }

        if (added.kind === "rejected") {
          queueRef.current.shift()
          setFeedback({ kind: "notice", text: added.message })
          dispatch({ type: "scanDismissed" })
          continue
        }

        sendFailed(added)
        return
      }
    } finally {
      sendingRef.current = false
    }
  }

  /** Falha de envio: transitória segura a operação para o retry; bloqueante vai para a tela de erro. */
  function sendFailed(failure: SendFailure, notice = SEND_FAILURE_NOTICE): void {
    if (failure.kind === "retryable") {
      setFeedback({ kind: "failure", text: notice })
      return
    }

    dispatch({ type: "apiFailed", problem: failure.problem })
  }

  // modal bloqueante (1126a–1126d): o corpo da venda sai de cena e o quadro do modal ocupa o
  // lugar — o leitor já está desligado (`barcodeEnabled`) e o `handleKey` daqui não age enquanto ele existe
  if (modal !== null) {
    return (
      <box width="100%" height="100%" flexDirection="column" alignItems="center" justifyContent="center">
        {modal.kind === "help" ? (
          <HelpModal onClosed={closeModal} />
        ) : modal.kind === "removeItemConfirm" ? (
          <RemoveItemConfirmModal
            item={modal.item}
            busy={removing}
            notice={removalNotice}
            onConfirm={() => void removeConfirmedItem()}
            onCancel={closeModal}
          />
        ) : modal.kind === "priceLookup" ? (
          <PriceLookupModal api={api} onCancel={closeModal} onFailed={failModal} />
        ) : modal.kind === "customer" ? (
          <CustomerModal
            customer={customer}
            api={api}
            onLink={linkCustomerFromModal}
            onUnlink={unlinkCustomerFromModal}
            onCancel={closeModal}
            onFailed={failModal}
          />
        ) : modal.kind === "switchOperator" ? (
          <SwitchOperatorModal
            saleId={sale?.id ?? null}
            itemCount={items.length}
            api={api}
            onSwitched={switchedOperator}
            onCancel={closeModal}
            onFailed={failModal}
          />
        ) : modal.kind === "cancelSale" ? (
          <CancelSaleModal
            saleId={modal.saleId}
            api={api}
            onCancelled={saleCancelled}
            onCancel={closeModal}
            onFailed={failModal}
          />
        ) : modal.kind === "cashMovement" ? (
          <CashMovementModal
            kind={modal.movement}
            onSend={(intent, idempotencyKey) =>
              sendCashMovement(modal.movement, intent, idempotencyKey)
            }
            onCancel={closeModal}
          />
        ) : (
          <DiscountModal onApply={applyDiscountFromModal} onCancel={closeModal} />
        )}
      </box>
    )
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
      {/* leitura manual (F-01): o campo fica sempre à vista e focado; a rajada do leitor passa por
          cima dele (o hook global intercepta antes) e o bipe o limpa */}
      <box height={1} flexDirection="row">
        <text fg={theme.muted} wrapMode="none">
          {"Código: "}
        </text>
        <input
          ref={inputRef}
          focused
          flexGrow={1}
          placeholder={MANUAL_PLACEHOLDER}
          value={manual}
          onInput={setManual}
          onSubmit={submitManual}
        />
      </box>
      <text wrapMode="none">{`Subtotal: ${formatAmount(sale?.subtotal ?? 0)}`}</text>
      <text wrapMode="none">{`Desconto: ${formatAmount(sale?.discountAmount ?? 0)}`}</text>
      <text attributes={TextAttributes.BOLD} wrapMode="none">
        {`TOTAL: ${formatAmount(sale?.total ?? 0)}`}
      </text>
      {/* rodapé de uma linha: "enviando…" com mutação em voo, senão o desfecho da última operação
          (bipe aceito, 404/422, falha de envio); a linha fica reservada mesmo sem feedback para o
          quadro não dançar quando ele aparece */}
      {mutating ? (
        <text fg={theme.muted} wrapMode="none">
          enviando…
        </text>
      ) : feedback === null ? (
        <text> </text>
      ) : (
        <FeedbackRow feedback={feedback} />
      )}
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

/** Rodapé: verde no bipe aceito, amarelo no aviso (404/422) e vermelho na falha que pede retry. */
function FeedbackRow({ feedback }: { feedback: Feedback }) {
  const color =
    feedback.kind === "failure" ? theme.danger : feedback.kind === "notice" ? theme.warning : theme.success

  return (
    <text fg={color} wrapMode="none">
      {feedback.text}
    </text>
  )
}

/** Confirmação do bipe: o item que o servidor devolveu, com nome, quantidade e valor (BR-12). */
function describeAdded(item: SaleItemView | null): string {
  if (item === null) {
    return "item adicionado"
  }

  return `adicionado: ${formatQuantity(item.quantity)} x ${item.name} — ${formatAmount(item.lineTotal)}`
}

/** Confirmação do `+`/`-` (1125c): a quantidade que o servidor aplicou, nunca a conta da TUI (BR-12). */
function describeQuantity(item: SaleItemView | null): string {
  if (item === null) {
    return "quantidade alterada"
  }

  return `quantidade: ${formatQuantity(item.quantity)} x ${item.name} — ${formatAmount(item.lineTotal)}`
}

/** Confirmação do desconto (F5, 1126b): o desconto e o total que o servidor recalculou (BR-12). */
function describeDiscount(sale: SaleView): string {
  return `desconto: ${formatAmount(sale.discountAmount)} — total ${formatAmount(sale.total)}`
}

/** Confirmação da gaveta (F7/F8, 1126d): o movimento com o valor que o servidor gravou (BR-12). */
function describeCashMovement(kind: CashMovementKind, movement: CashMovementView): string {
  return `${CASH_MOVEMENT_LABELS[kind]}: ${formatAmount(movement.amount)}`
}

/**
 * Alimenta o scanner com a leitura **digitada** (F-01): os caracteres e o `\r` entram no mesmo
 * instante, como uma rajada do leitor, e o `n*` do operador vira o multiplicador do bipe.
 *
 * O `*` só vale como multiplicador quando chega com o intervalo humano (`>= 50 ms`): na velocidade
 * da rajada ele é conteúdo do código (BR-14). Por isso o prefixo `n*` é alimentado com o intervalo
 * sintético de `MANUAL_MULTIPLIER_GAP_MS` e o código fecha a rajada no instante seguinte — o
 * `core/scanner` continua sendo a única fonte da regra; aqui só se emula o timing da digitação.
 */
function feedManualText(scanner: Scanner, text: string): ScannerEvent | null {
  const at = performance.now()
  const prefix = MANUAL_MULTIPLIER.exec(text)
  const code = prefix === null ? text : text.slice(prefix[0].length)
  const codeAt = prefix === null ? at : at + MANUAL_MULTIPLIER_GAP_MS

  if (prefix !== null) {
    for (const char of prefix[1] ?? "") {
      scanner.feed(char, at)
    }

    scanner.feed("*", codeAt)
  }

  for (const char of code) {
    scanner.feed(char, codeAt)
  }

  return scanner.feed("\r", codeAt)
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
