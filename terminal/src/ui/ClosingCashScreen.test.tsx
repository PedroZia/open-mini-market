import { Text } from 'ink';
import { render } from 'ink-testing-library';
import { useReducer } from 'react';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CancelSaleOutcome,
  CashMovementOutcome,
  CashRegistersOutcome,
  CashSessionSummaryOutcome,
  CashSessionSummaryView,
  CloseCashSessionIntent,
  CloseCashSessionOutcome,
  CompleteSaleOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
  CustomerSaleOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  SaleItemMutationOutcome,
  SearchCustomersOutcome,
  TerminalApi,
} from '../api/terminalApi';
import { reduce } from '../core/reducer';
import type { ApiProblem, ClosingCashState } from '../core/state';
import { ClosingCashScreen } from './ClosingCashScreen';

/**
 * Tela do fechamento de caixa (F10, 1115) pelo `ink-testing-library`, com o reducer real (1103) por
 * trás e a camada de API dublada: o que se testa é o fluxo da tela — o resumo do servidor, a máscara
 * do valor contado, a confirmação antes da API, a diferença do servidor depois do 200, a saída para
 * o login (com e sem logout) e as recusas do fechamento —, nunca o HTTP (esse é do
 * `@minimarket/api-client`).
 *
 * O ESC não é desta tela: quem o resolve é o canal cru do shell, que despacha `cancel` no reducer
 * (é o que o teste do App cobre). Aqui o harness mostra o `kind` do estado quando a tela sai de cena,
 * que é como o "volta ao login" fica observável.
 */

const OPERADOR = { id: 'u1', name: 'Ana Souza' };
const CAIXA = { id: 'r1', name: 'Caixa principal' };

/** Estado em que o shell entrega a tela: caixa aberto e a venda preservada para o ESC (1115). */
const STATE: ClosingCashState = {
  kind: 'closingCash',
  operator: OPERADOR,
  register: CAIXA,
  sessionId: 's1',
  sale: null,
  closing: null,
};

const PROBLEM: ApiProblem = { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' };

/** Resumo como o servidor o devolve: esperado, formas de pagamento e a gaveta da sessão (BR-12). */
function summary(overrides: Partial<CashSessionSummaryView> = {}): CashSessionSummaryView {
  return {
    sessionId: 'session-1',
    status: 'OPEN',
    openingAmount: 10,
    expectedAmount: 44.9,
    countedAmount: null,
    differenceAmount: null,
    totalsByType: { OPENING: 10, SALE: 34.9, WITHDRAWAL: 3, SUPPLY: 3 },
    paymentsByMethod: { CASH: 34.9, PIX: 0, DEBIT: 0, CREDIT: 0, VOUCHER: 0 },
    ...overrides,
  };
}

/** Dublê da camada de API: só o fechamento entra aqui; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  return {
    login: vi.fn(
      async (): Promise<LoginOutcome> => ({ ok: false, kind: 'rejected', message: PROBLEM.detail }),
    ),
    logout: vi.fn(async () => undefined),
    listCashRegisters: vi.fn(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    ),
    openCashRegister: vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: 'alreadyOpen' }),
    ),
    currentCashSession: vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: false, problem: PROBLEM }),
    ),
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({ ok: false, problem: PROBLEM }),
    ),
    createSale: vi.fn(
      async (): Promise<CreateSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    addSaleItem: vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    changeSaleItemQuantity: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    removeSaleItem: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    applyDiscount: vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: PROBLEM,
      }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    withdrawCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    cashSessionSummary: vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    ),
    closeCashSession: vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    ),
    cancelSale: vi.fn(
      async (): Promise<CancelSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    ...overrides,
  };
}

/** Shell mínimo do teste: com o reducer real, a tela sai de cena quando o estado muda de `kind`. */
function ClosingHarness({ api, initial }: { api: TerminalApi; initial: ClosingCashState }) {
  const [state, dispatch] = useReducer(reduce, initial);

  if (state.kind !== 'closingCash') {
    return <Text>estado: {state.kind}</Text>;
  }

  return <ClosingCashScreen state={state} api={api} dispatch={dispatch} />;
}

function renderScreen(api: TerminalApi = apiStub(), initial: ClosingCashState = STATE) {
  return render(<ClosingHarness api={api} initial={initial} />);
}

/** O render do Ink não é síncrono com o `stdin.write`: espera o frame alcançar o texto. */
async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(() => {
    expect(lastFrame()).toContain(text);
  });
}

/** Digita o valor contado e leva até a confirmação: o ENTER de baixo é o que chama a API. */
async function typeAndConfirm(
  ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
  digits = '3000',
): Promise<void> {
  ui.stdin.write(digits);
  await expectFrame(ui.lastFrame, 'Valor contado: R$ 30,00');
  ui.stdin.write('\r'); // formulário: só confirma
  await expectFrame(ui.lastFrame, 'fechar o caixa com R$ 30,00?');
}

describe('ClosingCashScreen (F10, 1115)', () => {
  test('busca o resumo da sessão e mostra aberto, esperado, formas e a gaveta do servidor', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    );
    const ui = renderScreen(apiStub({ cashSessionSummary }));

    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    expect(cashSessionSummary).toHaveBeenCalledWith('s1');
    expect(ui.lastFrame()).toContain('Aberto: R$ 10,00');
    expect(ui.lastFrame()).toContain('DINHEIRO: R$ 34,90');
    expect(ui.lastFrame()).toContain('PIX: R$ 0,00');
    expect(ui.lastFrame()).toContain('Sangrias: R$ 3,00 · Suprimentos: R$ 3,00');
    expect(ui.lastFrame()).toContain('Valor contado: R$ 0,00');
    expect(ui.lastFrame()).toContain('digite o valor contado em centavos: 1000 vira R$ 10,00');
  });

  test('a falha ao ler o resumo bloqueia na tela de erro (o shell volta e a tela relê)', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: false, problem: PROBLEM }),
    );
    const ui = renderScreen(apiStub({ cashSessionSummary }));

    await expectFrame(ui.lastFrame, 'estado: error');
    expect(ui.lastFrame()).not.toContain('Valor contado:');
  });

  test('a máscara é em centavos e nada vai à API antes do ENTER da confirmação', async () => {
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    );
    const ui = renderScreen(apiStub({ closeCashSession }));

    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);

    expect(ui.lastFrame()).toContain('Valor contado: R$ 30,00');
    expect(ui.lastFrame()).toContain('Esperado: R$ 44,90'); // esperado × contado antes de fechar
    expect(closeCashSession).not.toHaveBeenCalled(); // o ENTER do formulário só confirmou
  });

  test('ENTER com o campo vazio não confirma nada: a tela pede o valor contado', async () => {
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: PROBLEM,
      }),
    );
    const ui = renderScreen(apiStub({ closeCashSession }));

    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o valor contado');
    expect(ui.lastFrame()).not.toContain('fechar o caixa com');
    expect(closeCashSession).not.toHaveBeenCalled();
  });

  test('fechar manda o contado e a chave, mostra a diferença do servidor e o ENTER faz logout', async () => {
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    );
    const logout = vi.fn(async () => undefined);
    const ui = renderScreen(apiStub({ closeCashSession, logout }));
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);

    ui.stdin.write('\r'); // confirmação: esta chama a API

    await expectFrame(ui.lastFrame, 'Diferença (servidor): R$ -14,90');
    expect(ui.lastFrame()).toContain('falta dinheiro na gaveta');
    expect(ui.lastFrame()).toContain('Esperado: R$ 44,90 · Contado: R$ 30,00');
    expect(closeCashSession).toHaveBeenCalledWith('r1', { countedAmount: 30 }, expect.any(String));

    ui.stdin.write('\r'); // ENTER com o caixa fechado: revoga a sessão e volta ao login

    await expectFrame(ui.lastFrame, 'estado: login');
    expect(logout).toHaveBeenCalledTimes(1);
  });

  test('qualquer outra tecla com o caixa fechado volta ao login sem revogar a sessão', async () => {
    const logout = vi.fn(async () => undefined);
    const ui = renderScreen(apiStub({ logout }));
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Diferença (servidor): R$ -14,90');

    ui.stdin.write('x'); // outra tecla: volta sem logout

    await expectFrame(ui.lastFrame, 'estado: login');
    expect(logout).not.toHaveBeenCalled();
  });

  test('sobra no caixa é diferença do servidor, não conta da TUI', async () => {
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 50, expectedAmount: 44.9, differenceAmount: 5.1 },
      }),
    );
    const ui = renderScreen(apiStub({ closeCashSession }));
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'Diferença (servidor): R$ 5,10');
    expect(ui.lastFrame()).toContain('sobra dinheiro na gaveta');
  });

  test('409 SESSION_HAS_OPEN_SALES mostra a mensagem e o caixa não fecha', async () => {
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'há venda em andamento — cancele a venda (F4) antes de fechar',
      }),
    );
    const ui = renderScreen(apiStub({ closeCashSession }));
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'há venda em andamento — cancele a venda (F4) antes de fechar');
    expect(ui.lastFrame()).toContain('Valor contado: R$ 30,00'); // segue na tela, com o valor
    expect(ui.lastFrame()).not.toContain('Diferença (servidor)');
    expect(closeCashSession).toHaveBeenCalledTimes(1);
  });

  test('rede: aviso de retry na confirmação e o ENTER refaz com a mesma chave', async () => {
    /** Chaves das tentativas na ordem em que saíram: o retry tem de reusar a mesma (§8). */
    const sentKeys: string[] = [];
    const closeCashSession = vi.fn(
      async (
        registerId: string,
        counted: CloseCashSessionIntent,
        key: string,
      ): Promise<CloseCashSessionOutcome> => {
        sentKeys.push(`${registerId}:${String(counted.countedAmount)}:${key}`);
        return {
          ok: false,
          kind: 'retryable',
          problem: { status: 0, code: null, detail: 'fetch failed' },
        };
      },
    );
    const ui = renderScreen(apiStub({ closeCashSession }));
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'falha ao fechar o caixa — ENTER tenta de novo');

    ui.stdin.write('\r'); // retry

    await vi.waitFor(() => {
      expect(closeCashSession).toHaveBeenCalledTimes(2);
    });
    expect(sentKeys[1]).toBe(sentKeys[0]); // mesma chave, mesmo pedido: replay no servidor
  });

  test('falha bloqueante do fechamento (contrato) vai para a tela de erro pelo reducer', async () => {
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: { status: 0, code: null, detail: 'fechamento sem conferência na resposta' },
      }),
    );
    const ui = renderScreen(apiStub({ closeCashSession }));
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await typeAndConfirm(ui);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'estado: error');
  });
});
