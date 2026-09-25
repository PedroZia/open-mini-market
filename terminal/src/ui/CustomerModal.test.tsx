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
  CustomerOption,
  CustomerSaleOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  SaleItemMutationOutcome,
  SearchCustomersOutcome,
  TerminalApi,
} from '../api/terminalApi';
import type { SaleView } from '../core/state';
import { CustomerModal } from './CustomerModal';

/**
 * Modal de cliente (1112) com a camada de API dublada: o que se testa é o fluxo — busca pelo termo,
 * lista com nome e CPF, seleção pelas setas, vínculo pelo ENTER, remoção pelo DEL —, as recusas do
 * servidor ficando **no próprio modal** e o retry manual da rede. Nunca o HTTP (esse é do
 * `@minimarket/api-client`/`terminalApi.test.ts`). O ESC não aparece aqui: quem cancela é o canal
 * cru do shell (testado no `App.test.tsx`), como no desconto (1111).
 *
 * Cada escrita espera o frame: o `useInput` do Ink só re-registra o callback com o estado do último
 * render, então duas teclas no mesmo tick seriam tratadas com a lista antiga.
 */

const SALE_ID = 'sale-1';

/** Clientes da busca: Maria tem CPF (o servidor guarda só dígitos) e Ana não. */
const MARIA: CustomerOption = { id: 'c1', name: 'Maria Silva', taxId: '12345678900' };
const ANA: CustomerOption = { id: 'c2', name: 'Ana Souza', taxId: null };

/** Cliente já vinculado à venda quando o F6 abre. */
const JOAO: CustomerOption = { id: 'c9', name: 'João Pereira', taxId: null };

/** Venda como o servidor a devolve depois do vínculo: o `customerId` é dele (BR-12). */
function saleWith(customerId: string | null): SaleView {
  return {
    id: SALE_ID,
    items: [],
    subtotal: 0,
    discountAmount: 0,
    total: 0,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId,
  };
}

/** Dublê da camada de API: só o cliente entra aqui; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  const unused = { status: 0, code: null, detail: 'não usado no modal de cliente' };

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
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({ ok: false, problem: unused }),
    ),
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
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA, ANA] }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith(MARIA.id) }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith(null) }),
    ),
    // o pagamento (1113) é de outra tela: aqui só fecha o contrato
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    // a gaveta (1114) é de outro modal: aqui só fecha o contrato
    withdrawCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
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

function renderModal(api: TerminalApi = apiStub(), customer: CustomerOption | null = null) {
  const onUpdated = vi.fn();
  const onFailed = vi.fn();
  const ui = render(
    <CustomerModal
      saleId={SALE_ID}
      customer={customer}
      api={api}
      onUpdated={onUpdated}
      onFailed={onFailed}
    />,
  );

  return { ...ui, onUpdated, onFailed };
}

/** Digita o termo esperando o frame e dá o ENTER que busca; a lista é o que o teste asserta. */
async function typeTerm(ui: ModalUi, term: string): Promise<void> {
  ui.stdin.write(term);
  await expectFrame(ui.lastFrame, `Busca: ${term}`);
  ui.stdin.write('\r');
}

describe('CustomerModal', () => {
  test('abre com o campo vazio e a dica, sem chamar a API', () => {
    const api = apiStub();
    const { lastFrame } = renderModal(api);

    expect(lastFrame()).toContain('Cliente na venda (F6)');
    expect(lastFrame()).toContain('Busca:');
    expect(lastFrame()).toContain('digite o nome ou o CPF e ENTER busca');
    expect(api.searchCustomers).not.toHaveBeenCalled();
  });

  test('ENTER com o campo vazio não busca: o modal pede o termo', async () => {
    const api = apiStub();
    const ui = renderModal(api);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o nome ou o CPF do cliente');
    expect(api.searchCustomers).not.toHaveBeenCalled();
  });

  test('ENTER busca o termo digitado e lista nome e CPF (mascarado) do servidor', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA, ANA] }),
    );
    const ui = renderModal(apiStub({ searchCustomers }));

    await typeTerm(ui, 'maria');

    await expectFrame(ui.lastFrame, '› Maria Silva — 123.456.789-00');
    expect(ui.lastFrame()).toContain('Ana Souza');
    expect(searchCustomers).toHaveBeenCalledWith('maria');
    expect(ui.lastFrame()).toContain('DEL remove'); // o rodapé segue à vista
  });

  test('as setas escolhem o resultado e o ENTER vincula o selecionado', async () => {
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith(ANA.id) }),
    );
    const ui = renderModal(apiStub({ linkCustomer }));

    await typeTerm(ui, 'a');
    await expectFrame(ui.lastFrame, '› Maria Silva');

    ui.stdin.write('\x1b[B'); // ↓ para o segundo
    await expectFrame(ui.lastFrame, '› Ana Souza');
    ui.stdin.write('\r'); // vincula o selecionado

    await vi.waitFor(() => {
      expect(ui.onUpdated).toHaveBeenCalledWith(saleWith(ANA.id), ANA);
    });
    expect(linkCustomer).toHaveBeenCalledWith(SALE_ID, 'c2');
  });

  test('editar o termo limpa a lista: o ENTER volta a buscar em vez de vincular', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [MARIA] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith(MARIA.id) }),
    );
    const ui = renderModal(apiStub({ searchCustomers, linkCustomer }));

    await typeTerm(ui, 'maria');
    await expectFrame(ui.lastFrame, '› Maria Silva');

    ui.stdin.write(' silva'); // mexeu no termo: o resultado antigo não vale mais
    await expectFrame(ui.lastFrame, 'Busca: maria silva');

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(searchCustomers).toHaveBeenCalledTimes(2);
    });
    expect(linkCustomer).not.toHaveBeenCalled();
  });

  test('busca sem resultado avisa e o ENTER seguinte rebusca sem vincular nada', async () => {
    const searchCustomers = vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: true, customers: [] }),
    );
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith(MARIA.id) }),
    );
    const ui = renderModal(apiStub({ searchCustomers, linkCustomer }));

    await typeTerm(ui, 'zzz');

    await expectFrame(ui.lastFrame, 'nenhum cliente encontrado');

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(searchCustomers).toHaveBeenCalledTimes(2);
    });
    expect(linkCustomer).not.toHaveBeenCalled();
  });

  test('recusa do servidor (422 CUSTOMER_INACTIVE) fica no modal, que continua aberto', async () => {
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'cliente desativado no cadastro — escolha outro',
      }),
    );
    const ui = renderModal(apiStub({ linkCustomer }));

    await typeTerm(ui, 'joao');
    await expectFrame(ui.lastFrame, '› Maria Silva');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'cliente desativado no cadastro — escolha outro');
    expect(ui.lastFrame()).toContain('Cliente na venda (F6)');
    expect(ui.lastFrame()).toContain('› Maria Silva'); // a lista continua para escolher outro
    expect(ui.onUpdated).not.toHaveBeenCalled();
  });

  test('falha de rede no vínculo pede retry e o ENTER refaz a mesma chamada', async () => {
    let attempts = 0;
    const linkCustomer = vi.fn(async (): Promise<CustomerSaleOutcome> => {
      attempts += 1;
      return attempts === 1
        ? {
            ok: false,
            kind: 'retryable',
            problem: { status: 0, code: null, detail: 'fetch failed' },
          }
        : { ok: true, sale: saleWith(MARIA.id) };
    });
    const ui = renderModal(apiStub({ linkCustomer }));

    await typeTerm(ui, 'maria');
    await expectFrame(ui.lastFrame, '› Maria Silva');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'falha ao vincular — ENTER tenta de novo');

    ui.stdin.write('\r'); // retry manual

    await vi.waitFor(() => {
      expect(ui.onUpdated).toHaveBeenCalledWith(saleWith(MARIA.id), MARIA);
    });
    expect(linkCustomer).toHaveBeenCalledTimes(2);
  });

  test('falha de rede na busca pede retry e o ENTER rebusca o mesmo termo', async () => {
    let attempts = 0;
    const searchCustomers = vi.fn(async (): Promise<SearchCustomersOutcome> => {
      attempts += 1;
      return attempts === 1
        ? { ok: false, kind: 'retryable', problem: { status: 0, code: null, detail: 'fetch failed' } }
        : { ok: true, customers: [MARIA] };
    });
    const ui = renderModal(apiStub({ searchCustomers }));

    await typeTerm(ui, 'maria');

    await expectFrame(ui.lastFrame, 'falha ao buscar — ENTER tenta de novo');

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, '› Maria Silva — 123.456.789-00');
    expect(searchCustomers).toHaveBeenCalledTimes(2);
    expect(searchCustomers).toHaveBeenLastCalledWith('maria');
  });

  test('sem cliente vinculado o DEL só avisa e nada vai à API', async () => {
    const api = apiStub();
    const ui = renderModal(api);

    ui.stdin.write('\x1b[3~'); // DEL

    await expectFrame(ui.lastFrame, 'nenhum cliente vinculado para remover');
    expect(api.unlinkCustomer).not.toHaveBeenCalled();
  });

  test('com cliente vinculado o modal mostra o atual e o DEL remove', async () => {
    const unlinkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: true, sale: saleWith(null) }),
    );
    const ui = renderModal(apiStub({ unlinkCustomer }), JOAO);

    expect(ui.lastFrame()).toContain('Cliente atual: João Pereira');

    ui.stdin.write('\x1b[3~'); // DEL remove

    await vi.waitFor(() => {
      expect(ui.onUpdated).toHaveBeenCalledWith(saleWith(null), null);
    });
    expect(unlinkCustomer).toHaveBeenCalledWith(SALE_ID);
  });

  test('falha bloqueante do vínculo (409 SALE_NOT_OPEN) é do shell: o modal avisa o fallback', async () => {
    const problem = { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' };
    const linkCustomer = vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'failed', problem }),
    );
    const ui = renderModal(apiStub({ linkCustomer }));

    await typeTerm(ui, 'maria');
    await expectFrame(ui.lastFrame, '› Maria Silva');
    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(ui.onFailed).toHaveBeenCalledWith(problem);
    });
  });
});
