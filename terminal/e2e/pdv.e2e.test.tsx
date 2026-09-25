/** @jsxImportSource @opentui/react */
import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { KeyCodes } from "@opentui/core/testing"
import { testRender } from "@opentui/react/test-utils"
import { act } from "react"

import { resolveApiUrl, terminalApi } from "../src/api/index"
import { formatAmount, formatBRL } from "../src/core/money"
import { App } from "../src/opentui/App"
import { Backend, randomBarcode } from "./backend"

/**
 * E2E do PDV na UI nova (passo 1130a): o mesmo fluxo do 1120 — login, escolha do caixa, abertura,
 * bipe, desconto (F5), pagamento (F9), conclusão e fechamento (F10) — agora dirigindo o `App` de
 * `src/opentui/` com o `testRender` do `@opentui/react/test-utils`. Nada é dublado: o `App` recebe a
 * `terminalApi` de produção e cada tecla vira chamada HTTP de verdade. Só o fixture `e2e/backend.ts`
 * (sessão de ADMIN do `%dev`) fala HTTP fora da TUI, para montar o cenário e conferir o que o
 * operador não vê na tela.
 *
 * O runner é o **`bun test`** (decisão do 1121: o Vitest/Node não carrega a lib nativa da OpenTUI) e
 * aqui não existe `vi.waitFor`. A espera por um quadro é um laço próprio de `renderOnce` +
 * `captureCharFrame` dentro de `act` — 25 ms entre passadas, timeout de 15 s —, porque o
 * `waitForFrame` do `testRender` é limitado a 20 passadas e para quando o scheduler fica ocioso, o
 * que não cobre a rede. As teclas entram com `mockInput`, sempre dentro de `act` para os efeitos das
 * telas (os listeners do hook global) estarem registrados antes da tecla seguinte.
 *
 * O bipe é a rajada do leitor: `mockInput.typeText(barcode)` emite os caracteres colados (delay 0) e
 * o ENTER fecha o bipe no `core/scanner` — o mesmo caminho do hardware, com o campo de leitura limpo
 * pelo `handleBarcode` da tela de venda (1125b). Nos campos de valor a digitação é humana
 * (`typeText(..., 60)`): o limiar de rajada (< 50 ms) descartaria o texto, como descarta um bipe que
 * chega por engano.
 *
 * **Execução local obrigatória:** este teste grava dados no banco de dev (produto, estoque, venda,
 * sessão de caixa) e exige o backend no ar — `quarkus:dev` na 8081 + PostgreSQL do compose —, por
 * isso não roda no `npm test` nem no CI; a execução está documentada no `terminal/README.md`. O
 * cenário se limpa no começo (sessão aberta por uma execução anterior é fechada com as vendas `OPEN`
 * canceladas antes) e cria o produto com barcode único, então rodar de novo não depende do estado da
 * execução anterior.
 */

/** ADMIN inicial do `%dev` (`ADMIN_INITIAL_PASSWORD`, passo 115) e o caixa do seed de dev. */
const ADMIN = { username: "admin", password: "admin123" }
const REGISTER_CODE = "CAIXA-01"

/** Cenário: produto de preço conhecido, com estoque de verdade no ledger (passo 706). */
const PRODUCT_NAME = "Café E2E 500g"
const PRICE_CENTS = 1250
const RECEIPT_QUANTITY = 20

/** Operação: fundo de troco de R$ 10,00, desconto de R$ 1,00 e dinheiro com R$ 20,00 recebidos. */
const OPENING_CENTS = 1000
const DISCOUNT_CENTS = 100
const TENDERED_CENTS = 2000
const TOTAL_CENTS = PRICE_CENTS - DISCOUNT_CENTS
const CHANGE_CENTS = TENDERED_CENTS - TOTAL_CENTS

/** O contado fica 50 centavos abaixo do esperado: a diferença do fechamento é do servidor (BR-12). */
const MISSING_CENTS = 50

/** Espera de um passo (rede, Argon2id do login e a transação no servidor) e o teto do teste inteiro. */
const STEP_TIMEOUT_MS = 15_000
const TEST_TIMEOUT_MS = 120_000

type Setup = Awaited<ReturnType<typeof testRender>>

/**
 * Espera o quadro alcançar o texto: um `renderOnce` a cada 25 ms (dentro de `act`, para os efeitos
 * pendentes das telas rodarem) até o quadro conter o fragmento ou estourar o timeout do passo.
 *
 * O giro entre as passadas é o que sincroniza a rede: a resposta do servidor vira despacho do
 * reducer fora do passo da tecla, e o quadro seguinte só existe depois que o React a processa — não
 * há sleep arbitrário nem mock de tempo.
 */
async function expectFrame(setup: Setup, text: string, timeoutMs = STEP_TIMEOUT_MS): Promise<string> {
  const deadline = Date.now() + timeoutMs
  let frame = setup.captureCharFrame()

  while (!frame.includes(text)) {
    if (Date.now() >= deadline) {
      throw new Error(`o quadro não alcançou "${text}" em ${timeoutMs} ms:\n${frame}`)
    }

    await act(async () => {
      await Bun.sleep(25)
      await setup.renderOnce()
    })

    frame = setup.captureCharFrame()
  }

  return frame
}

/** ENTER do operador (formulários, escolhas e confirmações). */
async function pressEnter(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressEnter()
  })
}

/** TAB dos formulários (senha, motivo do desconto e campo do recebido no pagamento). */
async function pressTab(setup: Setup): Promise<void> {
  await act(async () => {
    setup.mockInput.pressTab()
  })
}

/** Tecla nomeada do mapa §11.3 (F5, F9, F10): o `mockInput` emite a tecla canônica da OpenTUI. */
async function pressKey(setup: Setup, key: string): Promise<void> {
  await act(async () => {
    setup.mockInput.pressKey(key)
  })
}

/** Digitação humana (60 ms por tecla): os campos de valor descartam o que chega como rajada (< 50 ms). */
async function typeHuman(setup: Setup, text: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(text, 60)
  })
}

/** Rajada do leitor: caracteres colados (delay 0) e o ENTER fecha o bipe no `core/scanner`. */
async function typeBurst(setup: Setup, text: string): Promise<void> {
  await act(async () => {
    await setup.mockInput.typeText(text)
  })
}

describe("E2E: fluxo completo do PDV na UI nova (1130a)", () => {
  const backend = new Backend(resolveApiUrl())
  const barcode = randomBarcode()

  let registerId = ""
  let productId = ""
  let sessionId = ""

  beforeAll(async () => {
    // 1. login de reconhecimento só para achar o caixa do seed; a sessão é descartada em seguida
    await backend.signIn(ADMIN.username, ADMIN.password)
    const register = await backend.requireRegister(REGISTER_CODE)
    await backend.signOut()

    if (register.id === undefined) {
      throw new Error(`caixa ${REGISTER_CODE} sem id na resposta`)
    }

    registerId = register.id

    // 2. sessão de ADMIN vinculada ao caixa: cancelar venda e fechar caixa conferem a posse (BR-11)
    await backend.signIn(ADMIN.username, ADMIN.password, registerId)

    // 3. limpeza da execução anterior: sessão aberta com venda OPEN derruba o fechamento com 409
    const stale = await backend.currentSession(registerId)
    if (stale !== null) {
      for (const sale of await backend.salesOf(stale, "OPEN")) {
        if (sale.id !== undefined) {
          await backend.cancelSale(sale.id, "limpeza do E2E (1130a)")
        }
      }

      const summary = await backend.summary(stale)
      await backend.closeSession(registerId, summary.expectedAmount ?? 0)
    }

    // 4. fixtures do cenário: produto com barcode único e entrada de mercadoria
    productId = await backend.createProduct(PRODUCT_NAME, barcode, PRICE_CENTS / 100)
    await backend.receiveStock(productId, RECEIPT_QUANTITY, "carga do E2E")

    const stock = await backend.stock(productId)
    expect(stock.quantity).toBe(RECEIPT_QUANTITY)
  }, TEST_TIMEOUT_MS)

  afterAll(async () => {
    await backend.signOut()
  })

  test(
    "login → abrir caixa → bipe → desconto → pagamento → conclusão → fechamento",
    async () => {
      const setup = await testRender(<App api={terminalApi} />, { width: 80, height: 24 })

      try {
        // --- login (1124a): credenciais e escolha do caixa ----------------------------------
        await typeHuman(setup, ADMIN.username)
        await expectFrame(setup, `Usuário: ${ADMIN.username}`)

        await pressTab(setup)
        await expectFrame(setup, "› Senha:")

        await typeHuman(setup, ADMIN.password)
        await expectFrame(setup, `Senha: ${"•".repeat(ADMIN.password.length)}`)

        await pressEnter(setup)
        await expectFrame(setup, "Escolha o caixa")
        // a limpeza do beforeAll devolveu o caixa livre: a sessão anterior não sobrou
        await expectFrame(setup, REGISTER_CODE)
        await expectFrame(setup, "— livre")

        await pressEnter(setup)
        await expectFrame(setup, "Abertura de caixa")

        // --- abertura de caixa (1124b) ------------------------------------------------------
        await typeHuman(setup, String(OPENING_CENTS))
        await expectFrame(setup, `Fundo de troco: ${formatBRL(OPENING_CENTS)}`)

        await pressEnter(setup)
        await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

        sessionId = (await backend.currentSession(registerId)) ?? ""
        expect(sessionId).not.toBe("")

        // --- bipe (1125b): rajada do leitor com o terminador fechando o bipe ----------------
        await typeBurst(setup, barcode)
        await pressEnter(setup)
        await expectFrame(setup, `› 1 x ${PRODUCT_NAME} — ${formatAmount(PRICE_CENTS / 100)}`)
        await expectFrame(setup, `TOTAL: ${formatAmount(PRICE_CENTS / 100)}`)

        // --- desconto (F5, 1126b): texto e ENTER em passos separados, como o operador digita --
        await pressKey(setup, KeyCodes.F5)
        await expectFrame(setup, "Desconto na venda (F5)")

        await typeHuman(setup, String(DISCOUNT_CENTS))
        await expectFrame(setup, `Valor: ${formatBRL(DISCOUNT_CENTS)}`)

        await pressTab(setup)
        await expectFrame(setup, "› Motivo:")
        await typeHuman(setup, "e2e")
        await expectFrame(setup, "Motivo: e2e")

        await pressEnter(setup)
        await expectFrame(setup, `Desconto: ${formatAmount(DISCOUNT_CENTS / 100)}`)
        await expectFrame(setup, `TOTAL: ${formatAmount(TOTAL_CENTS / 100)}`)

        // --- pagamento (F9, 1127a): dinheiro com troco do servidor --------------------------
        await pressKey(setup, KeyCodes.F9)
        await expectFrame(setup, "Pagamento (F9)")

        await typeHuman(setup, String(TOTAL_CENTS))
        await expectFrame(setup, `› Valor: ${formatBRL(TOTAL_CENTS)}`)

        await pressTab(setup)
        await expectFrame(setup, "› Recebido: R$ 0,00")
        await typeHuman(setup, String(TENDERED_CENTS))
        await expectFrame(setup, `Recebido: ${formatBRL(TENDERED_CENTS)}`)

        await pressEnter(setup)
        await expectFrame(
          setup,
          `Pago: ${formatAmount(TOTAL_CENTS / 100)} de ${formatAmount(TOTAL_CENTS / 100)}`,
        )
        await expectFrame(setup, `TROCO: ${formatAmount(CHANGE_CENTS / 100)}`)

        // --- conclusão (F9 de novo) e tela de sucesso (1127a) -------------------------------
        await pressKey(setup, KeyCodes.F9)
        await expectFrame(setup, "concluída")
        await expectFrame(setup, `TOTAL: ${formatAmount(TOTAL_CENTS / 100)}`)
        await expectFrame(setup, `TROCO: ${formatAmount(CHANGE_CENTS / 100)}`)

        await pressEnter(setup)
        await expectFrame(setup, "bipar o primeiro item para iniciar a venda")

        // --- fechamento (F10, 1127b): o esperado vem do servidor e o contado é digitado -----
        const expected = (await backend.summary(sessionId)).expectedAmount ?? 0
        expect(expected).toBe((OPENING_CENTS + TOTAL_CENTS) / 100) // só o dinheiro da venda entrou

        const countedCents = Math.round(expected * 100) - MISSING_CENTS

        await pressKey(setup, KeyCodes.F10)
        await expectFrame(setup, `Esperado: ${formatAmount(expected)}`)
        await expectFrame(setup, `DINHEIRO: ${formatAmount(TOTAL_CENTS / 100)}`)

        await typeHuman(setup, String(countedCents))
        await expectFrame(setup, `Valor contado: ${formatBRL(countedCents)}`)

        await pressEnter(setup)
        await expectFrame(setup, "caixa fechado")
        await expectFrame(
          setup,
          `Diferença (servidor): ${formatAmount(-MISSING_CENTS / 100)} — falta dinheiro na gaveta`,
        )

        // --- logout (ENTER no caixa fechado) e volta ao login -------------------------------
        await pressEnter(setup)
        await expectFrame(setup, "PDV minimercado — entrada do operador")

        // --- conferência por API: estoque, venda, auditoria e sessão -------------------------
        // BR-13: a venda concluída baixou o estoque de verdade (20 recebidos, 1 vendido)
        const stock = await backend.stock(productId)
        expect(stock.quantity).toBe(RECEIPT_QUANTITY - 1)

        const completed = await backend.salesOf(sessionId, "COMPLETED")
        expect(completed).toHaveLength(1)
        expect(completed[0]?.total).toBe(TOTAL_CENTS / 100)
        expect(completed[0]?.discountAmount).toBe(DISCOUNT_CENTS / 100)
        expect(completed[0]?.itemCount).toBe(1)

        const actions = (await backend.auditEvents(sessionId)).map((event) => event.action)
        for (const action of [
          "CASH_SESSION_OPENED",
          "SALE_CREATED",
          "SALE_ITEM_ADDED",
          "SALE_DISCOUNT_APPLIED",
          "PAYMENT_ADDED",
          "SALE_COMPLETED",
          "CASH_SESSION_CLOSED",
        ]) {
          expect(actions).toContain(action)
        }

        const closed = await backend.session(sessionId)
        expect(closed.status).toBe("CLOSED")
        expect(closed.countedAmount).toBe(countedCents / 100)
        expect(closed.expectedAmount).toBe(expected)
        expect(closed.differenceAmount).toBe(-MISSING_CENTS / 100)

        const afterClose = await backend.summary(sessionId)
        expect(afterClose.paymentsByMethod?.CASH).toBe(TOTAL_CENTS / 100)
      } finally {
        setup.renderer.destroy()
      }
    },
    TEST_TIMEOUT_MS,
  )
})
