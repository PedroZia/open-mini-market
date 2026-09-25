import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CashMovementKind,
  CashMovementOutcome,
  CashMovementView,
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
import { CashMovementModal } from './CashMovementModal';

/**
 * Modal da gaveta (1114) com a camada de API dublada: o que se testa é o formulário — máscara de
 * centavos, motivo obrigatório (BR-10), a **confirmação antes da API**, o saldo esperado que o
 * servidor devolve, o alerta de sangria acima do esperado e as recusas (403 do OPERADOR, 400 do
 * valor, 404/409 da sessão) ficando **no próprio modal** —, nunca o HTTP (esse é do
 * `@minimarket/api-client`/`terminalApi.test.ts`). O ESC não aparece aqui: quem cancela é o canal cru
 * do shell (testado no `App.test.tsx`), como no desconto (1111).
 *
 * Cada escrita espera o frame: o `useInput` do Ink só re-registra o callback com o estado do último
 * render, então duas teclas no mesmo tick seriam tratadas com o campo em foco antigo.
 */

const REGISTER_ID = 'r1';

/**
 * Movimento como o servidor o devolveu: a sangria de 10 sobre 150 deixa 140 — os dois números são
 * dele, da mesma transação (BR-12).
 */
function withdrawal(amount: number, before: number, after: number, aboveExpected = false): CashMovementView {
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

/** Dublê da camada de API: só a gaveta entra aqui; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  const unused = { status: 0, code: null, detail: 'não usado no modal da gaveta' };

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
      async (): Promise<SearchCustomersOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
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
      async (): Promise<CashMovementOutcome> => ({ ok: true, movement: withdrawal(10, 150, 140) }),
    ),
    supplyCash: vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: true,
        movement: { ...withdrawal(10, 150, 160), type: 'SUPPLY' },
      }),
    ),
    // o fechamento e o cancelamento (1115) são do shell: aqui só fecha o contrato
    cashSessionSummary: vi.fn(
      async (): Promise<CashSessionSummaryOutcome> => ({
        ok: false,
        problem: { status: 0, code: null, detail: 'não usado na gaveta' },
      }),
    ),
    closeCashSession: vi.fn(
      async (): Promise<CloseCashSessionOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na gaveta' },
      }),
    ),
    cancelSale: vi.fn(
      async (): Promise<CancelSaleOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'não usado na gaveta' },
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

type ModalUi = { lastFrame: () => string | undefined; stdin: { write: (data: string) => void } };

function renderModal(api: TerminalApi = apiStub(), kind: CashMovementKind = 'withdrawal') {
  const onClosed = vi.fn();
  const onFailed = vi.fn();
  const ui = render(
    <CashMovementModal
      registerId={REGISTER_ID}
      kind={kind}
      api={api}
      onClosed={onClosed}
      onFailed={onFailed}
    />,
  );

  return { ...ui, onClosed, onFailed };
}

/** Digita valor e motivo e para **antes** da confirmação: o ENTER que chama a API é do teste. */
async function fillFields(ui: ModalUi, reason = 'troco para o banco'): Promise<void> {
  ui.stdin.write('1000');
  await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
  ui.stdin.write('\t');
  await expectFrame(ui.lastFrame, '› Motivo:');
  ui.stdin.write(reason);
  await expectFrame(ui.lastFrame, `Motivo: ${reason}`);
}

describe('CashMovementModal', () => {
  test('abre na sangria com a máscara de centavos e o motivo ao lado', () => {
    const { lastFrame } = renderModal();
    const frame = lastFrame() ?? '';

    expect(frame).toContain('Sangria (F7)');
    expect(frame).toContain('› Valor: R$ 0,00');
    expect(frame).toContain('digite o valor em centavos: 1000 vira R$ 10,00');
    expect(frame).toContain('TAB troca o campo · ENTER confirma · ESC cancela');
  });

  test('o suprimento usa os rótulos do F8', async () => {
    const { lastFrame, stdin } = renderModal(apiStub(), 'supply');

    expect(lastFrame()).toContain('Suprimento (F8)');

    stdin.write('\r'); // formulário vazio: a dica segue o movimento pedido

    await expectFrame(lastFrame, 'informe o valor do suprimento');
  });

  test('sem valor não chama a API: a dica fica no modal', async () => {
    const withdrawCash = vi.fn<TerminalApi['withdrawCash']>();
    const ui = renderModal(apiStub({ withdrawCash }));

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o valor da sangria');
    expect(withdrawCash).not.toHaveBeenCalled();
    expect(ui.onClosed).not.toHaveBeenCalled();
  });

  test('motivo vazio não chama a API (BR-10): o formulário pede o motivo', async () => {
    const withdrawCash = vi.fn<TerminalApi['withdrawCash']>();
    const ui = renderModal(apiStub({ withdrawCash }));

    ui.stdin.write('1000');
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');
    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Motivo:');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'informe o motivo da sangria');
    expect(withdrawCash).not.toHaveBeenCalled();
  });

  test('a confirmação vem antes da API: nada sai antes do ENTER de confirmação', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: true, movement: withdrawal(10, 150, 140) }),
    );
    const ui = renderModal(apiStub({ withdrawCash }));

    await fillFields(ui);
    ui.stdin.write('\r'); // ENTER do formulário: só confirma

    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00? ENTER confirma · ESC cancela');
    expect(withdrawCash).not.toHaveBeenCalled();

    ui.stdin.write('\r'); // ENTER da confirmação: é este que chama a API

    await vi.waitFor(() => {
      expect(withdrawCash).toHaveBeenCalledTimes(1);
    });
    // a máscara é em centavos: `1000` vira R$ 10,00 na tela e `10` no corpo
    expect(withdrawCash).toHaveBeenCalledWith(
      REGISTER_ID,
      { amount: 10, reason: 'troco para o banco' },
      expect.any(String),
    );
  });

  test('sucesso mostra o esperado do servidor e o ENTER fecha o modal', async () => {
    const ui = renderModal();

    await fillFields(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'sangria registrada: R$ 10,00');
    expect(ui.lastFrame()).toContain('esperado antes: R$ 150,00');
    expect(ui.lastFrame()).toContain('esperado agora: R$ 140,00'); // saldo atualizado do servidor
    expect(ui.lastFrame()).not.toContain('ATENÇÃO');
    expect(ui.onClosed).not.toHaveBeenCalled(); // o ENTER que fecha é o de baixo

    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(ui.onClosed).toHaveBeenCalledTimes(1);
    });
  });

  test('sangria acima do esperado (aboveExpected) vira alerta visível: o servidor não bloqueia', async () => {
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: true,
        movement: withdrawal(200, 150, -50, true),
      }),
    );
    const ui = renderModal(apiStub({ withdrawCash }));

    await fillFields(ui, 'depósito no banco');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'ATENÇÃO: sangria acima do esperado — confira a gaveta');
    expect(ui.lastFrame()).toContain('esperado agora: R$ -50,00');
    expect(ui.onFailed).not.toHaveBeenCalled(); // o movimento foi registrado assim mesmo
  });

  test('suprimento registra na rota do F8 com o mesmo corpo', async () => {
    const supplyCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({
        ok: true,
        movement: { ...withdrawal(50, 150, 200), type: 'SUPPLY' },
      }),
    );
    const ui = renderModal(apiStub({ supplyCash }), 'supply');

    await fillFields(ui, 'fundo de troco');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar suprimento de R$ 10,00? ENTER confirma · ESC cancela');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'suprimento registrado: R$ 50,00');
    expect(supplyCash).toHaveBeenCalledWith(
      REGISTER_ID,
      { amount: 10, reason: 'fundo de troco' },
      expect.any(String),
    );
  });

  test('403 do OPERADOR: mensagem no modal e o formulário continua utilizável', async () => {
    const withdrawCash = vi
      .fn<TerminalApi['withdrawCash']>()
      .mockResolvedValueOnce({
        ok: false,
        kind: 'rejected',
        message: 'sem permissão para registrar sangria',
      })
      .mockResolvedValueOnce({ ok: true, movement: withdrawal(10, 150, 140) });
    const ui = renderModal(apiStub({ withdrawCash }));

    await fillFields(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'sem permissão para registrar sangria');
    expect(ui.lastFrame()).toContain('Valor: R$ 10,00'); // voltou ao formulário sem perder o digitado
    expect(ui.lastFrame()).toContain('Motivo: troco para o banco');
    expect(ui.onFailed).not.toHaveBeenCalled(); // recusa do servidor não bloqueia a operação

    ui.stdin.write('\r'); // confirma de novo: a segunda tentativa é a do dublê
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'sangria registrada: R$ 10,00');
    expect(withdrawCash).toHaveBeenCalledTimes(2);
  });

  test('rede: aviso de retry na confirmação e o ENTER refaz com a mesma chave', async () => {
    const withdrawCash = vi
      .fn<TerminalApi['withdrawCash']>()
      .mockResolvedValueOnce({
        ok: false,
        kind: 'retryable',
        problem: { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' },
      })
      .mockResolvedValueOnce({ ok: true, movement: withdrawal(10, 150, 140) });
    const ui = renderModal(apiStub({ withdrawCash }));

    await fillFields(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'falha ao registrar a sangria — ENTER tenta de novo');
    expect(withdrawCash).toHaveBeenCalledTimes(1);

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'sangria registrada: R$ 10,00');
    expect(withdrawCash).toHaveBeenCalledTimes(2);
    // a tentativa repetida é a mesma operação: repetir a chave evita sangrar duas vezes (§8)
    expect(withdrawCash.mock.calls[1]?.[2]).toBe(withdrawCash.mock.calls[0]?.[2]);
  });

  test('uma chamada por vez: com o envio em voo o ENTER não manda de novo', async () => {
    let release!: () => void;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    const withdrawCash = vi.fn<TerminalApi['withdrawCash']>(async () => {
      await pending;
      return { ok: true, movement: withdrawal(10, 150, 140) };
    });
    const ui = renderModal(apiStub({ withdrawCash }));

    await fillFields(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'enviando…');
    ui.stdin.write('\r'); // segunda tecla com a chamada ainda em voo
    await expectFrame(ui.lastFrame, 'enviando…');
    expect(withdrawCash).toHaveBeenCalledTimes(1);

    release();

    await expectFrame(ui.lastFrame, 'sangria registrada: R$ 10,00');
    expect(withdrawCash).toHaveBeenCalledTimes(1);
  });

  test('falha bloqueante (contrato) é do shell: `onFailed` com o problema', async () => {
    const problem = { status: 0, code: null, detail: 'sangria sem movimento na resposta' };
    const withdrawCash = vi.fn(
      async (): Promise<CashMovementOutcome> => ({ ok: false, kind: 'failed', problem }),
    );
    const ui = renderModal(apiStub({ withdrawCash }));

    await fillFields(ui);
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(ui.onFailed).toHaveBeenCalledWith(problem);
    });
    expect(ui.onClosed).not.toHaveBeenCalled();
  });

  test('BACKSPACE corrige o campo em foco', async () => {
    const withdrawCash = vi
      .fn<TerminalApi['withdrawCash']>()
      .mockResolvedValue({ ok: true, movement: withdrawal(100.05, 150, 49.95) });
    const ui = renderModal(apiStub({ withdrawCash }));

    ui.stdin.write('10005');
    await expectFrame(ui.lastFrame, 'Valor: R$ 100,05');
    ui.stdin.write('\x7f'); // BACKSPACE tira o último dígito
    await expectFrame(ui.lastFrame, 'Valor: R$ 10,00');

    ui.stdin.write('\t');
    await expectFrame(ui.lastFrame, '› Motivo:');
    ui.stdin.write('troco para o banco');
    await expectFrame(ui.lastFrame, 'Motivo: troco para o banco');
    ui.stdin.write('\r');
    await expectFrame(ui.lastFrame, 'confirmar sangria de R$ 10,00?');
    ui.stdin.write('\r');

    await vi.waitFor(() => {
      expect(withdrawCash).toHaveBeenCalledWith(
        REGISTER_ID,
        { amount: 10, reason: 'troco para o banco' },
        expect.any(String),
      );
    });
  });
});
