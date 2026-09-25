import { describe, expect, test } from 'vitest';

import { reduce, type Action } from './reducer';
import {
  initialState,
  type ApiProblem,
  type CashClosingView,
  type ReceiptView,
  type SaleView,
  type State,
} from './state';

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
  paidAmount: 0,
  changeAmount: 0,
  payments: [],
  customerId: null,
};

/** Resumo que o `complete` devolveu: a tela de sucesso mostra estes números, não uma conta (1113). */
const receipt: ReceiptView = { number: 42, total: 5.5, changeAmount: 0.5 };

/** Conferência que o `close` devolveu: contado, esperado e a diferença — todas do servidor (1115). */
const closingView: CashClosingView = { countedAmount: 6, expectedAmount: 5.5, differenceAmount: 0.5 };

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
      receipt: null,
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
      receipt: null,
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
      receipt: null,
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

  test('F9 com a venda sem itens não abre o pagamento: venda vazia não conclui (BR-05)', () => {
    const empty = reduce(selling(), {
      type: 'saleUpdated',
      sale: { ...sale, items: [], subtotal: 0, total: 0 },
    });

    expect(reduce(empty, { type: 'paymentStarted' })).toBe(empty);
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
      receipt: null,
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

  test('concluir guarda o resumo do servidor e volta para a venda, pronta para a próxima', () => {
    const paying = reduce(selling(), { type: 'paymentStarted' });

    expect(reduce(paying, { type: 'saleCompleted', receipt })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
      receipt,
    });
  });

  test('ENTER na tela de sucesso limpa o resumo e deixa a próxima venda vazia', () => {
    const completed = reduce(reduce(selling(), { type: 'paymentStarted' }), {
      type: 'saleCompleted',
      receipt,
    });

    expect(reduce(completed, { type: 'receiptDismissed' })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
      receipt: null,
    });
  });

  test('resumo dispensado sem resumo à vista é ignorado e devolve o mesmo estado', () => {
    const state = selling();

    expect(reduce(state, { type: 'receiptDismissed' })).toBe(state);
  });

  test('F10 entra no fechamento levando a venda aberta', () => {
    expect(reduce(selling(), { type: 'cashClosingStarted' })).toEqual({
      kind: 'closingCash',
      operator,
      register,
      sessionId: 'session-1',
      sale,
      closing: null,
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
      receipt: null,
    });
  });

  test('fechamento gravado guarda a conferência do servidor na tela', () => {
    const closing = reduce(selling(), { type: 'cashClosingStarted' });

    expect(reduce(closing, { type: 'cashCloseSucceeded', closing: closingView })).toEqual({
      kind: 'closingCash',
      operator,
      register,
      sessionId: 'session-1',
      sale,
      closing: closingView,
    });
  });

  test('caixa já fechado: o ESC não volta para a venda, porque não há venda num caixa fechado', () => {
    const closed = reduce(reduce(selling(), { type: 'cashClosingStarted' }), {
      type: 'cashCloseSucceeded',
      closing: closingView,
    });

    expect(reduce(closed, { type: 'cancel' })).toBe(closed);
  });

  test('caixa fechado volta ao login', () => {
    const closing = reduce(selling(), { type: 'cashClosingStarted' });

    expect(reduce(closing, { type: 'cashClosed' })).toEqual({ kind: 'login', failure: null });
  });

  test('troca de operador encerra a sessão de login sem passar pelo caixa fechado (1118)', () => {
    const state = selling();

    expect(reduce(state, { type: 'sessionEnded' })).toEqual({ kind: 'login', failure: null });
  });

  test('troca de operador vale na venda ainda sem venda criada (o F12 não depende do bipe)', () => {
    const state = cashOpened();

    expect(reduce(state, { type: 'sessionEnded' })).toEqual({ kind: 'login', failure: null });
  });

  test('F4 cancela a venda: volta à venda vazia, sem venda e sem bipe pendente', () => {
    expect(reduce(selling(), { type: 'saleCancelled' })).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
      receipt: null,
    });
  });

  test('cancelar sem venda criada é ignorado e devolve o mesmo estado', () => {
    const state = cashOpened();

    expect(reduce(state, { type: 'saleCancelled' })).toBe(state);
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
      [initialState, { type: 'sessionEnded' }],
      [initialState, { type: 'saleCompleted', receipt }],
      [initialState, { type: 'saleCancelled' }],
      [initialState, { type: 'cashCloseSucceeded', closing: closingView }],
      [loggedIn(), { type: 'paymentStarted' }],
      [loggedIn(), { type: 'cancel' }],
      [loggedIn(), { type: 'sessionEnded' }],
      [cashOpened(), { type: 'cashOpened', sessionId: 'session-2' }],
      [cashOpened(), { type: 'saleCompleted', receipt }],
      [cashOpened(), { type: 'cashCloseSucceeded', closing: closingView }],
      [selling(), { type: 'cancel' }],
      [selling(), { type: 'cashClosed' }],
      [selling(), { type: 'cashCloseSucceeded', closing: closingView }],
      [reduce(selling(), { type: 'paymentStarted' }), { type: 'sessionEnded' }],
    ];

    for (const [state, action] of cases) {
      expect(reduce(state, action), `${state.kind} + ${action.type}`).toBe(state);
    }
  });

  test('fluxo completo: login → abertura → venda → pagamento → sucesso → fechamento → login', () => {
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
    state = reduce(state, { type: 'saleCompleted', receipt });

    expect(state).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: null,
      pendingScan: null,
      receipt,
    });

    state = reduce(state, { type: 'receiptDismissed' });
    state = reduce(state, { type: 'cashClosingStarted' });
    state = reduce(state, { type: 'cashCloseSucceeded', closing: closingView });
    state = reduce(state, { type: 'cancel' }); // caixa fechado: o ESC não volta para a venda
    state = reduce(state, { type: 'cashClosed' });

    expect(state).toEqual({ kind: 'login', failure: null });
  });
});

/** Problemas do 1117: cada `code` tem uma política, e ela muda o destino da falha. */
const sessionProblem: ApiProblem = {
  status: 401,
  code: 'SESSION_EXPIRED',
  detail: 'sessão expirada; faça login novamente',
};

const reconcileProblem: ApiProblem = {
  status: 409,
  code: 'IDEMPOTENCY_KEY_REUSED',
  detail: 'chave de idempotência já utilizada',
};

/** Venda preservada pela queda de sessão: o caixa que a abriu e a venda como o servidor a tinha. */
const ticket = { registerId: register.id, registerName: register.name, sale };

describe('reducer: sessão, idempotência e erros (1117)', () => {
  test('401 no meio da venda leva ao login com o aviso e guarda a venda', () => {
    const failed = reduce(selling(), { type: 'apiFailed', problem: sessionProblem });

    expect(failed).toEqual({
      kind: 'login',
      failure: null,
      notice: 'sessão expirada — entre novamente; a venda continua aberta',
      resume: ticket,
    });
  });

  test('401 no pagamento também preserva a venda que estava sendo paga', () => {
    const paying = reduce(selling(), { type: 'paymentStarted' });

    expect(reduce(paying, { type: 'apiFailed', problem: sessionProblem })).toMatchObject({
      kind: 'login',
      resume: ticket,
    });
  });

  test('401 na tela de erro volta ao login com a venda que o erro escondia', () => {
    // um 409 SALE_NOT_OPEN bloqueia e guarda a venda de origem; a sessão cai durante a espera
    const blocked = reduce(selling(), {
      type: 'apiFailed',
      problem: { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' },
    });
    const expired = reduce(blocked, { type: 'apiFailed', problem: sessionProblem });

    expect(expired).toMatchObject({ kind: 'login', resume: ticket });
  });

  test('401 com o operador já no login só troca o aviso: a venda guardada fica onde está', () => {
    const expired = reduce(selling(), { type: 'apiFailed', problem: sessionProblem });
    const again = reduce(expired, { type: 'apiFailed', problem: sessionProblem });

    expect(again).toMatchObject({ kind: 'login', resume: ticket });
  });

  test('o login de novo atravessa a abertura e retoma a venda no mesmo caixa', () => {
    const expired = reduce(selling(), { type: 'apiFailed', problem: sessionProblem });
    const logged = reduce(expired, { type: 'loginSucceeded', operator, register });

    expect(logged).toMatchObject({ kind: 'openingCash', resume: ticket });

    const reopened = reduce(logged, { type: 'cashOpened', sessionId: 'session-2' });

    expect(reopened).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-2',
      sale,
      pendingScan: null,
      receipt: null,
      notice: 'venda retomada — os itens foram preservados',
    });
  });

  test('a retomada não vale em outro caixa: a venda fica no de origem, com o aviso', () => {
    const expired = reduce(selling(), { type: 'apiFailed', problem: sessionProblem });
    const other = { id: 'r-2', name: 'Caixa 2' };
    const logged = reduce(expired, { type: 'loginSucceeded', operator, register: other });

    expect(reduce(logged, { type: 'cashOpened', sessionId: 'session-2' })).toEqual({
      kind: 'saleOpen',
      operator,
      register: other,
      sessionId: 'session-2',
      sale: null,
      pendingScan: null,
      receipt: null,
      notice: 'a venda aberta no caixa Caixa 1 não foi retomada aqui — conclua-a naquele caixa',
    });
  });

  test('a primeira ação depois da retomada dispensa o aviso', () => {
    const expired = reduce(selling(), { type: 'apiFailed', problem: sessionProblem });
    const resumed = reduce(reduce(expired, { type: 'loginSucceeded', operator, register }), {
      type: 'cashOpened',
      sessionId: 'session-2',
    });

    expect(
      reduce(resumed, { type: 'barcodeScanned', barcode: '7891000100103', quantity: 1 }),
    ).toMatchObject({ notice: undefined });
  });

  test('409 de idempotência não empilha tela de erro: o estado fica e o shell é quem relê', () => {
    const state = selling();

    expect(reduce(state, { type: 'apiFailed', problem: reconcileProblem })).toBe(state);
  });

  test('a venda relida do servidor substitui a exibida e mostra o aviso da conferência', () => {
    const reconciled: SaleView = { ...sale, total: 6 };

    expect(
      reduce(selling(), {
        type: 'saleReconciled',
        sale: reconciled,
        notice: 'outro terminal alterou esta venda — estado conferido no servidor',
      }),
    ).toEqual({
      kind: 'saleOpen',
      operator,
      register,
      sessionId: 'session-1',
      sale: reconciled,
      pendingScan: null,
      receipt: null,
      notice: 'outro terminal alterou esta venda — estado conferido no servidor',
    });
  });

  test('a ação sessionExpired vale em qualquer tela e o ESC reconhece o erro guardado', () => {
    const expired = reduce(selling(), {
      type: 'sessionExpired',
      message: 'sessão expirada — entre novamente; a venda continua aberta',
    });

    expect(expired).toMatchObject({ kind: 'login', resume: ticket });
  });
});
