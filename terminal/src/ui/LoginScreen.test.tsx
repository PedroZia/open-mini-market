import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CashMovementOutcome,
  CashRegisterOption,
  CashRegistersOutcome,
  CompleteSaleOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
  CustomerSaleOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  SaleItemMutationOutcome,
  SearchCustomersOutcome,
  CancelSaleOutcome,
  CashSessionSummaryOutcome,
  CloseCashSessionOutcome,
  TerminalApi,
} from '../api/terminalApi';
import { LoginScreen } from './LoginScreen';

/**
 * Contrato da tela de login com o reducer (1103): a tela não troca de estado sozinha — ela relata o
 * fato e o shell decide. Aqui o `dispatch` é um espião, então dá para conferir exatamente o que sai
 * da tela (o `loginSucceeded` que leva à abertura de caixa) e os casos em que não sai nada.
 */

const OPERADOR = { id: 'u1', name: 'Ana Souza' };

const CAIXA_01: CashRegisterOption = {
  id: 'r1',
  code: '01',
  name: 'Caixa principal',
  open: false,
  operatorName: null,
};
const CAIXA_02: CashRegisterOption = {
  id: 'r2',
  code: '02',
  name: 'Caixa do fundo',
  open: true,
  operatorName: 'Maria',
};

function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  return {
    login: vi.fn(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR })),
    logout: vi.fn(async () => undefined),
    listCashRegisters: vi.fn(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [CAIXA_01, CAIXA_02] }),
    ),
    openCashRegister: vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: 'session-1' }),
    ),
    currentCashSession: vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: 'session-1' }),
    ),
    // o login não bipa: o bipe (1109) entra na camada tipada só para fechar o contrato
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { name: 'Arroz 5kg', price: 24.9, quantity: null },
      }),
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
    // o login não mexe em item: `+`/`-` e DEL (1110) entram só para fechar o contrato
    changeSaleItemQuantity: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    removeSaleItem: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    // o desconto (1111) é do modal de venda: aqui só fecha o contrato
    applyDiscount: vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    // o cliente (1112) é do F6 da venda: aqui só fecha o contrato
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    // o pagamento (1113) é da tela de pagamento: aqui só fecha o contrato
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    // a gaveta (1114) é do modal do F7/F8: aqui só fecha o contrato
    withdrawCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    // o fechamento e o cancelamento (1115) são do shell: aqui só fecha o contrato
    cashSessionSummary: vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({
        ok: false,
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    closeCashSession: vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    cancelSale: vi.fn(
      async (): Promise<CancelSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na tela de login' },
      }),
    ),
    ...overrides,
  };
}

function renderScreen(api: TerminalApi, dispatch = vi.fn()) {
  return render(
    <LoginScreen state={{ kind: 'login', failure: null }} api={api} dispatch={dispatch} />,
  );
}

async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(() => {
    expect(lastFrame()).toContain(text);
  });
}

/**
 * Digita as credenciais esperando o frame entre as teclas: o `useInput` do Ink só re-registra o
 * callback (com o estado do último render) no efeito seguinte, e no teste as escritas aconteceriam
 * todas no mesmo tick.
 */
async function signIn(
  lastFrame: () => string | undefined,
  stdin: { write: (data: string) => void },
): Promise<void> {
  stdin.write('ana');
  await expectFrame(lastFrame, 'Usuário: ana');
  stdin.write('\t');
  await expectFrame(lastFrame, '› Senha:');
  stdin.write('segredo');
  await expectFrame(lastFrame, 'Senha: •••••••');
  stdin.write('\r');
}

describe('LoginScreen', () => {
  test('ENTER no caixa escolhido despacha loginSucceeded com operador e caixa', async () => {
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(apiStub(), dispatch);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');

    stdin.write('\u001b[B'); // desce para o segundo caixa
    await expectFrame(lastFrame, '› 02');

    stdin.write('\r');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({
        type: 'loginSucceeded',
        operator: OPERADOR,
        register: { id: 'r2', name: 'Caixa do fundo' },
      });
    });
  });

  test('lista vazia avisa e ENTER não navega', async () => {
    const dispatch = vi.fn();
    const api = apiStub({
      listCashRegisters: vi.fn(
        async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
      ),
    });
    const { lastFrame, stdin } = renderScreen(api, dispatch);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'nenhum caixa ativo');

    stdin.write('\r');

    expect(lastFrame()).toContain('Escolha o caixa');
    expect(dispatch).not.toHaveBeenCalled();
  });

  test('a lista pendente mostra o carregamento', async () => {
    const api = apiStub({
      listCashRegisters: vi.fn(
        () =>
          new Promise<CashRegistersOutcome>(() => {
            // fica pendente de propósito: é o estado de carregando que o teste quer ver
          }),
      ),
    });
    const { lastFrame, stdin } = renderScreen(api);

    await signIn(lastFrame, stdin);

    await expectFrame(lastFrame, 'carregando caixas...');
  });

  test('campo vazio não chama a API e avisa o operador', async () => {
    const login = vi.fn(async (): Promise<LoginOutcome> => ({ ok: true, operator: OPERADOR }));
    const { lastFrame, stdin } = renderScreen(apiStub({ login }));

    stdin.write('\r');

    await expectFrame(lastFrame, 'informe usuário e senha');
    expect(login).not.toHaveBeenCalled();
  });

  test('ao confirmar o caixa, revoga a sessão provisória e loga de novo com o caixa escolhido', async () => {
    const calls: string[] = [];
    const login = vi.fn(
      async (
        _username: string,
        _password: string,
        cashRegisterId?: string,
      ): Promise<LoginOutcome> => {
        calls.push(cashRegisterId === undefined ? 'login sem caixa' : `login ${cashRegisterId}`);
        return { ok: true, operator: OPERADOR };
      },
    );
    const logout = vi.fn(async () => {
      calls.push('logout');
    });
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(apiStub({ login, logout }), dispatch);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');

    stdin.write('\u001b[B'); // desce para o segundo caixa
    await expectFrame(lastFrame, '› 02');
    stdin.write('\r');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({
        type: 'loginSucceeded',
        operator: OPERADOR,
        register: { id: 'r2', name: 'Caixa do fundo' },
      });
    });

    // a sessão provisória é revogada antes do login vinculado, e a senha segue com o operador
    expect(calls).toEqual(['login sem caixa', 'logout', 'login r2']);
    expect(login).toHaveBeenLastCalledWith('ana', 'segredo', 'r2');
  });

  test('recusa do login vinculado volta às credenciais com a senha fora do campo', async () => {
    let first = true;
    const login = vi.fn(async (): Promise<LoginOutcome> => {
      if (first) {
        first = false;
        return { ok: true, operator: OPERADOR };
      }
      return { ok: false, kind: 'rejected', message: 'caixa não encontrado ou inativo' };
    });
    const dispatch = vi.fn();
    const { lastFrame, stdin } = renderScreen(apiStub({ login }), dispatch);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');
    stdin.write('\r');

    // a tela volta às credenciais e relata a recusa; a mensagem exibida é do reducer (`loginRejected`)
    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({
        type: 'loginRejected',
        message: 'caixa não encontrado ou inativo',
      });
    });
    await expectFrame(lastFrame, 'Usuário: ana');
    expect(lastFrame()).not.toContain('••••'); // a senha recusada sai do campo
  });
});
