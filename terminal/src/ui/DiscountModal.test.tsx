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
  SaleItemMutationOutcome,
  SearchCustomersOutcome,
  CancelSaleOutcome,
  CashSessionSummaryOutcome,
  CloseCashSessionOutcome,
  TerminalApi,
} from '../api/terminalApi';
import type { SaleItemView, SaleView } from '../core/state';
import { DiscountModal } from './DiscountModal';

/**
 * Modal de desconto (1111) com a camada de API dublada: o que se testa é o formulário — máscaras de
 * valor e percentual, motivo obrigatório, as recusas do servidor (403 do OPERADOR, 422 do limite da
 * loja) ficando **no próprio modal**, o retry manual da rede e a falha bloqueante que é do shell —,
 * nunca o HTTP (esse é do `@minimarket/api-client`). O ESC não aparece aqui: quem cancela é o canal
 * cru do shell (testado no `App.test.tsx`), como no autoteste do F11.
 *
 * Cada escrita espera o frame: o `useInput` do Ink só re-registra o callback com o estado do último
 * render, então duas teclas no mesmo tick seriam tratadas com o campo em foco antigo.
 */

const SALE_ID = 'sale-1';

const ARROZ: SaleItemView = {
  productId: 'p1',
  name: 'Arroz 5kg',
  unit: 'UN',
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
};

/** Venda como o servidor devolveu ao aplicar o desconto: os totais são os dele (BR-12). */
function discounted(total: number): SaleView {
  return {
    id: SALE_ID,
    items: [ARROZ],
    subtotal: 24.9,
    discountAmount: 24.9 - total,
    total,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId: null,
  };
}

/** Dublê da camada de API: só o desconto entra aqui; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  const unused = { status: 0, code: null, detail: 'não usado no modal de desconto' };

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
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted(22.41) }),
    ),
    // o F6 (1112) é de outro modal: aqui só fecha o contrato
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
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
    // o fechamento e o cancelamento (1115) são do shell: aqui só fecha o contrato
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

function renderModal(api: TerminalApi = apiStub()) {
  const onApplied = vi.fn();
  const onFailed = vi.fn();
  const ui = render(
    <DiscountModal saleId={SALE_ID} api={api} onApplied={onApplied} onFailed={onFailed} />,
  );

  return { ...ui, onApplied, onFailed };
}

/** TAB e espera o foco chegar no campo (o ciclo é valor → motivo → tipo). */
async function tabTo(
  ui: { stdin: { write: (data: string) => void }; lastFrame: () => string | undefined },
  field: string,
): Promise<void> {
  ui.stdin.write('\t');
  await expectFrame(ui.lastFrame, `› ${field}`);
}

describe('DiscountModal', () => {
  test('abre no VALOR com a máscara de centavos e mostra os dois tipos', () => {
    const { lastFrame } = renderModal();
    const frame = lastFrame() ?? '';

    expect(frame).toContain('Desconto na venda (F5)');
    expect(frame).toContain('Tipo: [VALOR] · PERCENTUAL');
    expect(frame).toContain('› Valor: R$ 0,00');
    expect(frame).toContain('digite o valor em centavos: 1000 vira R$ 10,00');
    expect(frame).toContain('TAB troca o campo');
  });

  test('VALOR: os dígitos viram reais e a venda que volta é a do servidor', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted(14.9) }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');

    await tabTo(ui, 'Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(ui.onApplied).toHaveBeenCalledWith(discounted(14.9));
    });
    expect(applyDiscount).toHaveBeenCalledWith(SALE_ID, {
      type: 'VALUE',
      value: 10,
      reason: 'cliente pediu',
    });
  });

  test('PERCENTUAL: ← muda o tipo, a máscara vira % e o valor vai inteiro', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted(22.41) }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    await tabTo(ui, 'Motivo:');
    await tabTo(ui, 'Tipo:');
    ui.stdin.write('\u001b[C'); // seta → no campo do tipo: VALOR vira PERCENTUAL
    await expectFrame(ui.lastFrame, 'Tipo: VALOR · [PERCENTUAL]');

    await tabTo(ui, 'Valor:');
    await expectFrame(ui.lastFrame, 'digite o percentual inteiro: 10 vira 10%');
    ui.stdin.write('10');
    await expectFrame(ui.lastFrame, 'Valor: 10%');

    await tabTo(ui, 'Motivo:');
    ui.stdin.write('promo');
    await expectFrame(ui.lastFrame, 'Motivo: promo');
    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(applyDiscount).toHaveBeenCalledWith(SALE_ID, {
        type: 'PERCENT',
        value: 10,
        reason: 'promo',
      });
    });
    expect(ui.onApplied).toHaveBeenCalledWith(discounted(22.41));
  });

  test('sem valor não chama a API: a dica fica no modal', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted(22.41) }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o valor do desconto');
    expect(applyDiscount).not.toHaveBeenCalled();
    expect(ui.onApplied).not.toHaveBeenCalled();
  });

  test('motivo vazio não chama a API (BR-04): o formulário pede o motivo', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted(14.9) }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    await tabTo(ui, 'Motivo:');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o motivo do desconto');
    expect(applyDiscount).not.toHaveBeenCalled();
  });

  test('403 do OPERADOR: mensagem no modal e o formulário continua utilizável', async () => {
    const applyDiscount = vi
      .fn<() => Promise<ApplyDiscountOutcome>>()
      .mockResolvedValueOnce({
        ok: false,
        kind: 'rejected',
        message: 'sem permissão para aplicar desconto',
      })
      .mockResolvedValueOnce({ ok: true, sale: discounted(14.9) });
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    await tabTo(ui, 'Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'sem permissão para aplicar desconto');
    expect(ui.lastFrame()).toContain('Tipo: [VALOR] · PERCENTUAL'); // o modal não fechou
    expect(ui.lastFrame()).toContain('Valor: R$ 10,00'); // nem perdeu o que foi digitado
    expect(ui.onApplied).not.toHaveBeenCalled();

    ui.stdin.write('\r'); // ENTER de novo: a segunda tentativa é a do dublê

    await vi.waitFor(() => {
      expect(ui.onApplied).toHaveBeenCalledWith(discounted(14.9));
    });
    expect(applyDiscount).toHaveBeenCalledTimes(2);
  });

  test('422 do limite da loja: a mensagem do servidor aparece no modal', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({
        ok: false,
        kind: 'rejected',
        message: 'desconto de 30% excede o limite de 10% da loja',
      }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    await tabTo(ui, 'Motivo:');
    await tabTo(ui, 'Tipo:');
    ui.stdin.write('\u001b[C'); // PERCENTUAL
    await expectFrame(ui.lastFrame, 'Tipo: VALOR · [PERCENTUAL]');
    await tabTo(ui, 'Valor:');

    ui.stdin.write('30');
    await expectFrame(ui.lastFrame, 'Valor: 30%');
    await tabTo(ui, 'Motivo:');
    ui.stdin.write('promoção');
    await expectFrame(ui.lastFrame, 'Motivo: promoção');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'desconto de 30% excede o limite de 10% da loja');
    expect(ui.onApplied).not.toHaveBeenCalled();
    expect(ui.onFailed).not.toHaveBeenCalled(); // recusa do servidor não bloqueia a operação
  });

  test('rede: aviso de retry no modal e o ENTER refaz a mesma chamada', async () => {
    const applyDiscount = vi
      .fn<() => Promise<ApplyDiscountOutcome>>()
      .mockResolvedValueOnce({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' },
      })
      .mockResolvedValueOnce({ ok: true, sale: discounted(14.9) });
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    await tabTo(ui, 'Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'falha ao aplicar o desconto — ENTER tenta de novo');
    expect(applyDiscount).toHaveBeenCalledTimes(1);

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(ui.onApplied).toHaveBeenCalledWith(discounted(14.9));
    });
    expect(applyDiscount).toHaveBeenCalledTimes(2);
    expect(applyDiscount).toHaveBeenLastCalledWith(SALE_ID, {
      type: 'VALUE',
      value: 10,
      reason: 'cliente pediu',
    });
  });

  test('uma chamada por vez: com o envio em voo o ENTER não aplica de novo', async () => {
    let release!: () => void;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    const applyDiscount = vi.fn(async (): Promise<ApplyDiscountOutcome> => {
      await pending;
      return { ok: true, sale: discounted(14.9) };
    });
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    await tabTo(ui, 'Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'aplicando…');
    ui.stdin.write('\r'); // segunda tecla com a chamada ainda em voo
    await expectFrame(ui.lastFrame, 'aplicando…');
    expect(applyDiscount).toHaveBeenCalledTimes(1);

    release();

    await vi.waitFor(() => {
      expect(ui.onApplied).toHaveBeenCalledWith(discounted(14.9));
    });
    expect(applyDiscount).toHaveBeenCalledTimes(1);
  });

  test('falha bloqueante (409/contrato) é do shell: `onFailed` com o problema', async () => {
    const problem = { status: 409, code: 'CONCURRENT_MODIFICATION', detail: 'venda mudou' };
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: false, kind: 'failed', problem }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    await tabTo(ui, 'Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');
    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(ui.onFailed).toHaveBeenCalledWith(problem);
    });
    expect(ui.onApplied).not.toHaveBeenCalled();
  });

  test('BACKSPACE corrige o campo em foco', async () => {
    const applyDiscount = vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({ ok: true, sale: discounted(14.9) }),
    );
    const ui = renderModal(apiStub({ applyDiscount }));

    ui.stdin.write('10005');
    await expectFrame(ui.lastFrame, 'Valor: R$ 100,05');
    ui.stdin.write('\x7f'); // BACKSPACE tira o último dígito
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');

    await tabTo(ui, 'Motivo:');
    ui.stdin.write('cliente pediu');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pediu');
    ui.stdin.write('\x7f');
    await expectFrame(ui.lastFrame, 'Motivo: cliente pedi');

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(applyDiscount).toHaveBeenCalledWith(SALE_ID, {
        type: 'VALUE',
        value: 10,
        reason: 'cliente pedi',
      });
    });
  });
});
