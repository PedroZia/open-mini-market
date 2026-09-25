import { render } from 'ink-testing-library';
import { afterAll, beforeAll, describe, expect, test, vi } from 'vitest';

import { resolveApiUrl, terminalApi } from '../src/api/index';
import { formatAmount, formatBRL } from '../src/core/money';
import { App } from '../src/ui/App';
import { Backend, randomBarcode } from './backend';

/**
 * E2E do PDV (passo 1120): o fluxo inteiro do operador na TUI **real** — login, escolha do caixa,
 * abertura, bipe, desconto, pagamento, conclusão e fechamento — contra um **backend real** (Quarkus
 * + PostgreSQL, o ambiente de dev do `docker-compose`/`quarkus:dev`, porta 8081 por default ou a de
 * `MINIMARKET_API_URL`). Nada é dublado: o `App` recebe a `terminalApi` de produção e cada tecla vira
 * chamada HTTP de verdade.
 *
 * Os efeitos que o operador não vê na tela são conferidos por API, com uma sessão de ADMIN do
 * fixture (`e2e/backend.ts`): o saldo do produto baixou pela quantidade vendida, a sessão de caixa
 * fechou com contado/esperado/diferença coerentes, a venda ficou `COMPLETED` e a auditoria da sessão
 * tem os eventos do fluxo — `SALE_CREATED`, `SALE_ITEM_ADDED`, `SALE_DISCOUNT_APPLIED`,
 * `PAYMENT_ADDED`, `SALE_COMPLETED`, `CASH_SESSION_OPENED` e `CASH_SESSION_CLOSED` (BR-13).
 *
 * **Execução local obrigatória:** este teste grava dados no banco de dev (produto, estoque, venda,
 * sessão de caixa) e exige o backend no ar — por isso não roda no `npm test` nem no CI. O passo 1120
 * decidiu por execução local documentada em vez de job de E2E; veja `terminal/README.md`.
 *
 * O cenário se limpa no começo: uma sessão de caixa aberta por uma execução anterior (ou pela
 * operação manual do 1109) é fechada com as vendas `OPEN` canceladas antes — sem isso o fechamento da
 * TUI esbarraria no 409 `SESSION_HAS_OPEN_SALES`. Cada execução cria o seu produto com barcode único,
 * então rodar de novo não depende do estado da execução anterior.
 */

/** Teclas F no canal cru: o `useInput` do Ink não entrega F1–F12 (1105/1108). */
const F5 = '\u001b[15~';
const F9 = '\u001b[20~';
const F10 = '\u001b[21~';

/** ADMIN inicial do `%dev` (`ADMIN_INITIAL_PASSWORD`, passo 115) e o caixa do seed de dev. */
const ADMIN = { username: 'admin', password: 'admin123' };
const REGISTER_CODE = 'CAIXA-01';

/** Cenário: produto de preço conhecido, com estoque de verdade no ledger (passo 706). */
const PRODUCT_NAME = 'Café E2E 500g';
const PRICE_CENTS = 1250;
const RECEIPT_QUANTITY = 20;

/** Operação: fundo de troco de R$ 10,00, desconto de R$ 1,00 e dinheiro com R$ 20,00 recebidos. */
const OPENING_CENTS = 1000;
const DISCOUNT_CENTS = 100;
const TENDERED_CENTS = 2000;
const TOTAL_CENTS = PRICE_CENTS - DISCOUNT_CENTS;
const CHANGE_CENTS = TENDERED_CENTS - TOTAL_CENTS;

/** O contado fica 50 centavos abaixo do esperado: a diferença do fechamento é do servidor (BR-12). */
const MISSING_CENTS = 50;

/**
 * O render do Ink não é síncrono com o `stdin.write`: espera o frame alcançar o texto e dá um giro ao
 * event loop antes de devolver.
 *
 * O giro é o que garante que os **efeitos** do React da mudança anterior já rodaram: as assinaturas
 * de `useInput` das telas e o contexto do canal cru (F1–F12, `useRawShortcuts`) são efeitos passivos,
 * aplicados depois de o frame novo sair — uma tecla escrita na janela entre um e outro cai na tela
 * anterior (ou em ninguém) e some. Com o backend real, a espera pelo frame é a única sincronização:
 * nada de `sleep` arbitrário.
 */
async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(
    () => {
      expect(lastFrame()).toContain(text);
    },
    { timeout: 15_000, interval: 25 },
  );

  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('E2E: fluxo completo do PDV (1120)', () => {
  const backend = new Backend(resolveApiUrl());
  const barcode = randomBarcode();

  let registerId = '';
  let productId = '';
  let sessionId = '';

  beforeAll(async () => {
    // 1. login de reconhecimento só para achar o caixa do seed; a sessão é descartada em seguida
    await backend.signIn(ADMIN.username, ADMIN.password);
    const register = await backend.requireRegister(REGISTER_CODE);
    await backend.signOut();

    if (register.id === undefined) {
      throw new Error(`caixa ${REGISTER_CODE} sem id na resposta`);
    }

    registerId = register.id;

    // 2. sessão de ADMIN vinculada ao caixa: cancelar venda e fechar caixa conferem a posse (BR-11)
    await backend.signIn(ADMIN.username, ADMIN.password, registerId);

    // 3. limpeza da execução anterior: sessão aberta com venda OPEN derruba o fechamento com 409
    const stale = await backend.currentSession(registerId);
    if (stale !== null) {
      for (const sale of await backend.salesOf(stale, 'OPEN')) {
        if (sale.id !== undefined) {
          await backend.cancelSale(sale.id, 'limpeza do E2E (1120)');
        }
      }

      const summary = await backend.summary(stale);
      await backend.closeSession(registerId, summary.expectedAmount ?? 0);
    }

    // 4. fixtures do cenário: produto com barcode único e entrada de mercadoria
    productId = await backend.createProduct(PRODUCT_NAME, barcode, PRICE_CENTS / 100);
    await backend.receiveStock(productId, RECEIPT_QUANTITY, 'carga do E2E');

    const stock = await backend.stock(productId);
    expect(stock.quantity).toBe(RECEIPT_QUANTITY);
  });

  afterAll(async () => {
    await backend.signOut();
  });

  test('login → abrir caixa → bipe → desconto → pagamento → conclusão → fechamento', async () => {
    const ui = render(<App api={terminalApi} />);

    // --- login (1106): credenciais e escolha do caixa -------------------------------------
    ui.stdin.write(ADMIN.username);
    await expectFrame(ui.lastFrame, `Usuário: ${ADMIN.username}`);
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Senha:');
    ui.stdin.write(ADMIN.password);
    await expectFrame(ui.lastFrame, `Senha: ${'•'.repeat(ADMIN.password.length)}`);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Escolha o caixa');
    // a limpeza do beforeAll devolveu o caixa livre: a sessão anterior não sobrou
    await expectFrame(ui.lastFrame, REGISTER_CODE);
    await expectFrame(ui.lastFrame, '— livre');

    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Abertura de caixa');

    // --- abertura de caixa (1107) ---------------------------------------------------------
    ui.stdin.write(String(OPENING_CENTS));
    await expectFrame(ui.lastFrame, `Fundo de troco: ${formatBRL(OPENING_CENTS)}`);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');

    sessionId = (await backend.currentSession(registerId)) ?? '';
    expect(sessionId).not.toBe('');

    // --- bipe (1109): rajada do leitor com o terminador no mesmo chunk --------------------
    ui.stdin.write(`${barcode}\r`);
    await expectFrame(ui.lastFrame, `› 1 x ${PRODUCT_NAME} — ${formatAmount(PRICE_CENTS / 100)}`);

    // --- desconto (F5, 1111): texto e ENTER em chunks separados, como o leitor entrega -----
    ui.stdin.write(F5);
    await expectFrame(ui.lastFrame, 'Desconto na venda (F5)');
    ui.stdin.write(String(DISCOUNT_CENTS));
    await expectFrame(ui.lastFrame, `Valor: ${formatBRL(DISCOUNT_CENTS)}`);
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Motivo:');
    ui.stdin.write('e2e');
    await expectFrame(ui.lastFrame, 'Motivo: e2e');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, `Desconto: ${formatAmount(DISCOUNT_CENTS / 100)}`);
    await expectFrame(ui.lastFrame, `TOTAL: ${formatAmount(TOTAL_CENTS / 100)}`);

    // --- pagamento (F9, 1113): dinheiro com troco do servidor -----------------------------
    ui.stdin.write(F9);
    await expectFrame(ui.lastFrame, 'Pagamento (F9)');
    ui.stdin.write(String(TOTAL_CENTS));
    await expectFrame(ui.lastFrame, `Valor: ${formatBRL(TOTAL_CENTS)}`);
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Recebido:');
    ui.stdin.write(String(TENDERED_CENTS));
    await expectFrame(ui.lastFrame, `Recebido: ${formatBRL(TENDERED_CENTS)}`);
    ui.stdin.write('\r');
    await expectFrame(
      ui.lastFrame,
      `Pago: ${formatAmount(TOTAL_CENTS / 100)} de ${formatAmount(TOTAL_CENTS / 100)}`,
    );
    await expectFrame(ui.lastFrame, `TROCO: ${formatAmount(CHANGE_CENTS / 100)}`);

    // --- conclusão (F9 de novo) e tela de sucesso (1113) ----------------------------------
    ui.stdin.write(F9);
    await expectFrame(ui.lastFrame, 'concluída');
    await expectFrame(ui.lastFrame, `TOTAL: ${formatAmount(TOTAL_CENTS / 100)}`);
    await expectFrame(ui.lastFrame, `TROCO: ${formatAmount(CHANGE_CENTS / 100)}`);

    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');

    // --- fechamento (F10, 1115): o esperado vem do servidor e o contado é digitado ---------
    const expected = (await backend.summary(sessionId)).expectedAmount ?? 0;
    expect(expected).toBe((OPENING_CENTS + TOTAL_CENTS) / 100); // só o dinheiro da venda entrou

    const countedCents = Math.round(expected * 100) - MISSING_CENTS;

    ui.stdin.write(F10);
    await expectFrame(ui.lastFrame, `Esperado: ${formatAmount(expected)}`);
    await expectFrame(ui.lastFrame, `DINHEIRO: ${formatAmount(TOTAL_CENTS / 100)}`);

    ui.stdin.write(String(countedCents));
    await expectFrame(ui.lastFrame, `Valor contado: ${formatBRL(countedCents)}`);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, `fechar o caixa com ${formatBRL(countedCents)}?`);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'caixa fechado');
    await expectFrame(
      ui.lastFrame,
      `Diferença (servidor): ${formatAmount(-MISSING_CENTS / 100)} — falta dinheiro na gaveta`,
    );

    // --- logout (ENTER no caixa fechado) e volta ao login ---------------------------------
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Usuário:');

    // --- conferência por API: estoque, venda, auditoria e sessão --------------------------
    // BR-13: a venda concluída baixou o estoque de verdade (20 recebidos, 1 vendido)
    const stock = await backend.stock(productId);
    expect(stock.quantity).toBe(RECEIPT_QUANTITY - 1);

    const completed = await backend.salesOf(sessionId, 'COMPLETED');
    expect(completed).toHaveLength(1);
    expect(completed[0]?.total).toBe(TOTAL_CENTS / 100);
    expect(completed[0]?.discountAmount).toBe(DISCOUNT_CENTS / 100);
    expect(completed[0]?.itemCount).toBe(1);

    const actions = (await backend.auditEvents(sessionId)).map((event) => event.action);
    for (const action of [
      'CASH_SESSION_OPENED',
      'SALE_CREATED',
      'SALE_ITEM_ADDED',
      'SALE_DISCOUNT_APPLIED',
      'PAYMENT_ADDED',
      'SALE_COMPLETED',
      'CASH_SESSION_CLOSED',
    ]) {
      expect(actions).toContain(action);
    }

    const closed = await backend.session(sessionId);
    expect(closed.status).toBe('CLOSED');
    expect(closed.countedAmount).toBe(countedCents / 100);
    expect(closed.expectedAmount).toBe(expected);
    expect(closed.differenceAmount).toBe(-MISSING_CENTS / 100);

    const afterClose = await backend.summary(sessionId);
    expect(afterClose.paymentsByMethod?.CASH).toBe(TOTAL_CENTS / 100);
  });
});
