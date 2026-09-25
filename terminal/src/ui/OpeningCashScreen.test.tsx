import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CashMovementOutcome,
  CashRegistersOutcome,
  CompleteSaleOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
  CustomerSaleOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  ProductStockOutcome,
  SaleItemMutationOutcome,
  SearchCustomersOutcome,
  SearchProductsOutcome,
  CancelSaleOutcome,
  CashSessionSummaryOutcome,
  CloseCashSessionOutcome,
  TerminalApi,
  SaleReloadOutcome,
  SessionOutcome,
} from '../api/terminalApi';
import type { ApiProblem, OpeningCashState } from '../core/state';
import { OpeningCashScreen } from './OpeningCashScreen';

/**
 * Tela de abertura de caixa (1107) pelo `ink-testing-library`, com a camada de API dublada: o que se
 * testa é o fluxo da tela — máscara do valor, valor vazio, sessão criada, caixa já aberto com aviso
 * e falha bloqueante —, nunca o HTTP (esse é do `@minimarket/api-client`).
 */

const OPERADOR = { id: 'u1', name: 'Ana Souza' };
const CAIXA = { id: 'r1', name: 'Caixa principal' };

const STATE: OpeningCashState = { kind: 'openingCash', operator: OPERADOR, register: CAIXA };

const PROBLEM: ApiProblem = { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' };

function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  return {
    login: vi.fn(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR })),
    logout: vi.fn(async () => undefined),
    // a loja do cabeçalho e a releitura da reconciliação (1117) fecham o contrato; os fluxos que
    // precisam delas sobrescrevem no próprio teste
    currentSession: vi.fn(async (): Promise<SessionOutcome> => ({ ok: true, store: null })),
    getSale: vi.fn(
      async (): Promise<SaleReloadOutcome> => ({
        ok: false,
        problem: { status: 404, code: 'SALE_NOT_FOUND', detail: 'venda não encontrada' },
      }),
    ),    listCashRegisters: vi.fn(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    ),
    openCashRegister: vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: 'session-1' }),
    ),
    currentCashSession: vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: 'session-9' }),
    ),
    // a abertura não bipa: o bipe (1109) entra na camada tipada só para fechar o contrato
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN', quantity: null },
      }),
    ),
    searchProducts: vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [] }),
    ),
    productStock: vi.fn(
      async (): Promise<ProductStockOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    createSale: vi.fn(
      async (): Promise<CreateSaleOutcome> => ({
        ok: true,
        sale: {
          id: 'sale-1',
          items: [],
          subtotal: 0,
          discountAmount: 0,
          total: 0,
          paidAmount: 0,
          changeAmount: 0,
          payments: [],
          customerId: null,
        },
      }),
    ),
    addSaleItem: vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: false,
        kind: 'notFound',
        barcode: '7891000100103',
      }),
    ),
    // a abertura não mexe em item: `+`/`-` e DEL (1110) entram só para fechar o contrato
    changeSaleItemQuantity: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    removeSaleItem: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    // o desconto (1111) é do modal de venda: aqui só fecha o contrato
    applyDiscount: vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    // o cliente (1112) é do F6 da venda: aqui só fecha o contrato
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    // o pagamento (1113) é da tela de pagamento: aqui só fecha o contrato
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    // a gaveta (1114) é do modal do F7/F8: aqui só fecha o contrato
    withdrawCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    // o fechamento e o cancelamento (1115) são do shell: aqui só fecha o contrato
    cashSessionSummary: vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: false, problem: PROBLEM }),
    ),
    closeCashSession: vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: PROBLEM,
      }),
    ),
    cancelSale: vi.fn(
      async (): Promise<CancelSaleOutcome> => ({ ok: false, kind: 'retryable', problem: PROBLEM }),
    ),
    ...overrides,
  };
}

function renderScreen(api: TerminalApi, dispatch = vi.fn()) {
  return render(<OpeningCashScreen state={STATE} api={api} dispatch={dispatch} />);
}

/** O render do Ink não é síncrono com o `stdin.write`: espera o frame alcançar o texto. */
async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(() => {
    expect(lastFrame()).toContain(text);
  });
}

describe('OpeningCashScreen', () => {
  test('mostra operador, caixa e o valor vazio com a dica', () => {
    const { lastFrame } = renderScreen(apiStub());

    expect(lastFrame()).toContain('Abertura de caixa');
    expect(lastFrame()).toContain('Operador: Ana Souza · Caixa: Caixa principal');
    expect(lastFrame()).toContain('Fundo de troco: R$ 0,00');
    expect(lastFrame()).toContain('digite o valor de abertura: 1250 vira R$ 12,50');
  });

  test('dígitos viram a máscara de centavos e o ENTER abre com o valor em reais', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: 'session-1' }),
    );
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(apiStub({ openCashRegister }), dispatch);

    stdin.write('1250');
    await expectFrame(lastFrame, 'Fundo de troco: R$ 12,50');

    stdin.write('\r');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({ type: 'cashOpened', sessionId: 'session-1' });
    });
    expect(openCashRegister).toHaveBeenCalledWith('r1', 12.5);
  });

  test('ENTER com o campo vazio não chama a API e avisa o operador', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: 'session-1' }),
    );
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(apiStub({ openCashRegister }), dispatch);

    stdin.write('\r');

    await expectFrame(lastFrame, 'informe o valor de abertura');
    expect(openCashRegister).not.toHaveBeenCalled();
    expect(dispatch).not.toHaveBeenCalled();
  });

  test('enquanto a abertura está em curso mostra "abrindo..." e não dispara duas', async () => {
    const openCashRegister = vi.fn(
      () =>
        new Promise<OpenCashRegisterOutcome>(() => {
          // fica pendente de propósito: é o estado de abertura em curso que o teste quer ver
        }),
    );
    const { lastFrame, stdin } = renderScreen(apiStub({ openCashRegister }));

    stdin.write('100');
    await expectFrame(lastFrame, 'R$ 1,00');
    stdin.write('\r');

    await expectFrame(lastFrame, 'abrindo...');
    expect(openCashRegister).toHaveBeenCalledTimes(1);
  });

  test('caixa já aberto busca a sessão existente, avisa e o ENTER segue com ela', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: 'alreadyOpen' }),
    );
    const currentCashSession = vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: 'session-9' }),
    );
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(
      apiStub({ openCashRegister, currentCashSession }),
      dispatch,
    );

    stdin.write('500');
    await expectFrame(lastFrame, 'Fundo de troco: R$ 5,00');
    stdin.write('\r');

    await expectFrame(lastFrame, 'caixa já está aberto — seguindo para a venda com a sessão existente');
    expect(currentCashSession).toHaveBeenCalledWith('r1');
    expect(dispatch).not.toHaveBeenCalled(); // o aviso espera o ENTER do operador

    stdin.write('\r');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({ type: 'cashOpened', sessionId: 'session-9' });
    });
    expect(openCashRegister).toHaveBeenCalledTimes(1); // não reabre nada
  });

  test('falha da abertura vai para a tela de erro com o problem+json', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: 'failed', problem: PROBLEM }),
    );
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(apiStub({ openCashRegister }), dispatch);

    stdin.write('50');
    await expectFrame(lastFrame, 'R$ 0,50');
    stdin.write('\r');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({ type: 'apiFailed', problem: PROBLEM });
    });
  });

  test('caixa já aberto sem sessão corrente legível é falha bloqueante', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: 'alreadyOpen' }),
    );
    const currentCashSession = vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({
        ok: false,
        problem: { status: 404, code: 'CASH_SESSION_NOT_OPEN', detail: 'caixa sem sessão aberta' },
      }),
    );
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(
      apiStub({ openCashRegister, currentCashSession }),
      dispatch,
    );

    stdin.write('50');
    await expectFrame(lastFrame, 'R$ 0,50');
    stdin.write('\r');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({
        type: 'apiFailed',
        problem: { status: 404, code: 'CASH_SESSION_NOT_OPEN', detail: 'caixa sem sessão aberta' },
      });
    });
    expect(lastFrame()).not.toContain('caixa já está aberto');
  });
});
