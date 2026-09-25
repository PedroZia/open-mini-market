import type { ApiClient } from '@minimarket/api-client';
import { render } from 'ink-testing-library';
import { afterEach, describe, expect, test, vi } from 'vitest';

import { clearToken, getToken, setToken } from '../api/session';
import { createTerminalApi } from '../api/terminalApi';
import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CancelSaleOutcome,
  CashMovementOutcome,
  CashMovementView,
  CashRegisterOption,
  CashRegistersOutcome,
  CashSessionSummaryOutcome,
  CashSessionSummaryView,
  CloseCashSessionOutcome,
  CompleteSaleOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
  CustomerOption,
  CustomerSaleOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  ProductOption,
  ProductStockOutcome,
  SaleItemMutationOutcome,
  SearchCustomersOutcome,
  SearchProductsOutcome,
  StockBalanceView,
  TerminalApi,
  SaleReloadOutcome,
  SessionOutcome,
} from '../api/terminalApi';
import type { PaymentMethod, PaymentView, SaleView } from '../core/state';
import { App } from './App';

/**
 * Shell + entrada do operador (1106), abertura de caixa (1107) e venda com o canal cru do teclado
 * (1108) pelo `ink-testing-library`, com a camada de API dublada: o que se testa é o fluxo da tela —
 * login, escolha do caixa, abertura, erro que fica na tela, erro que bloqueia com volta e o F11 do
 * autoteste —, nunca o HTTP (esse é do `@minimarket/api-client`).
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

/**
 * Venda com um item e o desconto calculado pelo servidor (1111): subtotal 24,90 e total já com o
 * desconto — os números são os da resposta, a TUI não calcula nada (BR-12).
 */
function saleDiscounted(discountAmount: number): SaleView {
  return {
    id: 'sale-1',
    items: [
      { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
    ],
    subtotal: 24.9,
    discountAmount,
    total: 24.9 - discountAmount,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId: null,
  };
}

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
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [CAIXA_01, CAIXA_02] }),
    ),
    openCashRegister: vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: 'session-1' }),
    ),
    currentCashSession: vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: 'session-1' }),
    ),
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN', quantity: null },
      }),
    ),
    // a consulta de preço (1116) tem o seu próprio describe; aqui só fecha o contrato
    searchProducts: vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [] }),
    ),
    productStock: vi.fn(
      async (): Promise<ProductStockOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'saldo não usado neste teste' },
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
        ok: true,
        sale: {
          id: 'sale-1',
          items: [
            { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
          ],
          subtotal: 24.9,
          discountAmount: 0,
          total: 24.9,
          paidAmount: 0,
          changeAmount: 0,
          payments: [],
          customerId: null,
        },
      }),
    ),
    // a venda do App bipa: `+`/`-` e DEL (1110) entram só para fechar o contrato
    changeSaleItemQuantity: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    removeSaleItem: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    // desconto (1111): a venda volta com o desconto que o servidor calculou
    applyDiscount: vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: saleDiscounted(2.49) }),
    ),
    // cliente (1112): o F6 do shell entra no teste do próprio fluxo; aqui só fecha o contrato
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [] }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'cliente não usado neste teste' },
      }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'cliente não usado neste teste' },
      }),
    ),
    // pagamento e conclusão (1113): o fluxo do F9 tem o seu próprio describe; aqui só fecha o contrato
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'pagamento não usado neste teste' },
      }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'conclusão não usada neste teste' },
      }),
    ),
    // a gaveta (1114) tem o próprio fluxo: aqui só fecha o contrato
    withdrawCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'sangria não usada neste teste' },
      }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'suprimento não usado neste teste' },
      }),
    ),
    // o fechamento (1115) tem o seu próprio describe; aqui só fecha o contrato
    cashSessionSummary: vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({
        ok: false,
        problem: { status: 0, code: null, detail: 'resumo não usado neste teste' },
      }),
    ),
    closeCashSession: vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'fechamento não usado neste teste' },
      }),
    ),
    cancelSale: vi.fn(
      async (): Promise<CancelSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'cancelamento não usado neste teste' },
      }),
    ),
    ...overrides,
  };
}

/** O render do Ink não é síncrono com o `stdin.write`: espera o frame alcançar o texto. */
async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(() => {
    expect(lastFrame()).toContain(text);
  });
}

/**
 * Digita as credenciais esperando o frame entre as teclas: o `useInput` do Ink só re-registra o
 * callback (com o estado do último render) no efeito seguinte, e no teste as quatro escritas
 * aconteceriam no mesmo tick.
 */
async function typeCredentials(
  lastFrame: () => string | undefined,
  stdin: { write: (data: string) => void },
  username = 'ana',
  password = 'segredo',
): Promise<void> {
  stdin.write(username);
  await expectFrame(lastFrame, `Usuário: ${username}`);
  stdin.write('\t');
  await expectFrame(lastFrame, '› Senha:');
  stdin.write(password);
  await expectFrame(lastFrame, `Senha: ${'•'.repeat(password.length)}`);
}

/** Credenciais e ENTER; o destino (lista de caixas ou mensagem de recusa) é do teste. */
async function signIn(
  lastFrame: () => string | undefined,
  stdin: { write: (data: string) => void },
): Promise<void> {
  await typeCredentials(lastFrame, stdin);
  stdin.write('\r');
}

/** Entra, escolhe o caixa e abre com fundo de troco: a tela de venda é o ponto de partida daqui. */
async function reachSale(ui: {
  lastFrame: () => string | undefined;
  stdin: { write: (data: string) => void };
}): Promise<void> {
  await signIn(ui.lastFrame, ui.stdin);
  await expectFrame(ui.lastFrame, 'Escolha o caixa');
  ui.stdin.write('\r');
  await expectFrame(ui.lastFrame, 'Abertura de caixa');
  ui.stdin.write('1000');
  await expectFrame(ui.lastFrame, 'Fundo de troco: R$ 10,00');
  ui.stdin.write('\r');
  await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
}

describe('App', () => {
  test('login aceito lista os caixas ativos com código, nome, status e operador', async () => {
    const { lastFrame, stdin } = render(<App api={apiStub()} />);

    await signIn(lastFrame, stdin);

    await expectFrame(lastFrame, 'Escolha o caixa');
    expect(lastFrame()).toContain('Operador: Ana Souza');
    expect(lastFrame()).toContain('› 01 Caixa principal — livre');
    expect(lastFrame()).toContain('02 Caixa do fundo — aberto com Maria');
  });

  test('ENTER confirma o caixa e navega para a abertura de caixa', async () => {
    const { lastFrame, stdin } = render(<App api={apiStub()} />);
    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');

    stdin.write('\r');

    await expectFrame(lastFrame, 'Abertura de caixa');
    expect(lastFrame()).toContain('Fundo de troco: R$ 0,00');
  });

  test('abertura confirmada navega para a venda', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: true, sessionId: 'session-1' }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ openCashRegister })} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');
    stdin.write('\r');
    await expectFrame(lastFrame, 'Abertura de caixa');

    stdin.write('1000');
    await expectFrame(lastFrame, 'Fundo de troco: R$ 10,00');
    stdin.write('\r');

    await expectFrame(lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(openCashRegister).toHaveBeenCalledWith('r1', 10);
  });

  test('caixa já aberto avisa e o ENTER segue para a venda com a sessão existente', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: 'alreadyOpen' }),
    );
    const currentCashSession = vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: true, sessionId: 'session-9' }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ openCashRegister, currentCashSession })} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');
    stdin.write('\r');
    await expectFrame(lastFrame, 'Abertura de caixa');

    stdin.write('500');
    await expectFrame(lastFrame, 'Fundo de troco: R$ 5,00');
    stdin.write('\r');

    await expectFrame(lastFrame, 'caixa já está aberto — seguindo para a venda com a sessão existente');
    expect(currentCashSession).toHaveBeenCalledWith('r1');

    stdin.write('\r');

    await expectFrame(lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(openCashRegister).toHaveBeenCalledTimes(1); // não reabre nada
  });

  test('falha na abertura vai para a tela de erro e o ENTER volta para a abertura', async () => {
    const openCashRegister = vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' },
      }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ openCashRegister })} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');
    stdin.write('\r');
    await expectFrame(lastFrame, 'Abertura de caixa');

    stdin.write('50');
    await expectFrame(lastFrame, 'Fundo de troco: R$ 0,50');
    stdin.write('\r');

    await expectFrame(lastFrame, '503 — UNAVAILABLE — servidor fora do ar');
    stdin.write('\r');

    await expectFrame(lastFrame, 'Abertura de caixa');
    expect(lastFrame()).toContain('Fundo de troco: R$ 0,00'); // o valor é digitado de novo
  });

  test('a senha digitada não aparece no frame (mascarada)', async () => {
    const { lastFrame, stdin } = render(<App api={apiStub()} />);

    await typeCredentials(lastFrame, stdin);

    expect(lastFrame()).toContain('Senha: •••••••');
    expect(lastFrame()).not.toContain('segredo');
  });

  test('a senha não aparece em nenhum frame depois das credenciais', async () => {
    const { frames, lastFrame, stdin } = render(<App api={apiStub()} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');
    stdin.write('\r');
    await expectFrame(lastFrame, 'Abertura de caixa');

    // todos os frames desde o primeiro render: a senha nunca foi escrita, nem na troca de caixa
    expect(frames.join('\n')).not.toContain('segredo');
    expect(frames.join('\n')).toContain('Senha: •••••••');
  });

  test('recusa do login vinculado ao caixa volta às credenciais com a mensagem', async () => {
    let first = true;
    const login = vi.fn(async (): Promise<LoginOutcome> => {
      if (first) {
        first = false;
        return { ok: true, operator: OPERADOR };
      }
      return { ok: false, kind: 'rejected', message: 'caixa não encontrado ou inativo' };
    });
    const { lastFrame, stdin } = render(<App api={apiStub({ login })} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, 'Escolha o caixa');
    stdin.write('\r');

    await expectFrame(lastFrame, 'caixa não encontrado ou inativo');
    expect(lastFrame()).toContain('Usuário: ana');
    expect(lastFrame()).not.toContain('••••');
  });

  test('credencial inválida mostra a mensagem e continua na tela de login', async () => {
    const login = vi.fn(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'usuário ou senha inválidos',
      }),
    );
    const listCashRegisters = vi.fn(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ login, listCashRegisters })} />);

    await signIn(lastFrame, stdin);

    await expectFrame(lastFrame, 'usuário ou senha inválidos');
    expect(lastFrame()).toContain('Usuário:');
    expect(lastFrame()).not.toContain('••••'); // a senha recusada sai do campo
    expect(listCashRegisters).not.toHaveBeenCalled();
  });

  test('conta bloqueada (423) mostra o detalhe e permanece na tela de login', async () => {
    const login = vi.fn(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'conta bloqueada até 12:30',
      }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ login })} />);

    await signIn(lastFrame, stdin);

    await expectFrame(lastFrame, 'conta bloqueada até 12:30');
    expect(lastFrame()).toContain('Usuário:');
  });

  test('falha de rede vai para a tela de erro e ENTER volta ao login', async () => {
    const login = vi.fn(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' },
      }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ login })} />);

    await signIn(lastFrame, stdin);

    await expectFrame(lastFrame, '0 — sem código — Falha de rede ao chamar a API.');
    expect(lastFrame()).toContain('ENTER/ESC para voltar');

    stdin.write('\r');

    await expectFrame(lastFrame, 'PDV minimercado — entrada do operador');
    expect(lastFrame()).toContain('Usuário:');
  });

  test('ESC também reconhece a falha e volta ao login', async () => {
    const login = vi.fn(
      async (): Promise<LoginOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' },
      }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ login })} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, '503 — UNAVAILABLE — servidor fora do ar');

    stdin.write('\u001b');

    await expectFrame(lastFrame, 'PDV minimercado — entrada do operador');
  });

  test('setas movem a seleção na lista de caixas, em ciclo', async () => {
    const CAIXA_03: CashRegisterOption = {
      id: 'r3',
      code: '03',
      name: 'Caixa do açougue',
      open: false,
      operatorName: null,
    };
    const listCashRegisters = vi.fn(
      async (): Promise<CashRegistersOutcome> => ({
        ok: true,
        registers: [CAIXA_01, CAIXA_02, CAIXA_03],
      }),
    );
    const { lastFrame, stdin } = render(<App api={apiStub({ listCashRegisters })} />);

    await signIn(lastFrame, stdin);
    await expectFrame(lastFrame, '› 01');

    stdin.write('\u001b[B'); // seta para baixo
    await expectFrame(lastFrame, '› 02');
    stdin.write('\u001b[B');
    await expectFrame(lastFrame, '› 03');
    stdin.write('\u001b[B'); // na ponta, volta para o primeiro
    await expectFrame(lastFrame, '› 01');
    stdin.write('\u001b[A'); // e sobe para o último
    await expectFrame(lastFrame, '› 03');

    stdin.write('\r');
    await expectFrame(lastFrame, 'Abertura de caixa');
  });
});

describe('App: venda e canal cru do teclado (1108)', () => {
  test('a venda abre com cabeçalho, totais zerados e a barra de atalhos', async () => {
    const ui = render(<App api={apiStub()} />);

    await reachSale(ui);

    expect(ui.lastFrame()).toContain('PDV minimercado · Caixa principal');
    expect(ui.lastFrame()).toContain('Operador: Ana Souza');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00');
    expect(ui.lastFrame()).toContain('F11 Autoteste do leitor');
  });

  test('F11 no canal cru abre o autoteste, o bipe resolve no servidor e o ESC volta para a venda', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { id: 'p2', name: 'Banana prata', price: 6.99, unit: 'KG', quantity: 0.75 },
      }),
    );
    const ui = render(<App api={apiStub({ resolveBarcode })} />);
    await reachSale(ui);

    // F11 chega como sequência crua: o `useInput` do Ink não a entrega
    ui.stdin.write('\u001b[23~');
    await expectFrame(ui.lastFrame, 'Autoteste do leitor (F11)');

    // bipe da etiqueta de balança: o código vai bruto ao servidor (BR-14)
    ui.stdin.write('2000420001234\r');
    await expectFrame(ui.lastFrame, 'produto "Banana prata"');
    expect(resolveBarcode).toHaveBeenCalledWith('2000420001234');

    ui.stdin.write('\u001b'); // ESC fecha o overlay
    await expectFrame(ui.lastFrame, 'TOTAL: R$ 0,00');
    expect(ui.lastFrame()).not.toContain('Autoteste do leitor (F11)');
  });

  test('dígitos do operador não disparam atalho nenhum na venda', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: 'notFound',
        problem: { status: 404, code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' },
      }),
    );
    const ui = render(<App api={apiStub({ resolveBarcode })} />);
    await reachSale(ui);

    ui.stdin.write('123');

    await expectFrame(ui.lastFrame, 'TOTAL: R$ 0,00');
    expect(ui.lastFrame()).not.toContain('Autoteste do leitor (F11)');
    expect(resolveBarcode).not.toHaveBeenCalled();
  });

  test('com o autoteste aberto, os outros F não atuam na venda (modal bloqueia)', async () => {
    const ui = render(<App api={apiStub()} />);
    await reachSale(ui);

    ui.stdin.write('\u001b[23~');
    await expectFrame(ui.lastFrame, 'Autoteste do leitor (F11)');

    ui.stdin.write('\u001b[21~'); // F10 (fechar caixa) com o overlay aberto: bloqueado

    await expectFrame(ui.lastFrame, 'Autoteste do leitor (F11)');
    expect(ui.lastFrame()).not.toContain('Fechamento de caixa');
  });
});

describe('App: bipe adiciona item (1109)', () => {
  test('o shell liga o bipe da venda à API: cria a venda e inclui o item', async () => {
    const createSale = vi.fn(
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
    );
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: true,
        sale: {
          id: 'sale-1',
          items: [
            { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
          ],
          subtotal: 24.9,
          discountAmount: 0,
          total: 24.9,
          paidAmount: 0,
          changeAmount: 0,
          payments: [],
          customerId: null,
        },
      }),
    );
    const ui = render(<App api={apiStub({ createSale, addSaleItem })} />);
    await reachSale(ui);

    ui.stdin.write('7891000100103\r');

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(createSale).toHaveBeenCalledTimes(1);
    expect(addSaleItem).toHaveBeenCalledWith('sale-1', { barcode: '7891000100103', quantity: 1 });
  });

  test('falha bloqueante ao incluir o item vai para a tela de erro e o ENTER volta para a venda', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: { status: 403, code: 'ACCESS_DENIED', detail: 'permissão sale.create' },
      }),
    );
    const ui = render(<App api={apiStub({ addSaleItem })} />);
    await reachSale(ui);

    ui.stdin.write('7891000100103\r');

    await expectFrame(ui.lastFrame, '403 — ACCESS_DENIED — permissão sale.create');

    ui.stdin.write('\r'); // reconhece o erro e volta para a venda

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00');
  });
});

describe('App: desconto (1111)', () => {
  /** Venda com um item: o F5 só abre o modal quando a venda já existe (1109). */
  async function reachSaleWithItem(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    return ui;
  }

  /** Abre o modal pelo F5 do canal cru. */
  async function openDiscount(ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined }) {
    ui.stdin.write('\u001b[15~'); // F5: o `useInput` do Ink não entrega as teclas F
    await expectFrame(ui.lastFrame, 'Desconto na venda (F5)');
  }

  test('F5 abre o modal sobre a venda: o corpo da venda sai de cena', async () => {
    const ui = await reachSaleWithItem(apiStub());

    await openDiscount(ui);

    expect(ui.lastFrame()).toContain('Tipo: [VALOR] · PERCENTUAL');
    expect(ui.lastFrame()).not.toContain('Subtotal:'); // a venda fica escondida enquanto o modal está à vista
  });

  test('F5 sem venda criada não abre nada: não há o que descontar', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: saleDiscounted(10) }),
    );
    const ui = render(<App api={apiStub({ applyDiscount })} />);
    await reachSale(ui);

    ui.stdin.write('\u001b[15~');

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('Desconto na venda (F5)');
    expect(applyDiscount).not.toHaveBeenCalled();
  });

  test('aplicar o valor fecha o modal e os totais são os que o servidor devolveu', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: saleDiscounted(10) }),
    );
    const ui = await reachSaleWithItem(apiStub({ applyDiscount }));

    await openDiscount(ui);

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'Desconto: R$ 10,00');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 14,90');
    expect(ui.lastFrame()).not.toContain('Desconto na venda (F5)');
    expect(applyDiscount).toHaveBeenCalledWith('sale-1', {
      type: 'VALUE',
      value: 10,
      reason: 'cliente pediu',
    });
  });

  test('ESC cancela sem chamar a API e sem mexer na venda', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: saleDiscounted(10) }),
    );
    const ui = await reachSaleWithItem(apiStub({ applyDiscount }));
    await openDiscount(ui);

    ui.stdin.write('1000'); // com o formulário já preenchido: cancelar não aplica nada
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\u001b'); // ESC pelo canal cru fecha o modal (§11.3)

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Desconto na venda (F5)');
    });
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90'); // a venda intacta
    expect(applyDiscount).not.toHaveBeenCalled();
  });

  test('com o modal aberto os demais atalhos ficam bloqueados', async () => {
    const ui = await reachSaleWithItem(apiStub());
    await openDiscount(ui);

    ui.stdin.write('\u001b[23~'); // F11 (autoteste) com o desconto aberto: bloqueado

    await expectFrame(ui.lastFrame, 'Desconto na venda (F5)');
    expect(ui.lastFrame()).not.toContain('Autoteste do leitor');
  });

  test('bipe com o modal aberto não vira item nem aplica o desconto', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: true,
        sale: {
          id: 'sale-1',
          items: [
            { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
          ],
          subtotal: 24.9,
          discountAmount: 0,
          total: 24.9,
          paidAmount: 0,
          changeAmount: 0,
          payments: [],
          customerId: null,
        },
      }),
    );
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: saleDiscounted(10) }),
    );
    const ui = await reachSaleWithItem(apiStub({ addSaleItem, applyDiscount }));
    expect(addSaleItem).toHaveBeenCalledTimes(1); // o bipe que criou a venda com o item

    await openDiscount(ui);

    ui.stdin.write('7891000100103\r'); // rajada com o modal à vista

    await expectFrame(ui.lastFrame, 'Desconto na venda (F5)');
    expect(addSaleItem).toHaveBeenCalledTimes(1); // o bipe não virou item: a venda saiu de cena
    expect(applyDiscount).not.toHaveBeenCalled(); // o terminador colado no texto não aplica nada
  });
});

describe('App: cliente na venda (1112)', () => {
  /** Clientes da busca: o CPF chega do servidor só com dígitos (502a) e nem todo cliente tem um. */
  const MARIA: CustomerOption = { id: 'c1', name: 'Maria Silva', taxId: '12345678900' };
  const ANA: CustomerOption = { id: 'c2', name: 'Ana Souza', taxId: null };

  /** Venda que o servidor devolve no vínculo: o item vem junto e o `customerId` é o dele (BR-12). */
  function saleWithCustomer(customerId: string | null): SaleView {
    return {
      id: 'sale-1',
      items: [
        { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
      ],
      subtotal: 24.9,
      discountAmount: 0,
      total: 24.9,
      paidAmount: 0,
      changeAmount: 0,
      payments: [],
      customerId,
    };
  }

  /** Venda com um item: o F6 só abre o modal quando a venda já existe (1109). */
  async function reachSaleWithItem(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    return ui;
  }

  /** Abre o modal pelo F6 do canal cru. */
  async function openCustomer(ui: {
    stdin: { write: (data: string) => void };
    lastFrame: () => string | undefined;
  }): Promise<void> {
    ui.stdin.write('\u001b[17~'); // F6: o `useInput` do Ink não entrega as teclas F
    await expectFrame(ui.lastFrame, 'Cliente na venda (F6)');
  }

  /** Digita o termo no campo de busca esperando o frame; o ENTER que busca é do teste. */
  async function typeTerm(
    ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
    term: string,
  ): Promise<void> {
    ui.stdin.write(term);
    await expectFrame(ui.lastFrame, `Busca: ${term}`);
  }

  test('F6 abre o modal sobre a venda: o corpo da venda sai de cena', async () => {
    const ui = await reachSaleWithItem(apiStub());

    await openCustomer(ui);

    expect(ui.lastFrame()).toContain('digite o nome ou o CPF e ENTER busca');
    expect(ui.lastFrame()).not.toContain('Subtotal:'); // a venda fica escondida com o modal à vista
  });

  test('F6 sem venda criada não abre nada: não há onde vincular cliente antes do primeiro bipe', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA] }),
    );
    const ui = render(<App api={apiStub({ searchCustomers })} />);
    await reachSale(ui);

    ui.stdin.write('\u001b[17~');

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('Cliente na venda (F6)');
    expect(searchCustomers).not.toHaveBeenCalled();
  });

  test('a busca lista os clientes e o ENTER vincula: o nome aparece no cabeçalho da venda', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA, ANA] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWithCustomer(MARIA.id) }),
    );
    const ui = await reachSaleWithItem(apiStub({ searchCustomers, linkCustomer }));
    await openCustomer(ui);

    await typeTerm(ui, 'maria');
    ui.stdin.write('\r'); // busca no servidor

    await expectFrame(ui.lastFrame, '› Maria Silva — 123.456.789-00');
    expect(ui.lastFrame()).toContain('Ana Souza');
    expect(searchCustomers).toHaveBeenCalledWith('maria');

    ui.stdin.write('\r'); // vincula o selecionado

    await expectFrame(ui.lastFrame, 'Cliente: Maria Silva');
    expect(linkCustomer).toHaveBeenCalledWith('sale-1', 'c1');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90'); // a venda voltou à cena
    expect(ui.lastFrame()).not.toContain('Cliente na venda (F6)');
  });

  test('com cliente vinculado o F6 mostra o atual e o DEL remove: o cabeçalho limpa', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWithCustomer(MARIA.id) }),
    );
    const unlinkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWithCustomer(null) }),
    );
    const ui = await reachSaleWithItem(apiStub({ searchCustomers, linkCustomer, unlinkCustomer }));

    // primeiro o vínculo (é como o PDV chega no F6 com cliente)
    await openCustomer(ui);
    await typeTerm(ui, 'maria');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, '› Maria Silva — 123.456.789-00');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Cliente: Maria Silva');

    // F6 de novo: o modal mostra o atual e o DEL remove
    await openCustomer(ui);
    expect(ui.lastFrame()).toContain('Cliente atual: Maria Silva');

    ui.stdin.write('\x1b[3~'); // DEL remove

    // o modal sai de cena com a venda que o servidor devolveu (sem o cliente)
    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Cliente na venda (F6)');
    });
    expect(unlinkCustomer).toHaveBeenCalledWith('sale-1');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90'); // a venda segue, anônima de novo
    expect(ui.lastFrame()).not.toContain('Cliente: Maria Silva'); // o cabeçalho limpou
  });

  test('422 CUSTOMER_INACTIVE mostra a mensagem no modal e não vincula', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'cliente desativado no cadastro — escolha outro',
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ searchCustomers, linkCustomer }));
    await openCustomer(ui);

    await typeTerm(ui, 'maria');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, '› Maria Silva — 123.456.789-00');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'cliente desativado no cadastro — escolha outro');
    expect(ui.lastFrame()).toContain('Cliente na venda (F6)'); // o modal segue aberto
    expect(ui.lastFrame()).not.toContain('Cliente: Maria Silva');
  });

  test('ESC fecha o modal sem chamar a API e sem mexer na venda', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWithCustomer(MARIA.id) }),
    );
    const ui = await reachSaleWithItem(apiStub({ searchCustomers, linkCustomer }));
    await openCustomer(ui);
    await typeTerm(ui, 'maria');

    ui.stdin.write('\u001b'); // ESC pelo canal cru fecha o modal (§11.3)

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Cliente na venda (F6)');
    });
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90'); // a venda intacta
    expect(ui.lastFrame()).not.toContain('Cliente:');
    expect(searchCustomers).not.toHaveBeenCalled();
    expect(linkCustomer).not.toHaveBeenCalled();
  });

  test('bipe com o modal aberto não vira item: a rajada entra no campo de busca', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleWithCustomer(null) }),
    );
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [] }),
    );
    const ui = await reachSaleWithItem(apiStub({ addSaleItem, searchCustomers }));
    expect(addSaleItem).toHaveBeenCalledTimes(1); // o bipe que criou a venda com o item

    await openCustomer(ui);

    ui.stdin.write('7891000100103\r'); // rajada com o modal à vista
    await expectFrame(ui.lastFrame, 'Busca: 7891000100103');

    ui.stdin.write('\r'); // o terminador vira busca do código, nunca vínculo nem item

    await vi.waitFor(() => {
      expect(searchCustomers).toHaveBeenCalledWith('7891000100103');
    });
    expect(addSaleItem).toHaveBeenCalledTimes(1); // o bipe não virou item: a venda saiu de cena
    expect(ui.lastFrame()).toContain('Cliente na venda (F6)');
  });

  test('com o modal aberto os demais atalhos ficam bloqueados', async () => {
    const ui = await reachSaleWithItem(apiStub());
    await openCustomer(ui);

    ui.stdin.write('\u001b[15~'); // F5 (desconto) com o cliente aberto: bloqueado

    await expectFrame(ui.lastFrame, 'Cliente na venda (F6)');
    expect(ui.lastFrame()).not.toContain('Desconto na venda (F5)');
  });
});

describe('App: pagamento e conclusão (1113)', () => {
  /** Venda com o item do bipe e o pagamento como o servidor o devolveu (BR-05/BR-12). */
  function salePaid(paidAmount: number, changeAmount: number, payments: PaymentView[]): SaleView {
    return {
      id: 'sale-1',
      items: [
        { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
      ],
      subtotal: 24.9,
      discountAmount: 0,
      total: 24.9,
      paidAmount,
      changeAmount,
      payments,
      customerId: null,
    };
  }

  function payment(
    id: string,
    method: PaymentMethod,
    amount: number,
    changeAmount = 0,
  ): PaymentView {
    return { id, method, amount, changeAmount, status: 'APPROVED' };
  }

  /** Venda com um item e o pagamento aberto pelo F9 do canal cru: o ponto de partida dos testes. */
  async function reachPayment(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    ui.stdin.write('\u001b[20~'); // F9: o `useInput` do Ink não entrega as teclas F
    await expectFrame(ui.lastFrame, 'Pagamento (F9)');

    return ui;
  }

  test('F9 sem venda criada não abre o pagamento: venda vazia não tem o que pagar', async () => {
    const addPayment = vi.fn();
    const ui = render(<App api={apiStub({ addPayment })} />);
    await reachSale(ui);

    ui.stdin.write('\u001b[20~');

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('Pagamento (F9)');
    expect(addPayment).not.toHaveBeenCalled();
  });

  test('F9 abre o pagamento com as cinco formas e o pago/total do servidor', async () => {
    const ui = await reachPayment(apiStub());

    expect(ui.lastFrame()).toContain('Método: [DINHEIRO] · PIX · DÉBITO · CRÉDITO · VOUCHER');
    expect(ui.lastFrame()).toContain('Valor: R$ 0,00');
    expect(ui.lastFrame()).toContain('Recebido: R$ 0,00');
    expect(ui.lastFrame()).toContain('nenhum pagamento registrado');
    expect(ui.lastFrame()).toContain('Pago: R$ 0,00 de R$ 24,90');
    expect(ui.lastFrame()).not.toContain('Subtotal:'); // a venda sai de cena com o pagamento à vista
  });

  test('a máscara é em centavos e o recebido só existe no dinheiro', async () => {
    const ui = await reachPayment(apiStub());

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');

    ui.stdin.write('\u001b[C'); // seta para a direita troca a forma
    await expectFrame(ui.lastFrame, '[PIX]');

    expect(ui.lastFrame()).toContain('Valor: R$ 10,00');
    expect(ui.lastFrame()).not.toContain('Recebido:');
  });

  test('dinheiro com o valor recebido mostra o troco do servidor e o pagamento registrado', async () => {
    const addPayment = vi.fn(
      async (): Promise<AddPaymentOutcome> => ({
        ok: true,
        sale: salePaid(24.9, 25.1, [payment('pay-1', 'CASH', 24.9, 25.1)]),
      }),
    );
    const ui = await reachPayment(apiStub({ addPayment }));

    ui.stdin.write('2490');
    await expectFrame(ui.lastFrame, 'Valor: R$ 24,90');
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Recebido:');
    ui.stdin.write('5000');
    await expectFrame(ui.lastFrame, 'Recebido: R$ 50,00');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, '1. DINHEIRO — R$ 24,90 · troco R$ 25,10');
    expect(ui.lastFrame()).toContain('Pago: R$ 24,90 de R$ 24,90');
    expect(ui.lastFrame()).toContain('TROCO: R$ 25,10'); // troco do servidor, em destaque
    expect(addPayment).toHaveBeenCalledWith(
      'sale-1',
      { method: 'CASH', amount: 24.9, tenderedAmount: 50 },
      expect.any(String),
    );
  });

  test('dois pagamentos acumulam o pago e a lista: a tela continua no pagamento', async () => {
    let registered = 0;
    const addPayment = vi.fn(async (): Promise<AddPaymentOutcome> => {
      registered += 1;

      return registered === 1
        ? { ok: true, sale: salePaid(10, 0, [payment('pay-1', 'CASH', 10)]) }
        : {
            ok: true,
            sale: salePaid(15, 0, [payment('pay-1', 'CASH', 10), payment('pay-2', 'PIX', 5)]),
          };
    });
    const ui = await reachPayment(apiStub({ addPayment }));

    // primeiro pagamento em dinheiro: 10,00 com 10,00 recebidos (sem troco)
    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Recebido:');
    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Recebido: R$ 10,00');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Pago: R$ 10,00 de R$ 24,90');

    // segundo em PIX: a máscara e o campo voltam limpos para o próximo
    ui.stdin.write('\u001b[C');
    await expectFrame(ui.lastFrame, '[PIX]');
    ui.stdin.write('500');
    await expectFrame(ui.lastFrame, 'Valor: R$ 5,00');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, '2. PIX — R$ 5,00');
    expect(ui.lastFrame()).toContain('1. DINHEIRO — R$ 10,00');
    expect(ui.lastFrame()).toContain('Pago: R$ 15,00 de R$ 24,90');
    expect(ui.lastFrame()).toContain('Pagamento (F9)'); // a venda só fecha no F9
    expect(addPayment).toHaveBeenCalledTimes(2);
  });

  test('F9 conclui e o ENTER inicia a próxima venda com o resumo do servidor', async () => {
    const completeSale = vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({
        ok: true,
        receipt: { number: 42, total: 24.9, changeAmount: 25.1 },
      }),
    );
    const ui = await reachPayment(apiStub({ completeSale }));

    ui.stdin.write('\u001b[20~');

    await expectFrame(ui.lastFrame, 'Venda 42 concluída');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
    expect(ui.lastFrame()).toContain('TROCO: R$ 25,10');
    expect(ui.lastFrame()).toContain('ENTER inicia a próxima venda');
    expect(completeSale).toHaveBeenCalledWith('sale-1', expect.any(String));

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('Venda 42 concluída');
    expect(ui.lastFrame()).not.toContain('TROCO:');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00');
  });

  test('falha transitória ao concluir não avança nem cria venda nova, e o F9 reusa a chave', async () => {
    const createSale = vi.fn(
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
    );
    let attempt = 0;
    // tipado pelo contrato: o teste confere a chave das duas tentativas em `mock.calls`
    const completeSale = vi.fn<TerminalApi['completeSale']>(async () => {
      attempt += 1;

      return attempt === 1
        ? {
            ok: false,
            kind: 'retryable',
            problem: { status: 0, code: null, detail: 'fetch failed' },
          }
        : { ok: true, receipt: { number: 42, total: 24.9, changeAmount: 0 } };
    });
    const ui = await reachPayment(apiStub({ createSale, completeSale }));

    ui.stdin.write('\u001b[20~');

    await expectFrame(ui.lastFrame, 'falha ao concluir — F9 tenta de novo');
    expect(ui.lastFrame()).toContain('Pagamento (F9)'); // a venda não avançou
    expect(ui.lastFrame()).not.toContain('Venda 42 concluída');

    ui.stdin.write('\u001b[20~');

    await expectFrame(ui.lastFrame, 'Venda 42 concluída');
    expect(completeSale).toHaveBeenCalledTimes(2);
    expect(completeSale.mock.calls[0]?.[1]).toBe(completeSale.mock.calls[1]?.[1]);
    // o retry é da mesma venda: nada de criar outra (a chave é o que evita a baixa dupla no servidor)
    expect(createSale).toHaveBeenCalledTimes(1);
  });

  test('422 PAYMENT_INSUFFICIENT mostra a mensagem e continua no pagamento', async () => {
    const completeSale = vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'pagamento insuficiente — registre o valor que falta',
      }),
    );
    const ui = await reachPayment(apiStub({ completeSale }));

    ui.stdin.write('\u001b[20~');

    await expectFrame(ui.lastFrame, 'pagamento insuficiente — registre o valor que falta');
    expect(ui.lastFrame()).toContain('Pagamento (F9)');
    expect(ui.lastFrame()).toContain('Pago: R$ 0,00 de R$ 24,90');
    expect(ui.lastFrame()).not.toContain('Venda 42 concluída');
  });

  test('ESC no pagamento volta para a venda com os itens preservados', async () => {
    const ui = await reachPayment(apiStub());

    ui.stdin.write('\u001b'); // ESC pelo canal do `useInput`, como nas demais telas

    await expectFrame(ui.lastFrame, 'TOTAL: R$ 24,90');
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).not.toContain('Pagamento (F9)');
  });
});

describe('App: sangria e suprimento (1114)', () => {
  /** F7/F8 no canal cru: o `useInput` do Ink não entrega as teclas F. */
  const F7 = '\u001b[18~';
  const F8 = '\u001b[19~';

  /** Movimento como o servidor o devolveu: esperado antes/depois da mesma transação (BR-12). */
  function movement(
    amount: number,
    before: number,
    after: number,
    aboveExpected = false,
  ): CashMovementView {
    return {
      sessionId: 'session-1',
      type: 'WITHDRAWAL',
      amount,
      reason: 'troco para o banco',
      expectedBefore: before,
      expectedAfter: after,
      aboveExpected,
    };
  }

  /** Digita valor e motivo: o operador ainda está no formulário (nada foi à API). */
  async function fillForm(
    ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
  ): Promise<void> {
    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Motivo:');
    ui.stdin.write('troco para o banco');
    await expectFrame(ui.lastFrame, 'Motivo: troco para o banco');
  }

  /** Preenche e leva até a confirmação: o ENTER que chama a API é o de baixo. */
  async function sendMovement(
    ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
    label: string,
  ): Promise<void> {
    await fillForm(ui);
    ui.stdin.write('\r'); // formulário: só confirma
    await expectFrame(ui.lastFrame, `confirmar ${label} de R$ 10,00?`);
    ui.stdin.write('\r'); // confirmação: esta chama a API
  }

  test('F7 abre a sangria e F8 o suprimento sobre a venda: o corpo da venda sai de cena', async () => {
    const ui = render(<App api={apiStub()} />);
    await reachSale(ui);

    ui.stdin.write(F7);

    await expectFrame(ui.lastFrame, 'Sangria (F7)');
    expect(ui.lastFrame()).not.toContain('Subtotal:'); // a venda fica escondida com o modal à vista

    ui.stdin.write('\u001b'); // ESC pelo canal cru fecha o modal (§11.3)
    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Sangria (F7)');
    });

    ui.stdin.write(F8);

    await expectFrame(ui.lastFrame, 'Suprimento (F8)');
  });

  test('F7 antes do primeiro bipe abre: a sangria é do caixa, não da venda', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: true, movement: movement(10, 150, 140) }),
    );
    const ui = render(<App api={apiStub({ withdrawCash })} />);
    await reachSale(ui); // sem venda criada: o F5/F6 não abririam nada, o F7 abre

    ui.stdin.write(F7);

    await expectFrame(ui.lastFrame, 'Sangria (F7)');
    expect(ui.lastFrame()).toContain('› Valor: R$ 0,00');
  });

  test('ESC no formulário e na confirmação não chama a API', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: true, movement: movement(10, 150, 140) }),
    );
    const ui = render(<App api={apiStub({ withdrawCash })} />);
    await reachSale(ui);

    // ESC no formulário, com o valor já digitado
    ui.stdin.write(F7);
    await expectFrame(ui.lastFrame, 'Sangria (F7)');
    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\u001b');

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Sangria (F7)');
    });
    expect(withdrawCash).not.toHaveBeenCalled();

    // ESC na confirmação: o pedido estava pronto, mas o ENTER que chama a API não veio
    ui.stdin.write(F7);
    await expectFrame(ui.lastFrame, 'Sangria (F7)');
    await fillForm(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\u001b');

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Sangria (F7)');
    });
    expect(withdrawCash).not.toHaveBeenCalled();
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00'); // a venda intacta
  });

  test('a sangria confirma depois do formulário e mostra o esperado do servidor', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: true, movement: movement(10, 150, 140) }),
    );
    const ui = render(<App api={apiStub({ withdrawCash })} />);
    await reachSale(ui);
    ui.stdin.write(F7);
    await expectFrame(ui.lastFrame, 'Sangria (F7)');

    await sendMovement(ui, 'sangria');

    await expectFrame(ui.lastFrame, 'sangria registrada: R$ 10,00');
    expect(ui.lastFrame()).toContain('esperado antes: R$ 150,00');
    expect(ui.lastFrame()).toContain('esperado agora: R$ 140,00');
    expect(withdrawCash).toHaveBeenCalledWith(
      'r1', // o `{id}` da rota é o caixa da sessão, não a sessão (passo 609)
      { amount: 10, reason: 'troco para o banco' },
      expect.any(String),
    );

    ui.stdin.write('\r'); // sucesso: o ENTER fecha o modal

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('Sangria (F7)');
  });

  test('o suprimento mostra o esperado atualizado do servidor', async () => {
    const supplyCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: true,
        movement: { ...movement(50, 150, 200), type: 'SUPPLY' },
      }),
    );
    const ui = render(<App api={apiStub({ supplyCash })} />);
    await reachSale(ui);
    ui.stdin.write(F8);
    await expectFrame(ui.lastFrame, 'Suprimento (F8)');

    await sendMovement(ui, 'suprimento');

    await expectFrame(ui.lastFrame, 'suprimento registrado: R$ 50,00');
    expect(ui.lastFrame()).toContain('esperado agora: R$ 200,00');
    expect(supplyCash).toHaveBeenCalledWith(
      'r1',
      { amount: 10, reason: 'troco para o banco' },
      expect.any(String),
    );
  });

  test('403 do OPERADOR mostra a mensagem no modal e o ESC fecha sem mexer na venda', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'sem permissão para registrar sangria',
      }),
    );
    const ui = render(<App api={apiStub({ withdrawCash })} />);
    await reachSale(ui);
    ui.stdin.write(F7);
    await expectFrame(ui.lastFrame, 'Sangria (F7)');

    await sendMovement(ui, 'sangria');

    await expectFrame(ui.lastFrame, 'sem permissão para registrar sangria');
    expect(ui.lastFrame()).toContain('Sangria (F7)'); // o modal segue aberto no formulário

    ui.stdin.write('\u001b');

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Sangria (F7)');
    });
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00'); // a venda intacta
    expect(withdrawCash).toHaveBeenCalledTimes(1);
  });

  test('bipe com o modal aberto não vira item: a rajada entra no valor da gaveta', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: true,
        sale: {
          id: 'sale-1',
          items: [
            { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
          ],
          subtotal: 24.9,
          discountAmount: 0,
          total: 24.9,
          paidAmount: 0,
          changeAmount: 0,
          payments: [],
          customerId: null,
        },
      }),
    );
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: true, movement: movement(10, 150, 140) }),
    );
    const ui = render(<App api={apiStub({ addSaleItem, withdrawCash })} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r'); // o bipe que cria a venda com o item
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(addSaleItem).toHaveBeenCalledTimes(1);

    ui.stdin.write(F7);
    await expectFrame(ui.lastFrame, 'Sangria (F7)');

    ui.stdin.write('123456\r'); // rajada com o modal à vista

    await expectFrame(ui.lastFrame, 'Valor: R$ 1234,56'); // os dígitos caem no campo, como texto
    expect(ui.lastFrame()).toContain('Sangria (F7)');
    expect(addSaleItem).toHaveBeenCalledTimes(1); // o bipe não virou item: a venda saiu de cena
    expect(withdrawCash).not.toHaveBeenCalled(); // o terminador colado não confirma nada
  });

  test('com o modal aberto os demais atalhos ficam bloqueados', async () => {
    const ui = render(<App api={apiStub()} />);
    await reachSale(ui);
    ui.stdin.write(F7);
    await expectFrame(ui.lastFrame, 'Sangria (F7)');

    ui.stdin.write('\u001b[15~'); // F5 (desconto) com a gaveta aberta: bloqueado

    await expectFrame(ui.lastFrame, 'Sangria (F7)');
    expect(ui.lastFrame()).not.toContain('Desconto na venda (F5)');
  });
});

describe('App: fechamento de caixa e cancelamentos (1115)', () => {
  /** F3/F4/F10 no canal cru: o `useInput` do Ink não entrega as teclas F (1105). */
  const F3 = '\u001b[13~';
  const F4 = '\u001b[14~';
  const F10 = '\u001b[21~';

  /** Cliente da busca (502): o CPF chega só com dígitos e a máscara é da apresentação. */
  const MARIA: CustomerOption = { id: 'c1', name: 'Maria Silva', taxId: '12345678900' };

  /** Resumo que o servidor devolve no `summary`: esperado e quebras são dele (BR-12). */
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

  /** Venda que o servidor devolve no vínculo do F6: o item vem junto e o `customerId` é o dele. */
  function saleWithCustomer(customerId: string | null): SaleView {
    return {
      id: 'sale-1',
      items: [
        { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
      ],
      subtotal: 24.9,
      discountAmount: 0,
      total: 24.9,
      paidAmount: 0,
      changeAmount: 0,
      payments: [],
      customerId,
    };
  }

  /** Venda com um item: o F6, o F4 e o F3 só atuam quando a venda já existe (1109). */
  async function reachSaleWithItem(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    return ui;
  }

  /** Digita o valor contado e o ENTER do formulário: a confirmação fica à vista, API ainda não. */
  async function confirmClose(
    ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
  ): Promise<void> {
    ui.stdin.write('3000');
    await expectFrame(ui.lastFrame, 'Valor contado: R$ 30,00');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'fechar o caixa com R$ 30,00?');
  }

  test('F10 abre o fechamento e busca o resumo da sessão no servidor', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    );
    const ui = render(<App api={apiStub({ cashSessionSummary })} />);
    await reachSale(ui);

    ui.stdin.write(F10);

    await expectFrame(ui.lastFrame, 'Fechamento de caixa (F10)');
    expect(cashSessionSummary).toHaveBeenCalledWith('session-1');
    expect(ui.lastFrame()).toContain('Aberto: R$ 10,00');
    expect(ui.lastFrame()).toContain('Esperado: R$ 44,90');
    expect(ui.lastFrame()).toContain('DINHEIRO: R$ 34,90');
    expect(ui.lastFrame()).toContain('Sangrias: R$ 3,00 · Suprimentos: R$ 3,00');
    expect(ui.lastFrame()).toContain('Valor contado: R$ 0,00');
  });

  test('fechar mostra a diferença do servidor e o ENTER faz logout e volta ao login', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    );
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    );
    const logout = vi.fn(async () => undefined);
    const ui = render(<App api={apiStub({ cashSessionSummary, closeCashSession, logout })} />);
    await reachSale(ui);
    ui.stdin.write(F10);
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');

    await confirmClose(ui);

    expect(closeCashSession).not.toHaveBeenCalled(); // o ENTER do formulário só confirmou

    ui.stdin.write('\r'); // confirmação: esta chama a API

    await expectFrame(ui.lastFrame, 'Diferença (servidor): R$ -14,90');
    expect(ui.lastFrame()).toContain('falta dinheiro na gaveta');
    expect(closeCashSession).toHaveBeenCalledWith('r1', { countedAmount: 30 }, expect.any(String));

    // o login em dois passos (1107) já revogou a sessão provisória: daqui em diante só o fechamento conta
    logout.mockClear();
    ui.stdin.write('\r'); // ENTER com o caixa fechado: revoga a sessão

    await expectFrame(ui.lastFrame, 'Usuário:');
    expect(logout).toHaveBeenCalledTimes(1);
  });

  test('com o caixa fechado, outra tecla volta ao login sem revogar a sessão', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    );
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    );
    const logout = vi.fn(async () => undefined);
    const ui = render(<App api={apiStub({ cashSessionSummary, closeCashSession, logout })} />);
    await reachSale(ui);
    ui.stdin.write(F10);
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await confirmClose(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Diferença (servidor): R$ -14,90');

    logout.mockClear();
    ui.stdin.write('x'); // outra tecla: volta sem logout

    await expectFrame(ui.lastFrame, 'Usuário:');
    expect(logout).not.toHaveBeenCalled();
  });

  test('409 SESSION_HAS_OPEN_SALES mostra a mensagem, não fecha e o ESC volta para a venda', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    );
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'há venda em andamento — cancele a venda (F4) antes de fechar',
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ cashSessionSummary, closeCashSession }));
    ui.stdin.write(F10);
    await expectFrame(ui.lastFrame, 'Esperado: R$ 44,90');
    await confirmClose(ui);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'há venda em andamento — cancele a venda (F4) antes de fechar');
    expect(ui.lastFrame()).not.toContain('Diferença (servidor)'); // o caixa não fechou
    expect(ui.lastFrame()).toContain('Valor contado: R$ 30,00'); // segue na tela, com o valor

    ui.stdin.write('\u001b'); // ESC volta para a venda preservada (reducer)

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).not.toContain('Fechamento de caixa (F10)');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90'); // a venda intacta
  });

  test('ESC no fechamento volta para a venda sem fechar nada', async () => {
    const cashSessionSummary = vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: true, summary: summary() }),
    );
    const closeCashSession = vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: true,
        closing: { countedAmount: 30, expectedAmount: 44.9, differenceAmount: -14.9 },
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ cashSessionSummary, closeCashSession }));
    ui.stdin.write(F10);
    await expectFrame(ui.lastFrame, 'Fechamento de caixa (F10)');

    ui.stdin.write('\u001b');

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
    expect(ui.lastFrame()).not.toContain('Fechamento de caixa (F10)');
    expect(closeCashSession).not.toHaveBeenCalled();
  });

  test('F3 faz o mesmo que o DEL: abre a confirmação e o ENTER remove o item', async () => {
    const removeSaleItem = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({
        ok: true,
        sale: { ...saleWithCustomer(null), items: [] },
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ removeSaleItem }));

    ui.stdin.write(F3);

    await expectFrame(ui.lastFrame, 'remover Arroz 5kg? ENTER confirma · ESC cancela');
    expect(removeSaleItem).not.toHaveBeenCalled(); // nada vai à API antes do ENTER

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'removido: Arroz 5kg');
    expect(removeSaleItem).toHaveBeenCalledWith('sale-1', 'p1');
    expect(ui.lastFrame()).toContain('bipar o primeiro item para iniciar a venda');
  });

  test('F3 sem item selecionado não abre nada (a venda vazia não tem o que cancelar)', async () => {
    const removeSaleItem = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    );
    const ui = render(<App api={apiStub({ removeSaleItem })} />);
    await reachSale(ui);

    ui.stdin.write(F3);

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('ENTER confirma · ESC cancela');
    expect(removeSaleItem).not.toHaveBeenCalled();
  });

  test('F4 sem venda criada não abre: não há o que cancelar', async () => {
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    const ui = render(<App api={apiStub({ cancelSale })} />);
    await reachSale(ui);

    ui.stdin.write(F4);

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).not.toContain('Cancelar a venda (F4)');
    expect(cancelSale).not.toHaveBeenCalled();
  });

  test('F4 exige motivo: sem texto o modal avisa e nada vai à API', async () => {
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    const ui = await reachSaleWithItem(apiStub({ cancelSale }));

    ui.stdin.write(F4);

    await expectFrame(ui.lastFrame, 'Cancelar a venda (F4)');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o motivo do cancelamento');
    expect(ui.lastFrame()).toContain('Cancelar a venda (F4)'); // o modal segue aberto
    expect(cancelSale).not.toHaveBeenCalled();
  });

  test('F4 cancela a venda: volta à venda vazia e limpa o cliente do cabeçalho', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWithCustomer(MARIA.id) }),
    );
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    const ui = await reachSaleWithItem(apiStub({ searchCustomers, linkCustomer, cancelSale }));

    // primeiro o vínculo do cliente, para o cabeçalho ter o nome a limpar
    ui.stdin.write('\u001b[17~'); // F6
    await expectFrame(ui.lastFrame, 'Cliente na venda (F6)');
    ui.stdin.write('maria');
    await expectFrame(ui.lastFrame, 'Busca: maria');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, '› Maria Silva — 123.456.789-00');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Cliente: Maria Silva');

    ui.stdin.write(F4);
    await expectFrame(ui.lastFrame, 'Cancelar a venda (F4)');
    ui.stdin.write('cliente desistiu');
    await expectFrame(ui.lastFrame, '› Motivo: cliente desistiu');
    ui.stdin.write('\r'); // formulário: só confirma
    await expectFrame(ui.lastFrame, 'cancelar a venda em andamento?');
    expect(cancelSale).not.toHaveBeenCalled(); // nada vai à API antes do ENTER da confirmação

    ui.stdin.write('\r'); // confirmação: esta cancela a venda

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(cancelSale).toHaveBeenCalledWith('sale-1', 'cliente desistiu', expect.any(String));
    expect(ui.lastFrame()).not.toContain('Cliente: Maria Silva');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00');
    expect(ui.lastFrame()).not.toContain('Cancelar a venda (F4)');
  });

  test('ESC no modal do F4 fecha sem cancelar a venda', async () => {
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    const ui = await reachSaleWithItem(apiStub({ cancelSale }));

    ui.stdin.write(F4);
    await expectFrame(ui.lastFrame, 'Cancelar a venda (F4)');
    ui.stdin.write('cliente desistiu');
    await expectFrame(ui.lastFrame, '› Motivo: cliente desistiu');

    ui.stdin.write('\u001b');

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Cancelar a venda (F4)');
    });
    expect(cancelSale).not.toHaveBeenCalled();
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90'); // a venda intacta
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
  });
});

describe('App: consulta de preço e ajuda (1116)', () => {
  /** F1/F2 no canal cru: o `useInput` do Ink não entrega as teclas F (1105). */
  const F1 = '\u001bOP';
  const F2 = '\u001bOQ';

  /** Produto que a busca por nome devolve: o preço é do servidor (BR-12). */
  const ARROZ: ProductOption = { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN' };
  const FEIJAO: ProductOption = { id: 'p2', name: 'Feijão 1kg', price: 8.5, unit: 'UN' };

  /** Saldo que o `GET /stock/{productId}` devolve: estoque baixo é sinal do servidor (704). */
  const SALDO: StockBalanceView = { quantity: 3, minQuantity: 5, lowStock: true };

  /** Venda com um item na tela: o bipe que a criou é o único `addSaleItem` esperado. */
  async function reachSaleWithItem(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    return ui;
  }

  /** Digita o termo na consulta esperando o frame; o ENTER que consulta é do teste. */
  async function typeTerm(
    ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
    term: string,
  ): Promise<void> {
    ui.stdin.write(term);
    await expectFrame(ui.lastFrame, `Busca: ${term}`);
  }

  test('F2 abre a consulta sobre a venda: o corpo da venda sai de cena', async () => {
    const ui = render(<App api={apiStub()} />);
    await reachSale(ui);

    ui.stdin.write(F2);

    await expectFrame(ui.lastFrame, 'Consulta de preço (F2)');
    expect(ui.lastFrame()).toContain('digite o código de barras ou o nome e ENTER consulta');
    expect(ui.lastFrame()).not.toContain('Subtotal:'); // a venda fica escondida com o overlay à vista
  });

  test('F2 com código mostra nome, preço e saldo sem criar venda nenhuma', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN', quantity: null },
      }),
    );
    const productStock = vi.fn(
      async (): Promise<ProductStockOutcome> => ({ ok: true, stock: SALDO }),
    );
    const api = apiStub({ resolveBarcode, productStock });
    // a consulta roda **antes** do primeiro bipe: nenhuma venda existe ainda
    const ui = render(<App api={api} />);
    await reachSale(ui);

    ui.stdin.write(F2);
    await expectFrame(ui.lastFrame, 'Consulta de preço (F2)');
    await typeTerm(ui, '7891000100103');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'Produto: Arroz 5kg');
    expect(ui.lastFrame()).toContain('Preço: R$ 24,90 · UN');
    expect(ui.lastFrame()).toContain('Saldo: 3 · mínimo 5 · ESTOQUE BAIXO');
    expect(resolveBarcode).toHaveBeenCalledWith('7891000100103');
    expect(productStock).toHaveBeenCalledWith('p1');
    // consulta não é venda: nenhuma chamada de venda saiu do F2
    expect(api.createSale).not.toHaveBeenCalled();
    expect(api.addSaleItem).not.toHaveBeenCalled();
    expect(api.changeSaleItemQuantity).not.toHaveBeenCalled();
    expect(api.removeSaleItem).not.toHaveBeenCalled();
    expect(api.applyDiscount).not.toHaveBeenCalled();
    expect(api.cancelSale).not.toHaveBeenCalled();

    ui.stdin.write('\u001b'); // ESC fecha e volta para a venda vazia, como estava

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Consulta de preço (F2)');
    });
    expect(ui.lastFrame()).toContain('bipar o primeiro item para iniciar a venda');
  });

  test('F2 por nome lista os resultados e o ENTER no selecionado mostra preço e estoque', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: 'notFound',
        problem: { status: 404, code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' },
      }),
    );
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [ARROZ, FEIJAO] }),
    );
    const productStock = vi.fn(
      async (): Promise<ProductStockOutcome> => ({ ok: true, stock: SALDO }),
    );
    const ui = render(<App api={apiStub({ resolveBarcode, searchProducts, productStock })} />);
    await reachSale(ui);

    ui.stdin.write(F2);
    await expectFrame(ui.lastFrame, 'Consulta de preço (F2)');
    await typeTerm(ui, 'arroz');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, '› Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('Feijão 1kg — R$ 8,50');
    expect(searchProducts).toHaveBeenCalledWith('arroz');
    expect(productStock).not.toHaveBeenCalled(); // a lista ainda não consultou saldo

    ui.stdin.write('\x1b[B'); // ↓ escolhe o segundo
    await expectFrame(ui.lastFrame, '› Feijão 1kg');
    ui.stdin.write('\r'); // consulta o saldo do selecionado

    await expectFrame(ui.lastFrame, 'Produto: Feijão 1kg');
    expect(ui.lastFrame()).toContain('Preço: R$ 8,50 · UN');
    expect(ui.lastFrame()).toContain('Saldo: 3 · mínimo 5');
    expect(productStock).toHaveBeenCalledWith('p2');
  });

  test('F2 não encontrado avisa e a venda continua intacta', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: 'notFound',
        problem: { status: 404, code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' },
      }),
    );
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [] }),
    );
    const ui = await reachSaleWithItem(apiStub({ resolveBarcode, searchProducts }));

    ui.stdin.write(F2);
    await expectFrame(ui.lastFrame, 'Consulta de preço (F2)');
    await typeTerm(ui, 'zzz');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'nenhum produto encontrado');

    ui.stdin.write('\u001b'); // ESC fecha e volta para a venda

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Consulta de preço (F2)');
    });
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
  });

  test('F2 não abre com a tela de sucesso à vista: o ENTER é dela', async () => {
    const completeSale = vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({
        ok: true,
        receipt: { number: 42, total: 24.9, changeAmount: 0 },
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ completeSale }));

    ui.stdin.write('\u001b[20~'); // F9 abre o pagamento
    await expectFrame(ui.lastFrame, 'Pagamento (F9)');
    ui.stdin.write('\u001b[20~'); // F9 conclui
    await expectFrame(ui.lastFrame, 'Venda 42 concluída');

    ui.stdin.write(F2);

    await expectFrame(ui.lastFrame, 'Venda 42 concluída'); // a consulta não abriu
    expect(ui.lastFrame()).not.toContain('Consulta de preço (F2)');
  });

  test('F1 abre a ajuda com todos os atalhos e o ESC volta para a venda', async () => {
    const ui = await reachSaleWithItem(apiStub());

    ui.stdin.write(F1);

    await expectFrame(ui.lastFrame, 'Ajuda — atalhos da venda (F1)');
    expect(ui.lastFrame()).toContain('F1 — esta ajuda');
    expect(ui.lastFrame()).toContain('F2 — consulta de preço e estoque, sem vender');
    expect(ui.lastFrame()).toContain('F12 — troca o operador do caixa');
    expect(ui.lastFrame()).toContain('ENTER — confirma o bipe');
    expect(ui.lastFrame()).toContain('ESC — fecha o modal e volta para a venda');
    expect(ui.lastFrame()).toContain('↑ ↓ — navega nos itens');
    expect(ui.lastFrame()).toContain('+ - — altera a quantidade');
    expect(ui.lastFrame()).toContain('DEL — remove o item selecionado');
    expect(ui.lastFrame()).not.toContain('Subtotal:'); // a venda fica escondida com o overlay à vista

    ui.stdin.write('\u001b'); // ESC pelo canal cru fecha (§11.3)

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Ajuda — atalhos da venda (F1)');
    });
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90'); // a venda voltou como estava
  });

  test('F1 abre antes do primeiro bipe: a ajuda não depende da venda', async () => {
    const ui = render(<App api={apiStub()} />);
    await reachSale(ui);

    ui.stdin.write(F1);

    await expectFrame(ui.lastFrame, 'Ajuda — atalhos da venda (F1)');
    expect(ui.lastFrame()).not.toContain('bipar o primeiro item'); // o corpo da venda saiu de cena
  });

  test('com a consulta aberta os demais atalhos ficam bloqueados', async () => {
    const ui = render(<App api={apiStub()} />);
    await reachSale(ui);

    ui.stdin.write(F2);
    await expectFrame(ui.lastFrame, 'Consulta de preço (F2)');

    ui.stdin.write('\u001b[15~'); // F5 (desconto) com a consulta aberta: bloqueado

    await expectFrame(ui.lastFrame, 'Consulta de preço (F2)');
    expect(ui.lastFrame()).not.toContain('Desconto na venda (F5)');

    ui.stdin.write('\u001b'); // e o ESC fecha a consulta
    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Consulta de preço (F2)');
    });

    ui.stdin.write(F1); // com a ajuda aberta, idem
    await expectFrame(ui.lastFrame, 'Ajuda — atalhos da venda (F1)');
    ui.stdin.write('\u001b[21~'); // F10 (fechar caixa) com a ajuda aberta: bloqueado

    await expectFrame(ui.lastFrame, 'Ajuda — atalhos da venda (F1)');
    expect(ui.lastFrame()).not.toContain('Fechamento de caixa');
  });
});

describe('App: resiliência de rede e sessão (1117)', () => {
  /** Venda de um item como o servidor a devolve — o ponto de partida dos fluxos. */
  function saleWithOneItem(): SaleView {
    return {
      id: 'sale-1',
      items: [
        {
          productId: 'p1',
          name: 'Arroz 5kg',
          unit: 'UN',
          quantity: 1,
          unitPrice: 24.9,
          lineTotal: 24.9,
        },
      ],
      subtotal: 24.9,
      discountAmount: 0,
      total: 24.9,
      paidAmount: 0,
      changeAmount: 0,
      payments: [],
      customerId: null,
    };
  }

  function paidSale(paid: number, payments: PaymentView[]): SaleView {
    return { ...saleWithOneItem(), paidAmount: paid, payments };
  }

  function cashPayment(id: string, amount: number): PaymentView {
    return { id, method: 'CASH', amount, changeAmount: 0, status: 'APPROVED' };
  }

  /** Entra na venda com o item do primeiro bipe: o ponto de partida dos fluxos de resiliência. */
  async function reachSaleWithItem(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    return ui;
  }

  /** Falha de transporte: o servidor não respondeu, a venda continua em memória. */
  const networkProblem = { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' };

  /** 401 do servidor: a sessão caiu no meio da operação. */
  const sessionProblem = {
    status: 401,
    code: 'SESSION_EXPIRED',
    detail: 'sessão expirada; faça login novamente',
  };

  test('queda de rede não perde o bipe: o indicador acusa SEM CONEXÃO e o retry do ENTER retoma', async () => {
    let attempt = 0;
    const addSaleItem = vi.fn(async (): Promise<AddSaleItemOutcome> => {
      attempt += 1;

      return attempt === 1
        ? { ok: false, kind: 'retryable', problem: networkProblem }
        : { ok: true, sale: saleWithOneItem() };
    });
    const ui = render(<App api={apiStub({ addSaleItem })} />);
    await reachSale(ui);
    expect(ui.lastFrame()).toContain('conectado');

    ui.stdin.write('7891000100103\r');

    await expectFrame(ui.lastFrame, 'SEM CONEXÃO');
    expect(ui.lastFrame()).toContain('falha ao enviar o bipe — ENTER tenta de novo');

    ui.stdin.write('\r'); // retry manual: o bipe que ficou na fila

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('conectado'); // o servidor respondeu de novo
    expect(addSaleItem).toHaveBeenCalledTimes(2);
  });

  test('401 no meio da venda volta ao login com aviso e a mesma venda é retomada no novo login', async () => {
    let attempt = 0;
    const addSaleItem = vi.fn(async (): Promise<AddSaleItemOutcome> => {
      attempt += 1;

      return attempt === 1
        ? { ok: true, sale: saleWithOneItem() }
        : { ok: false, kind: 'failed', problem: sessionProblem };
    });
    const ui = await reachSaleWithItem(apiStub({ addSaleItem }));

    ui.stdin.write('7891000100103\r'); // a sessão cai no meio da segunda leitura

    await expectFrame(
      ui.lastFrame,
      'sessão expirada — entre novamente; a venda continua aberta',
    );
    expect(ui.lastFrame()).toContain('Usuário:'); // o formulário voltou

    // login de novo, mesmo caixa: o fluxo 1106/1107 inteiro
    await signIn(ui.lastFrame, ui.stdin);
    await expectFrame(ui.lastFrame, 'Escolha o caixa');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Abertura de caixa');
    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Fundo de troco: R$ 10,00');
    ui.stdin.write('\r');

    // a mesma venda, com os mesmos itens, e o aviso da retomada à vista
    await expectFrame(ui.lastFrame, 'venda retomada — os itens foram preservados');
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
    expect(ui.lastFrame()).toContain('PDV minimercado · Caixa principal');
  });

  test('a loja do /auth/me aparece no cabeçalho, uma vez por login', async () => {
    const currentSession = vi.fn(
      async (): Promise<SessionOutcome> => ({
        ok: true,
        store: { code: '01', name: 'Mercadinho Central' },
      }),
    );
    const ui = render(<App api={apiStub({ currentSession })} />);

    await reachSale(ui);

    await expectFrame(ui.lastFrame, 'PDV minimercado · Mercadinho Central · Caixa principal');
    expect(currentSession).toHaveBeenCalledTimes(1);
    expect(ui.lastFrame()).toContain('bipar o primeiro item para iniciar a venda');
  });

  test('falha do /auth/me não bloqueia a venda: o cabeçalho só fica sem loja', async () => {
    const currentSession = vi.fn(
      async (): Promise<SessionOutcome> => ({
        ok: false,
        problem: { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' },
      }),
    );
    const ui = render(<App api={apiStub({ currentSession })} />);

    await reachSale(ui);

    await vi.waitFor(() => {
      expect(currentSession).toHaveBeenCalledTimes(1);
    });
    expect(ui.lastFrame()).toContain('PDV minimercado · Caixa principal');
    expect(ui.lastFrame()).not.toContain('503 — UNAVAILABLE'); // o rótulo não vira tela de erro
    expect(ui.lastFrame()).toContain('bipar o primeiro item para iniciar a venda');
  });

  test('409 de idempotência relê a venda do servidor e avisa, sem repetir a operação', async () => {
    const addPayment = vi.fn(
      async (): Promise<AddPaymentOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: {
          status: 409,
          code: 'IDEMPOTENCY_KEY_REUSED',
          detail: 'chave de idempotência já usada com outra requisição',
        },
      }),
    );
    // a releitura: o pagamento que já estava gravado com a chave antiga
    const getSale = vi.fn(
      async (): Promise<SaleReloadOutcome> => ({
        ok: true,
        sale: paidSale(10, [cashPayment('pay-1', 10)]),
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ addPayment, getSale }));

    ui.stdin.write('\u001b[20~'); // F9 abre o pagamento
    await expectFrame(ui.lastFrame, 'Pagamento (F9)');
    ui.stdin.write('1500');
    await expectFrame(ui.lastFrame, 'Valor: R$ 15,00');
    ui.stdin.write('\r');

    await expectFrame(
      ui.lastFrame,
      'operação já registrada com outros dados — venda conferida no servidor',
    );
    expect(getSale).toHaveBeenCalledWith('sale-1');
    expect(addPayment).toHaveBeenCalledTimes(1); // a operação não foi repetida
    expect(ui.lastFrame()).toContain('1. DINHEIRO — R$ 10,00'); // a venda é a que o servidor tem
    expect(ui.lastFrame()).toContain('Pago: R$ 10,00 de R$ 24,90');
    expect(ui.lastFrame()).toContain('Pagamento (F9)');
  });

  test('retry idêntico do pagamento reusa a chave, para o servidor devolver o replay', async () => {
    let attempt = 0;
    const addPayment = vi.fn<TerminalApi['addPayment']>(async () => {
      attempt += 1;

      return attempt === 1
        ? { ok: false, kind: 'retryable', problem: networkProblem }
        : { ok: true, sale: paidSale(24.9, [cashPayment('pay-1', 24.9)]) };
    });
    const ui = await reachSaleWithItem(apiStub({ addPayment }));

    ui.stdin.write('\u001b[20~'); // F9
    await expectFrame(ui.lastFrame, 'Pagamento (F9)');
    ui.stdin.write('2490');
    await expectFrame(ui.lastFrame, 'Valor: R$ 24,90');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'falha ao registrar o pagamento — ENTER tenta de novo');
    expect(addPayment).toHaveBeenCalledTimes(1);

    ui.stdin.write('\r'); // o mesmo ENTER, com o mesmo pedido: mesma chave

    await expectFrame(ui.lastFrame, '1. DINHEIRO — R$ 24,90');
    expect(addPayment).toHaveBeenCalledTimes(2);
    expect(addPayment.mock.calls[0]?.[2]).toBe(addPayment.mock.calls[1]?.[2]);
  });

  test('mudar a forma do pagamento é nova intenção: a chave antiga não é reaproveitada', async () => {
    let attempt = 0;
    const addPayment = vi.fn<TerminalApi['addPayment']>(async () => {
      attempt += 1;

      return attempt === 1
        ? { ok: false, kind: 'retryable', problem: networkProblem }
        : { ok: true, sale: paidSale(24.9, [{ ...cashPayment('pay-1', 24.9), method: 'PIX' }]) };
    });
    const ui = await reachSaleWithItem(apiStub({ addPayment }));

    ui.stdin.write('\u001b[20~'); // F9
    await expectFrame(ui.lastFrame, 'Pagamento (F9)');
    ui.stdin.write('2490');
    await expectFrame(ui.lastFrame, 'Valor: R$ 24,90');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'falha ao registrar o pagamento — ENTER tenta de novo');

    ui.stdin.write('\u001b[C'); // seta → muda a forma: o corpo mudou, a chave não vale mais
    await expectFrame(ui.lastFrame, '[PIX]');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, '1. PIX — R$ 24,90');
    expect(addPayment).toHaveBeenCalledTimes(2);
    expect(addPayment.mock.calls[0]?.[2]).not.toBe(addPayment.mock.calls[1]?.[2]);
  });

  test('401 na gaveta leva ao login preservando a venda, em vez de ficar no modal', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: false,
        kind: 'failed',
        problem: sessionProblem,
      }),
    );
    const ui = await reachSaleWithItem(apiStub({ withdrawCash }));

    ui.stdin.write('\u001b[18~'); // F7 abre a sangria
    await expectFrame(ui.lastFrame, 'Sangria (F7)');
    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Motivo:');
    ui.stdin.write('troco para o banco');
    await expectFrame(ui.lastFrame, 'Motivo: troco para o banco');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(
      ui.lastFrame,
      'sessão expirada — entre novamente; a venda continua aberta',
    );
    expect(ui.lastFrame()).toContain('Usuário:');
    expect(ui.lastFrame()).not.toContain('Sangria (F7)');
  });
});

describe('App: troca de operador (1118)', () => {
  /** F12 no canal cru: o `useInput` do Ink não entrega as teclas F (1105). */
  const F12 = '\u001b[24~';

  afterEach(() => {
    clearToken(); // o token vive na sessão em memória, entre um teste e outro
  });

  /**
   * Logout **de verdade** sobre um client mínimo: é o `finally` da camada de API (1107) que esquece
   * o token da sessão, e é isso que o F12 precisa provar — o dublê do `apiStub` não limpa nada.
   */
  function realLogout(): Promise<void> {
    return createTerminalApi({ post: async () => undefined } as unknown as ApiClient).logout();
  }

  /** Venda com um item: o ponto de partida da troca com venda aberta. */
  async function reachSaleWithItem(api: TerminalApi) {
    const ui = render(<App api={api} />);
    await reachSale(ui);
    ui.stdin.write('7891000100103\r');
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');

    return ui;
  }

  test('F12 sem venda confirma a troca: só o ENTER encerra a sessão, que volta ao login', async () => {
    const logout = vi.fn(realLogout);
    const ui = render(<App api={apiStub({ logout })} />);
    await reachSale(ui);
    // o login em dois passos (1107) já revogou a sessão provisória; daqui em diante só o F12 conta
    logout.mockClear();
    setToken('tok-antigo');

    ui.stdin.write(F12);

    await expectFrame(ui.lastFrame, 'Trocar operador (F12)');
    expect(ui.lastFrame()).toContain('ENTER troca de operador · ESC volta');
    expect(logout).not.toHaveBeenCalled(); // nada acontece antes do ENTER
    expect(getToken()).toBe('tok-antigo');

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'Usuário:');
    expect(ui.lastFrame()).toContain('PDV minimercado — entrada do operador');
    expect(logout).toHaveBeenCalledTimes(1);
    expect(getToken()).toBeNull(); // a sessão de login terminou
  });

  test('F12 com venda aberta bloqueia: confirma e nada vai à API antes do ENTER', async () => {
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    const logout = vi.fn(async () => undefined);
    const ui = await reachSaleWithItem(apiStub({ cancelSale, logout }));
    logout.mockClear(); // o login em dois passos (1107) já tinha revogado a sessão provisória

    ui.stdin.write(F12);

    await expectFrame(ui.lastFrame, 'Trocar operador (F12)');
    expect(ui.lastFrame()).toContain('há venda aberta');
    expect(ui.lastFrame()).toContain('ENTER cancela a venda e troca de operador · ESC volta');
    expect(ui.lastFrame()).not.toContain('TOTAL: R$ 24,90'); // o corpo da venda saiu de cena
    expect(cancelSale).not.toHaveBeenCalled();
    expect(logout).not.toHaveBeenCalled();

    ui.stdin.write('\u001b'); // ESC volta para a venda, intacta

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Trocar operador (F12)');
    });
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
    expect(cancelSale).not.toHaveBeenCalled();
    expect(logout).not.toHaveBeenCalled();
  });

  test('ENTER cancela a venda com o motivo e encerra a sessão sem fechar o caixa', async () => {
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    const logout = vi.fn(realLogout);
    const api = apiStub({ cancelSale, logout });
    const ui = await reachSaleWithItem(api);
    logout.mockClear(); // o login em dois passos (1107) já tinha revogado a sessão provisória
    setToken('tok-antigo');

    ui.stdin.write(F12);
    await expectFrame(ui.lastFrame, 'ENTER cancela a venda e troca de operador');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'Usuário:');
    expect(cancelSale).toHaveBeenCalledWith('sale-1', 'troca de operador', expect.any(String));
    expect(logout).toHaveBeenCalledTimes(1);
    // a venda sai primeiro: é o cancelamento que a libera para o próximo operador
    expect(cancelSale.mock.invocationCallOrder[0] ?? 0).toBeLessThan(
      logout.mock.invocationCallOrder.at(-1) ?? 0,
    );
    expect(getToken()).toBeNull();
    // a sessão de **caixa** continua aberta: quem encerrou foi só a sessão de login
    expect(api.closeCashSession).not.toHaveBeenCalled();
  });

  test('a troca lembra o caixa: o login nasce com o mesmo caixa selecionado', async () => {
    const cancelSale = vi.fn(async (): Promise<CancelSaleOutcome> => ({ ok: true }));
    // a lista volta em outra ordem depois da troca: sem a lembrança, o primeiro (02) é que estaria marcado
    let listed = 0;
    const listCashRegisters = vi.fn(async (): Promise<CashRegistersOutcome> => {
      listed += 1;

      return listed === 1
        ? { ok: true, registers: [CAIXA_01, CAIXA_02] }
        : { ok: true, registers: [CAIXA_02, CAIXA_01] };
    });
    const ui = await reachSaleWithItem(apiStub({ cancelSale, listCashRegisters }));

    ui.stdin.write(F12);
    await expectFrame(ui.lastFrame, 'Trocar operador (F12)');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'Usuário:');

    await signIn(ui.lastFrame, ui.stdin);
    await expectFrame(ui.lastFrame, 'Escolha o caixa');

    expect(ui.lastFrame()).toContain('› 01 Caixa principal'); // o caixa da troca, não o primeiro da lista
    expect(ui.lastFrame()).not.toContain('› 02');
  });

  test('falha do cancelamento não troca nada: a mensagem fica no modal e a venda continua', async () => {
    const cancelSale = vi.fn(
      async (): Promise<CancelSaleOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'sem permissão para cancelar a venda',
      }),
    );
    const logout = vi.fn(async () => undefined);
    const ui = await reachSaleWithItem(apiStub({ cancelSale, logout }));
    logout.mockClear(); // o login em dois passos (1107) já tinha revogado a sessão provisória

    ui.stdin.write(F12);
    await expectFrame(ui.lastFrame, 'Trocar operador (F12)');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'sem permissão para cancelar a venda');
    expect(ui.lastFrame()).toContain('Trocar operador (F12)'); // o modal segue à vista
    expect(ui.lastFrame()).not.toContain('Usuário:'); // a sessão não terminou
    expect(logout).not.toHaveBeenCalled();

    ui.stdin.write('\u001b'); // e o ESC devolve a venda como estava

    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('Trocar operador (F12)');
    });
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
    expect(cancelSale).toHaveBeenCalledTimes(1);
  });
});
