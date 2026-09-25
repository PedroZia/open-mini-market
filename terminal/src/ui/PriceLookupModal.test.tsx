import { render } from 'ink-testing-library';
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
  CloseCashSessionOutcome,
  CompleteSaleOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
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
} from '../api/terminalApi';
import { PriceLookupModal } from './PriceLookupModal';

/**
 * Consulta de preço (F2, 1116) com a camada de API dublada: o que se testa é o fluxo — o termo
 * bruto consultado como código, a queda para a busca por nome quando o servidor não conhece o
 * código, a lista, a escolha pelas setas, o detalhe com preço e saldo —, as recusas do servidor
 * ficando **no próprio modal** e o retry manual da rede. Nunca o HTTP (esse é do
 * `@minimarket/api-client`/`terminalApi.test.ts`).
 *
 * O ESC não aparece aqui: quem fecha é o canal cru do shell (testado no `App.test.tsx`), como nos
 * demais modais. E o mais importante do passo: a consulta **não** cria venda nem mexe em nenhuma —
 * o teste do código de barras assere que nenhuma chamada de venda saiu.
 */

const ARROZ: ProductOption = { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN' };
const FEIJAO: ProductOption = { id: 'p2', name: 'Feijão 1kg', price: 8.5, unit: 'UN' };

/** Saldo que o servidor devolve no `StockDetailResponse` (passo 704): estoque baixo é sinal dele. */
const SALDO_BAIXO: StockBalanceView = { quantity: 3, minQuantity: 5, lowStock: true };

/** Dublê da camada de API: só a consulta entra aqui; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  const unused = { status: 0, code: null, detail: 'não usado na consulta de preço' };

  return {
    login: vi.fn(
      async (): Promise<LoginOutcome> => ({ ok: false, kind: 'rejected', message: unused.detail }),
    ),
    logout: vi.fn(async () => undefined),
    listCashRegisters: vi.fn(
      async (): Promise<CashRegistersOutcome> => ({ ok: true, registers: [] }),
    ),
    openCashRegister: vi.fn(
      async (): Promise<OpenCashRegisterOutcome> => ({ ok: false, kind: 'alreadyOpen' }),
    ),
    currentCashSession: vi.fn(
      async (): Promise<CurrentCashSessionOutcome> => ({ ok: false, problem: unused }),
    ),
    // o código não é conhecido por padrão: quem decide o desfecho é o teste
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: 'notFound',
        problem: { ...unused, status: 404, code: 'PRODUCT_NOT_FOUND' },
      }),
    ),
    searchProducts: vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [] }),
    ),
    productStock: vi.fn(
      async (): Promise<ProductStockOutcome> => ({ ok: true, stock: SALDO_BAIXO }),
    ),
    // as operações da venda não existem na consulta: se alguma for chamada, o teste acusa
    createSale: vi.fn(
      async (): Promise<CreateSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    addSaleItem: vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({ ok: false, kind: 'notFound', barcode: 'x' }),
    ),
    changeSaleItemQuantity: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    removeSaleItem: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    ),
    applyDiscount: vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [] }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    withdrawCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    cashSessionSummary: vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({ ok: false, problem: unused }),
    ),
    closeCashSession: vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: unused,
      }),
    ),
    cancelSale: vi.fn(
      async (): Promise<CancelSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
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

type ModalUi = { lastFrame: () => string | undefined; stdin: { write: (data: string) => void } };

function renderModal(api: TerminalApi = apiStub()) {
  const onFailed = vi.fn();
  const ui = render(<PriceLookupModal api={api} onFailed={onFailed} />);

  return { ...ui, onFailed };
}

/** Digita o termo esperando o frame e dá o ENTER que consulta; o desfecho é do teste. */
async function typeTerm(ui: ModalUi, term: string): Promise<void> {
  ui.stdin.write(term);
  await expectFrame(ui.lastFrame, `Busca: ${term}`);
  ui.stdin.write('\r');
}

describe('PriceLookupModal', () => {
  test('abre com o campo vazio e a dica, sem chamar a API', () => {
    const api = apiStub();
    const { lastFrame } = renderModal(api);

    expect(lastFrame()).toContain('Consulta de preço (F2)');
    expect(lastFrame()).toContain('Busca:');
    expect(lastFrame()).toContain('digite o código de barras ou o nome e ENTER consulta');
    expect(api.resolveBarcode).not.toHaveBeenCalled();
    expect(api.searchProducts).not.toHaveBeenCalled();
  });

  test('ENTER com o campo vazio não consulta: o modal pede o termo', async () => {
    const api = apiStub();
    const ui = renderModal(api);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o código de barras ou o nome do produto');
    expect(api.resolveBarcode).not.toHaveBeenCalled();
  });

  test('código encontrado mostra nome, preço e saldo do servidor, sem criar venda nenhuma', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN', quantity: null },
      }),
    );
    const productStock = vi.fn(async (): Promise<ProductStockOutcome> => ({ ok: true, stock: SALDO_BAIXO }));
    const api = apiStub({ resolveBarcode, productStock });
    const ui = renderModal(api);

    await typeTerm(ui, '7891000100103');

    await expectFrame(ui.lastFrame, 'Produto: Arroz 5kg');
    expect(ui.lastFrame()).toContain('Preço: R$ 24,90 · UN');
    expect(ui.lastFrame()).toContain('Saldo: 3 · mínimo 5 · ESTOQUE BAIXO');
    // o código vai bruto ao servidor (BR-14) e o saldo é do produto que ele devolveu
    expect(resolveBarcode).toHaveBeenCalledWith('7891000100103');
    expect(productStock).toHaveBeenCalledWith('p1');
    // consulta não é venda: nenhuma chamada de venda sai do F2
    expect(api.createSale).not.toHaveBeenCalled();
    expect(api.addSaleItem).not.toHaveBeenCalled();
    expect(api.changeSaleItemQuantity).not.toHaveBeenCalled();
    expect(api.removeSaleItem).not.toHaveBeenCalled();
    expect(api.applyDiscount).not.toHaveBeenCalled();
    expect(api.cancelSale).not.toHaveBeenCalled();
  });

  test('código não conhecido cai na busca pelo nome: a lista mostra nome e preço', async () => {
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [ARROZ, FEIJAO] }),
    );
    const productStock = vi.fn(async (): Promise<ProductStockOutcome> => ({ ok: true, stock: SALDO_BAIXO }));
    const ui = renderModal(apiStub({ searchProducts, productStock }));

    await typeTerm(ui, 'arroz');

    await expectFrame(ui.lastFrame, '› Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('Feijão 1kg — R$ 8,50');
    expect(searchProducts).toHaveBeenCalledWith('arroz');
    expect(productStock).not.toHaveBeenCalled(); // a lista ainda não consultou saldo de ninguém
    expect(ui.lastFrame()).toContain('↑↓ escolhe · ENTER consulta · ESC fecha');
  });

  test('as setas escolhem o resultado e o ENTER no selecionado mostra preço e estoque', async () => {
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [ARROZ, FEIJAO] }),
    );
    const productStock = vi.fn(async (): Promise<ProductStockOutcome> => ({
      ok: true,
      stock: { quantity: 12, minQuantity: 5, lowStock: false },
    }));
    const ui = renderModal(apiStub({ searchProducts, productStock }));

    await typeTerm(ui, 'feijao');
    await expectFrame(ui.lastFrame, '› Arroz 5kg');

    ui.stdin.write('\x1b[B'); // ↓ para o segundo resultado
    await expectFrame(ui.lastFrame, '› Feijão 1kg');
    ui.stdin.write('\r'); // consulta o saldo do selecionado

    await expectFrame(ui.lastFrame, 'Produto: Feijão 1kg');
    expect(ui.lastFrame()).toContain('Preço: R$ 8,50 · UN');
    expect(ui.lastFrame()).toContain('Saldo: 12 · mínimo 5');
    expect(ui.lastFrame()).not.toContain('ESTOQUE BAIXO'); // saldo acima do mínimo
    expect(productStock).toHaveBeenCalledWith('p2');
  });

  test('editar o termo limpa a lista: o ENTER volta a consultar em vez de reusar o resultado', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: false,
        kind: 'notFound',
        problem: { status: 404, code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' },
      }),
    );
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [ARROZ] }),
    );
    const ui = renderModal(apiStub({ resolveBarcode, searchProducts }));

    await typeTerm(ui, 'arroz');
    await expectFrame(ui.lastFrame, '› Arroz 5kg');

    ui.stdin.write('a'); // mexeu no termo: a lista antiga não vale mais
    await expectFrame(ui.lastFrame, 'Busca: arroza');
    expect(ui.lastFrame()).not.toContain('› Arroz 5kg');

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(resolveBarcode).toHaveBeenCalledWith('arroza');
    });
  });

  test('não encontrado nem por código nem por nome avisa e nada mais é consultado', async () => {
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({ ok: true, products: [] }),
    );
    const productStock = vi.fn();
    const ui = renderModal(apiStub({ searchProducts, productStock }));

    await typeTerm(ui, 'zzz');

    await expectFrame(ui.lastFrame, 'nenhum produto encontrado');
    expect(searchProducts).toHaveBeenCalledWith('zzz');
    expect(productStock).not.toHaveBeenCalled();
  });

  test('recusa do servidor (403 sem `product.read`) fica no modal, que continua aberto', async () => {
    const searchProducts = vi.fn(
      async (): Promise<SearchProductsOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'sem permissão para consultar produtos',
      }),
    );
    const ui = renderModal(apiStub({ searchProducts }));

    await typeTerm(ui, 'arroz');

    await expectFrame(ui.lastFrame, 'sem permissão para consultar produtos');
    expect(ui.lastFrame()).toContain('Consulta de preço (F2)'); // o modal segue aberto
    expect(ui.onFailed).not.toHaveBeenCalled();
  });

  test('recusa do saldo (404 do produto que sumiu) avisa no modal sem fechar', async () => {
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN', quantity: null },
      }),
    );
    const productStock = vi.fn(
      async (): Promise<ProductStockOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'produto não encontrado — faça a consulta de novo',
      }),
    );
    const ui = renderModal(apiStub({ resolveBarcode, productStock }));

    await typeTerm(ui, '7891000100103');

    await expectFrame(ui.lastFrame, 'produto não encontrado — faça a consulta de novo');
    expect(ui.lastFrame()).toContain('Consulta de preço (F2)');
  });

  test('falha de rede pede retry e o ENTER refaz a consulta do mesmo termo', async () => {
    let attempts = 0;
    const resolveBarcode = vi.fn(async (): Promise<BarcodeLookupOutcome> => {
      attempts += 1;

      return attempts === 1
        ? {
            ok: false,
            kind: 'retryable' as const,
            problem: { status: 0, code: null, detail: 'fetch failed' },
          }
        : {
            ok: true,
            product: { id: 'p1', name: 'Arroz 5kg', price: 24.9, unit: 'UN', quantity: null },
          };
    });
    const ui = renderModal(apiStub({ resolveBarcode }));

    await typeTerm(ui, '7891000100103');

    await expectFrame(ui.lastFrame, 'falha ao consultar — ENTER tenta de novo');

    ui.stdin.write('\r'); // retry manual

    await expectFrame(ui.lastFrame, 'Produto: Arroz 5kg');
    expect(resolveBarcode).toHaveBeenCalledTimes(2);
    expect(resolveBarcode).toHaveBeenLastCalledWith('7891000100103');
  });

  test('falha bloqueante da consulta é do shell: o modal chama o `onFailed`', async () => {
    const problem = { status: 403, code: 'ACCESS_DENIED', detail: 'permissão product.read' };
    const resolveBarcode = vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({ ok: false, kind: 'failed', problem }),
    );
    const ui = renderModal(apiStub({ resolveBarcode }));

    await typeTerm(ui, '7891000100103');

    await vi.waitFor(() => {
      expect(ui.onFailed).toHaveBeenCalledWith(problem);
    });
  });
});
