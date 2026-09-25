import { describe, expect, test } from 'vitest';

import { reduce, type Action } from './reducer';
import { initialState, type SaleView, type State } from './state';

const operator = { id: 'u-1', name: 'Ana' };
const register = { id: 'r-1', name: 'Caixa 1' };

const sale: SaleView = {
  id: 'sale-1',
  items: [
    { productId: 'p-1', name: 'Arroz 1kg', unit: 'UN', quantity: 1, unitPrice: 5.5, lineTotal: 5.5 },
  ],
  subtotal: 5.5,
  discountAmount: 0,
  total: 5.5,
};

const problem = { status: 503, code: null, detail: 'Serviço indisponível.' };

/** Login OK → abertura de caixa. */
function loggedIn(): State {
  return reduce(initialState, { type: 'loginSucceeded', operator, register });
}

/** Caixa aberto → venda, ainda sem venda criada no servidor. */
function cashOpened(): State {
  return reduce(loggedIn(), { type: 'cashOpened', sessionId: 'session-1' });
}

/** Venda aberta com a venda de exemplo sincronizada. */
function selling(): State {
  return reduce(cashOpened(), { type: 'saleUpdated', sale });
}

describe('reducer da operação', () => {
  test('estado inicial: login, sem operador e sem falha', () => {
    expect(initialState).toEqual({ kind: 'login', failure: null });
  });

  test('login OK leva à abertura de caixa com operador e caixa', () => {
    expect(loggedIn()).toEqual({ kind: 'openingCash', operator, register });
  });

  test('credencial recusada mantém o formulário e mostra a mensagem', () => {
    const rejected = reduce(initialState, {
      type: 'loginRejected',
      message: 'Usuário ou senha inválidos.',
    });

    expect(rejected).toEqual({ kind: 'login', failure: 'Usuário ou senha inválidos.' });
  });

  test('login OK depois da recusa não carrega a mensagem antiga', () => {
    const rejected = reduce(initialState, { type: 'loginRejected', message: 'Bloqueado.' });

    expect(reduce(rejected, { type: 'loginSucceeded', operator, register })).toEqual({
      kind: 'openingCash',
      operator,
      register,
    });
  });

  test('caixa aberto começa a venda sem venda criada', () => {
    expect(cashOpened()).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
    });
  });

  test('bipe registra a intenção com o código bruto, sem trim (BR-14)', () => {
    const scanned = reduce(cashOpened(), {
      type: 'barcodeScanned',
      barcode: ' 7891000100103 ',
      quantity: 1,
    });

    expect(scanned).toMatchObject({
      kind: 'saleOpen',
      pendingScan: { barcode: ' 7891000100103 ', quantity: 1 },
    });
  });

  test('multiplicador do leitor vira quantidade na mesma intenção', () => {
    const scanned = reduce(cashOpened(), {
      type: 'barcodeScanned',
      barcode: '7891000100103',
      quantity: 3,
    });

    expect(scanned).toMatchObject({ pendingScan: { barcode: '7891000100103', quantity: 3 } });
  });

  test('resposta da API atualiza itens e totais e limpa o bipe pendente', () => {
    const scanned = reduce(cashOpened(), {
      type: 'barcodeScanned',
      barcode: '7891000100103',
      quantity: 1,
    });

    expect(reduce(scanned, { type: 'saleUpdated', sale })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale,
      pendingScan: null,
    });
  });

  test('bipe recusado é consumido sem mexer na venda: scanDismissed só limpa o pendente', () => {
    const scanned = reduce(selling(), {
      type: 'barcodeScanned',
      barcode: '7891000100103',
      quantity: 1,
    });
    const dismissed = reduce(scanned, { type: 'scanDismissed' });

    expect(dismissed).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale,
      pendingScan: null,
    });
  });

  test('scanDismissed sem bipe pendente é ignorado e devolve o mesmo estado', () => {
    const state = selling();

    expect(reduce(state, { type: 'scanDismissed' })).toBe(state);
  });

  test('F9 abre o pagamento da venda em andamento', () => {
    expect(reduce(selling(), { type: 'paymentStarted' })).toEqual({
      kind: 'paying',
      operator,
      register,
      sessionId: 'session-1',
      sale,
    });
  });

  test('F9 sem venda criada não abre o pagamento', () => {
    const state = cashOpened();

    expect(reduce(state, { type: 'paymentStarted' })).toBe(state);
  });

  test('ESC no pagamento volta para a venda sem perder os itens', () => {
    const paying = reduce(selling(), { type: 'paymentStarted' });

    expect(reduce(paying, { type: 'cancel' })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale,
      pendingScan: null,
    });
  });

  test('resposta da API durante o pagamento substitui a venda exibida', () => {
    const paying = reduce(selling(), { type: 'paymentStarted' });
    const discounted: SaleView = { ...sale, discountAmount: 0.5, total: 5 };

    expect(reduce(paying, { type: 'saleUpdated', sale: discounted })).toEqual({
      ...paying,
      sale: discounted,
    });
  });

  test('concluir o pagamento inicia a próxima venda na mesma sessão', () => {
    const paying = reduce(selling(), { type: 'paymentStarted' });

    expect(reduce(paying, { type: 'saleCompleted' })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
    });
  });

  test('F10 entra no fechamento levando a venda aberta', () => {
    expect(reduce(selling(), { type: 'cashClosingStarted' })).toEqual({
      kind: 'closingCash',
      operator,
      register,
      sessionId: 'session-1',
      sale,
    });
  });

  test('ESC no fechamento volta para a venda', () => {
    const closing = reduce(selling(), { type: 'cashClosingStarted' });

    expect(reduce(closing, { type: 'cancel' })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale,
      pendingScan: null,
    });
  });

  test('caixa fechado volta ao login', () => {
    const closing = reduce(selling(), { type: 'cashClosingStarted' });

    expect(reduce(closing, { type: 'cashClosed' })).toEqual({ kind: 'login', failure: null });
  });

  test('falha da API guarda o estado de origem para voltar', () => {
    const state = reduce(cashOpened(), {
      type: 'barcodeScanned',
      barcode: '7891000100103',
      quantity: 1,
    });

    expect(reduce(state, { type: 'apiFailed', problem })).toEqual({
      kind: 'error',
      problem,
      returnTo: state,
    });
  });

  test('ENTER reconhece o erro e volta ao estado anterior', () => {
    const state = selling();
    const failed = reduce(state, { type: 'apiFailed', problem });

    expect(reduce(failed, { type: 'confirm' })).toBe(state);
  });

  test('ESC também fecha o erro', () => {
    const state = selling();
    const failed = reduce(state, { type: 'apiFailed', problem });

    expect(reduce(failed, { type: 'cancel' })).toBe(state);
  });

  test('falha durante o erro não empilha: a primeira precisa ser reconhecida', () => {
    const failed = reduce(selling(), { type: 'apiFailed', problem });

    expect(
      reduce(failed, { type: 'apiFailed', problem: { ...problem, status: 500, detail: 'Boom.' } }),
    ).toBe(failed);
  });

  test('bipe no pagamento é ignorado: modal bloqueia o leitor', () => {
    const paying = reduce(selling(), { type: 'paymentStarted' });

    expect(reduce(paying, { type: 'barcodeScanned', barcode: '7891000100103', quantity: 1 })).toBe(
      paying,
    );
  });

  test('ação fora de contexto é ignorada e devolve o mesmo estado', () => {
    const cases: [State, Action][] = [
      [initialState, { type: 'cancel' }],
      [initialState, { type: 'confirm' }],
      [initialState, { type: 'barcodeScanned', barcode: '7891000100103', quantity: 1 }],
      [initialState, { type: 'cashClosed' }],
      [initialState, { type: 'saleCompleted' }],
      [loggedIn(), { type: 'paymentStarted' }],
      [loggedIn(), { type: 'cancel' }],
      [cashOpened(), { type: 'cashOpened', sessionId: 'session-2' }],
      [cashOpened(), { type: 'saleCompleted' }],
      [selling(), { type: 'cancel' }],
      [selling(), { type: 'cashClosed' }],
    ];

    for (const [state, action] of cases) {
      expect(reduce(state, action), `${state.kind} + ${action.type}`).toBe(state);
    }
  });

  test('fluxo completo: login → abertura → venda → pagamento → fechamento → login', () => {
    let state: State = initialState;

    state = reduce(state, { type: 'loginSucceeded', operator, register });
    state = reduce(state, { type: 'cashOpened', sessionId: 'session-1' });
    state = reduce(state, {
      type: 'barcodeScanned',
      barcode: '7891000100103',
      quantity: 1,
    });
    state = reduce(state, { type: 'saleUpdated', sale });
    state = reduce(state, { type: 'paymentStarted' });
    state = reduce(state, { type: 'saleUpdated', sale: { ...sale, total: 5 } });
    state = reduce(state, { type: 'saleCompleted' });

    expect(state).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
    });

    state = reduce(state, { type: 'cashClosingStarted' });
    state = reduce(state, { type: 'cashClosed' });

    expect(state).toEqual({ kind: 'login', failure: null });
  });
});
