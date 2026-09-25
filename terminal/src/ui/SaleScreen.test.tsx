import { render } from 'ink-testing-library';
import { useReducer } from 'react';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddPaymentOutcome,
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
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
import { reduce } from '../core/reducer';
import type { SaleItemView, SaleOpenState, SaleView } from '../core/state';
import { SaleScreen } from './SaleScreen';

/**
 * Tela de venda: o layout do 1108 (0, 1 e 20 itens; 80×24) e o fluxo do bipe do 1109, com o
 * reducer real (1103) por trás e a camada de API dublada. O que se testa é a operação — bipe cria
 * a venda e adiciona o item, soma do mesmo produto, multiplicador `3*`, fila de bipes durante a
 * chamada, 404/422 com aviso, falha de rede com retry no ENTER e o bell —, nunca o HTTP (esse é do
 * `@minimarket/api-client`).
 *
 * O harness renderiza com 100 colunas, então quem garante o 80×24 é a asserção, não o terminal do
 * teste.
 */

/** Data fixa: a hora entra por prop justamente para o teste não depender do relógio. */
const NOW = new Date(2026, 8, 24, 14, 32, 5);

const OPERATOR = { id: 'u1', name: 'Ana Souza' };
const REGISTER = { id: 'r1', name: 'Caixa 01' };

const SALE_ID = 'sale-1';
const BARCODE = '7891000100103';

/** Produtos realistas da loja, na ordem em que o servidor devolveu os itens. */
const PRODUCTS = [
  'Arroz 5kg',
  'Feijão 1kg',
  'Açúcar 1kg',
  'Café 500g',
  'Óleo 900ml',
  'Leite 1L',
  'Pão de forma',
  'Ovos 12un',
  'Macarrão 500g',
  'Molho de tomate',
  'Queijo mussarela',
  'Presunto fatiado',
  'Banana prata',
  'Maçã gala',
  'Tomate',
  'Cebola',
  'Batata',
  'Sabão em pó',
  'Detergente',
  'Papel higiênico',
];

const ARROZ: SaleItemView = {
  productId: 'p1',
  name: 'Arroz 5kg',
  unit: 'UN',
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
};

const FEIJAO: SaleItemView = {
  productId: 'p2',
  name: 'Feijão 1kg',
  unit: 'UN',
  quantity: 1,
  unitPrice: 8.9,
  lineTotal: 8.9,
};

/** Banana é vendida a granel: o passo do `+`/`-` em KG é 0,1 (1110). */
const BANANA: SaleItemView = {
  productId: 'p3',
  name: 'Banana prata',
  unit: 'KG',
  quantity: 0.75,
  unitPrice: 6.99,
  lineTotal: 5.24,
};

/** Venda como o servidor devolveu: o fixture repete a conta dele; a tela só exibe (BR-12). */
function saleWithItems(items: SaleItemView[]): SaleView {
  const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
  return {
    id: SALE_ID,
    items,
    subtotal,
    discountAmount: 0,
    total: subtotal,
    paidAmount: 0,
    changeAmount: 0,
    payments: [],
    customerId: null,
  };
}

/** Estado em que o shell entrega a tela: caixa aberto e venda ainda não criada. */
function saleOpen(): SaleOpenState {
  return {
    kind: 'saleOpen',
    operator: OPERATOR,
    register: REGISTER,
    sessionId: 's1',
    sale: null,
    pendingScan: null,
    receipt: null,
  };
}

/** Estado da venda como o server devolveria: 0 item é a venda ainda não criada (`sale: null`). */
function saleWith(count: number): SaleOpenState {
  const items: SaleItemView[] = PRODUCTS.slice(0, count).map((name, index) => ({
    productId: `p${index}`,
    name,
    unit: 'UN',
    quantity: 2,
    unitPrice: index + 3,
    lineTotal: (index + 3) * 2,
  }));

  return { ...saleOpen(), sale: count === 0 ? null : saleWithItems(items) };
}

/** Estado da tela com a venda já criada e os itens exatos que o teste quer (1110). */
function saleOf(items: SaleItemView[]): SaleOpenState {
  return { ...saleOpen(), sale: saleWithItems(items) };
}

/** Estado da tela com o cliente que o servidor vinculou à venda (1112). */
function saleOfWithCustomer(customerId: string | null): SaleOpenState {
  return { ...saleOpen(), sale: { ...saleWithItems([ARROZ]), customerId } };
}

/** Dublê da camada de API: só a venda entra aqui; o resto existe para satisfazer o tipo. */
function apiStub(overrides: Partial<TerminalApi> = {}): TerminalApi {
  const unused = { status: 0, code: null, detail: 'não usado na tela de venda' };

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
      async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleWithItems([]) }),
    ),
    addSaleItem: vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleWithItems([ARROZ]) }),
    ),
    changeSaleItemQuantity: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([ARROZ]) }),
    ),
    removeSaleItem: vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([]) }),
    ),
    // o modal de desconto (1111) é do shell: aqui só fecha o contrato
    applyDiscount: vi.fn(
      async (): Promise<ApplyDiscountOutcome> => ({
        ok: false,
        kind: 'retryable',
        problem: unused,
      }),
    ),
    // o F6 (1112) é do shell: aqui só fecha o contrato
    searchCustomers: vi.fn(
      async (): Promise<SearchCustomersOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    linkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    unlinkCustomer: vi.fn(
      async (): Promise<CustomerSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    // o pagamento (1113) é da tela de pagamento: aqui só fecha o contrato
    addPayment: vi.fn(
      async (): Promise<AddPaymentOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    completeSale: vi.fn(
      async (): Promise<CompleteSaleOutcome> => ({ ok: false, kind: 'retryable', problem: unused }),
    ),
    ...overrides,
  };
}

/** Shell mínimo do teste: o reducer real (1103) por trás da tela, como no App. */
function SaleHarness({
  api,
  initial,
  customer = null,
}: {
  api: TerminalApi;
  initial: SaleOpenState;
  customer?: CustomerOption | null;
}) {
  const [state, dispatch] = useReducer(reduce, initial);

  if (state.kind !== 'saleOpen') {
    throw new Error(`estado inesperado no harness da venda: ${state.kind}`);
  }

  return <SaleScreen state={state} api={api} dispatch={dispatch} now={NOW} customer={customer} />;
}

function renderSale(
  api: TerminalApi = apiStub(),
  initial: SaleOpenState = saleOpen(),
  customer: CustomerOption | null = null,
) {
  return render(<SaleHarness api={api} initial={initial} customer={customer} />);
}

/** O render do Ink não é síncrono com o `stdin.write`: espera o frame alcançar o texto. */
async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(() => {
    expect(lastFrame()).toContain(text);
  });
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Linhas do frame: o harness do Ink entrega texto puro, então a largura da linha é a visível. */
function frameLines(frame: string): string[] {
  return frame.split('\n');
}

/** Aceite do 1108: o frame cabe nas 24 linhas e nenhuma linha passa de 80 colunas. */
function expectLayout(frame: string): void {
  const lines = frameLines(frame);

  expect(lines.length).toBeLessThanOrEqual(24);

  for (const line of lines) {
    expect(line.length).toBeLessThanOrEqual(80);
  }
}

/** Linhas destacadas da lista: as que começam com o marcador do último item. */
function highlighted(frame: string): string[] {
  return frameLines(frame).filter((line) => line.startsWith('› '));
}

describe('SaleScreen', () => {
  test('sem venda ainda: lista vazia, zeros de exibição e o convite ao primeiro bipe', () => {
    const { lastFrame } = renderSale(apiStub(), saleWith(0));
    const frame = lastFrame() ?? '';

    expect(frame).toContain('PDV minimercado · Caixa 01');
    expect(frame).toContain('Operador: Ana Souza · 14:32:05');
    expect(frame).toContain('bipar o primeiro item para iniciar a venda');
    expect(frame).toContain('Subtotal: R$ 0,00');
    expect(frame).toContain('Desconto: R$ 0,00');
    expect(frame).toContain('TOTAL: R$ 0,00');
    expectLayout(frame);
  });

  test('um item: aparece com o último (e único) destacado, com o valor do servidor', () => {
    const { lastFrame } = renderSale(apiStub(), saleWith(1));
    const frame = lastFrame() ?? '';

    expect(frame).toContain('› 2 x Arroz 5kg — R$ 6,00');
    expect(frame).toContain('TOTAL: R$ 6,00');
    expect(highlighted(frame)).toEqual(['› 2 x Arroz 5kg — R$ 6,00']);
    expectLayout(frame);
  });

  test('20 itens: a janela mostra os últimos, avisa quantos ficaram acima e destaca o último', () => {
    const { lastFrame } = renderSale(apiStub(), saleWith(20));
    const frame = lastFrame() ?? '';

    expect(frame).toContain('… 10 itens acima');
    expect(frame).toContain('Papel higiênico');
    expect(frame).not.toContain('Arroz 5kg'); // o primeiro item ficou acima da janela
    expect(frame).toContain('TOTAL: R$ 500,00');

    const marked = highlighted(frame);
    expect(marked).toHaveLength(1);
    expect(marked[0]).toContain('Papel higiênico');
    expectLayout(frame);
  });

  test('a barra de status mostra os atalhos da operação (§11.3)', () => {
    const { lastFrame } = renderSale(apiStub(), saleWith(0));
    const frame = lastFrame() ?? '';

    expect(frame).toContain('F1 Ajuda');
    expect(frame).toContain('F9 Pagamento');
    expect(frame).toContain('F11 Autoteste do leitor');
    expect(frame).toContain('F12 Trocar operador');
    expect(frame).toContain('↑↓ itens');
    expect(frame).toContain('DEL remove');
  });
});

describe('SaleScreen: bipe adiciona item (1109)', () => {
  test('bipe cria a venda na primeira leitura e adiciona o item com o código bruto (BR-14)', async () => {
    const calls: string[] = [];
    const createSale = vi.fn(async (): Promise<CreateSaleOutcome> => {
      calls.push('createSale');
      return { ok: true, sale: saleWithItems([]) };
    });
    const addSaleItem = vi.fn(async (): Promise<AddSaleItemOutcome> => {
      calls.push('addSaleItem');
      return { ok: true, sale: saleWithItems([ARROZ]) };
    });
    const ui = renderSale(apiStub({ createSale, addSaleItem }));

    ui.stdin.write(`${BARCODE}\r`);

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
    expect(calls).toEqual(['createSale', 'addSaleItem']); // a venda nasce antes do item
    expect(createSale).toHaveBeenCalledTimes(1);
    expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 });
    await expectFrame(ui.lastFrame, 'adicionado: 1 x Arroz 5kg — R$ 24,90');
  });

  test('segundo bipe do mesmo produto soma na mesma linha (quem soma é o servidor)', async () => {
    let call = 0;
    const addSaleItem = vi.fn(async (): Promise<AddSaleItemOutcome> => {
      call += 1;
      return {
        ok: true,
        sale: saleWithItems([call === 1 ? ARROZ : { ...ARROZ, quantity: 2, lineTotal: 49.8 }]),
      };
    });
    const ui = renderSale(apiStub({ addSaleItem }));

    ui.stdin.write(`${BARCODE}\r`);
    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg');

    ui.stdin.write(`${BARCODE}\r`);

    await expectFrame(ui.lastFrame, '› 2 x Arroz 5kg — R$ 49,80');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 49,80');
    await expectFrame(ui.lastFrame, 'adicionado: 2 x Arroz 5kg — R$ 49,80');
    expect(addSaleItem).toHaveBeenCalledTimes(2);
  });

  test('3* antes do bipe manda a quantidade na mesma chamada (1104a)', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: true,
        sale: saleWithItems([{ ...ARROZ, quantity: 3, lineTotal: 74.7 }]),
      }),
    );
    const ui = renderSale(apiStub({ addSaleItem }));

    ui.stdin.write('3');
    await sleep(60); // o '*' precisa chegar fora da rajada para valer de multiplicador
    ui.stdin.write('*');
    await sleep(60);
    ui.stdin.write(`${BARCODE}\r`);

    await expectFrame(ui.lastFrame, '› 3 x Arroz 5kg — R$ 74,70');
    expect(addSaleItem).toHaveBeenCalledWith(SALE_ID, { barcode: BARCODE, quantity: 3 });
  });

  test('bipe durante a chamada em andamento não se perde: a fila envia os dois na ordem', async () => {
    const BARCODE_2 = '7891000100202';
    let release!: () => void;
    const firstCall = new Promise<void>((resolve) => {
      release = resolve;
    });
    const sent: string[] = [];
    const addSaleItem = vi.fn(
      async (_saleId: string, item: { barcode: string; quantity: number }): Promise<AddSaleItemOutcome> => {
        sent.push(item.barcode);

        if (item.barcode === BARCODE) {
          await firstCall; // a resposta do primeiro só chega quando o teste liberar
          return { ok: true, sale: saleWithItems([ARROZ]) };
        }

        return { ok: true, sale: saleWithItems([ARROZ, FEIJAO]) };
      },
    );
    const ui = renderSale(apiStub({ addSaleItem }));

    ui.stdin.write(`${BARCODE}\r`);
    await vi.waitFor(() => {
      expect(sent).toEqual([BARCODE]); // o primeiro envio começou e está pendurado
    });

    ui.stdin.write(`${BARCODE_2}\r`); // chega durante a chamada: entra na fila local
    expect(sent).toEqual([BARCODE]); // e não vira uma segunda chamada em paralelo

    release();

    await expectFrame(ui.lastFrame, '› 1 x Feijão 1kg — R$ 8,90');
    expect(sent).toEqual([BARCODE, BARCODE_2]); // nenhum bipe perdido, na ordem
    expect(addSaleItem).toHaveBeenCalledTimes(2);
  });

  test('404 mostra o aviso, não toca o bell e o próximo bipe reusa a venda criada', async () => {
    const MISSING = '9999999999999';
    const createSale = vi.fn(
      async (): Promise<CreateSaleOutcome> => ({ ok: true, sale: saleWithItems([]) }),
    );
    const addSaleItem = vi.fn(
      async (_saleId: string, item: { barcode: string; quantity: number }): Promise<AddSaleItemOutcome> =>
        item.barcode === MISSING
          ? { ok: false, kind: 'notFound', barcode: MISSING }
          : { ok: true, sale: saleWithItems([ARROZ]) },
    );
    const ui = renderSale(apiStub({ createSale, addSaleItem }));

    ui.stdin.write(`${MISSING}\r`);

    await expectFrame(
      ui.lastFrame,
      `produto não encontrado: ${MISSING} — cadastro rápido ainda não disponível`,
    );
    expect(ui.frames.join('')).not.toContain('\u0007'); // bipe recusado não é bipe aceito
    expect(ui.lastFrame()).toContain('bipar o primeiro item para iniciar a venda'); // a venda segue vazia e usável

    ui.stdin.write(`${BARCODE}\r`);

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(createSale).toHaveBeenCalledTimes(1); // a venda vazia do 404 foi reaproveitada
    expect(addSaleItem).toHaveBeenLastCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 });
  });

  test('422 mostra a mensagem clara e a venda continua utilizável', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({
        ok: false,
        kind: 'rejected',
        barcode: BARCODE,
        message: 'produto desativado no cadastro: 789 — fale com o gerente',
      }),
    );
    const ui = renderSale(apiStub({ addSaleItem }));

    ui.stdin.write(`${BARCODE}\r`);

    await expectFrame(ui.lastFrame, 'produto desativado no cadastro: 789 — fale com o gerente');
    expect(ui.lastFrame()).toContain('bipar o primeiro item para iniciar a venda');
  });

  test('falha de rede mantém o bipe e o ENTER refaz a chamada sob demanda', async () => {
    let call = 0;
    const addSaleItem = vi.fn(async (): Promise<AddSaleItemOutcome> => {
      call += 1;

      return call === 1
        ? {
            ok: false,
            kind: 'retryable',
            problem: { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' },
          }
        : { ok: true, sale: saleWithItems([ARROZ]) };
    });
    const ui = renderSale(apiStub({ addSaleItem }));

    ui.stdin.write(`${BARCODE}\r`);
    await expectFrame(ui.lastFrame, 'falha ao enviar o bipe — ENTER tenta de novo');
    expect(addSaleItem).toHaveBeenCalledTimes(1); // sem retry automático: o ENTER é sob demanda

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(addSaleItem).toHaveBeenCalledTimes(2);
    expect(addSaleItem).toHaveBeenLastCalledWith(SALE_ID, { barcode: BARCODE, quantity: 1 });
  });

  test('bipe aceito toca o bell e confirma no rodapé o item que o servidor devolveu', async () => {
    const ui = renderSale(apiStub());

    expect(ui.frames.join('')).not.toContain('\u0007');

    ui.stdin.write(`${BARCODE}\r`);

    await expectFrame(ui.lastFrame, 'adicionado: 1 x Arroz 5kg — R$ 24,90');
    expect(ui.frames.join('')).toContain('\u0007');
  });
});

describe('SaleScreen: alterar quantidade e remover item (1110)', () => {
  test('`+` manda o PATCH com a quantidade nova e os totais são os da resposta do servidor', async () => {
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({
        ok: true,
        sale: saleWithItems([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]),
      }),
    );
    const ui = renderSale(apiStub({ changeSaleItemQuantity }), saleOf([ARROZ]));

    ui.stdin.write('+');

    await expectFrame(ui.lastFrame, '› 2 x Arroz 5kg — R$ 49,80');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 49,80');
    expect(changeSaleItemQuantity).toHaveBeenCalledWith(SALE_ID, 'p1', 2);
    await expectFrame(ui.lastFrame, 'quantidade: 2 x Arroz 5kg — R$ 49,80');
  });

  test('`-` manda o PATCH com a quantidade menor, também absoluta', async () => {
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({
        ok: true,
        sale: saleWithItems([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]),
      }),
    );
    const ui = renderSale(
      apiStub({ changeSaleItemQuantity }),
      saleOf([{ ...ARROZ, quantity: 3, lineTotal: 74.7 }]),
    );

    ui.stdin.write('-');

    await expectFrame(ui.lastFrame, '› 2 x Arroz 5kg — R$ 49,80');
    expect(changeSaleItemQuantity).toHaveBeenCalledWith(SALE_ID, 'p1', 2);
  });

  test('em KG o passo é 0,1 (venda a granel) e a quantidade vai sem ruído de ponto flutuante', async () => {
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({
        ok: true,
        sale: saleWithItems([{ ...BANANA, quantity: 0.85, lineTotal: 5.94 }]),
      }),
    );
    const ui = renderSale(apiStub({ changeSaleItemQuantity }), saleOf([BANANA]));

    ui.stdin.write('+');

    await expectFrame(ui.lastFrame, '› 0,850 x Banana prata — R$ 5,94');
    expect(changeSaleItemQuantity).toHaveBeenCalledWith(SALE_ID, 'p3', 0.85);
  });

  test('`-` que zeraria não chama a API: quem remove é o DEL', async () => {
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([ARROZ]) }),
    );
    const ui = renderSale(apiStub({ changeSaleItemQuantity }), saleOf([ARROZ]));

    ui.stdin.write('-');

    await expectFrame(ui.lastFrame, 'use DEL para remover o item');
    expect(changeSaleItemQuantity).not.toHaveBeenCalled();
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
  });

  test('uma mutação por vez: com o PATCH em voo a tecla é ignorada e o rodapé mostra "enviando…"', async () => {
    let release!: () => void;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => {
        await pending;
        return { ok: true, sale: saleWithItems([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]) };
      },
    );
    const ui = renderSale(apiStub({ changeSaleItemQuantity }), saleOf([ARROZ]));

    ui.stdin.write('+');
    await expectFrame(ui.lastFrame, 'enviando…');

    ui.stdin.write('+'); // segunda tecla com a primeira ainda em voo
    expect(changeSaleItemQuantity).toHaveBeenCalledTimes(1);

    release();

    await expectFrame(ui.lastFrame, '› 2 x Arroz 5kg — R$ 49,80');
    expect(changeSaleItemQuantity).toHaveBeenCalledTimes(1);
    expect(ui.lastFrame()).not.toContain('enviando…');
  });

  test('DEL abre a confirmação e nada vai à API antes do ENTER', async () => {
    const removeSaleItem = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([ARROZ]) }),
    );
    const ui = renderSale(apiStub({ removeSaleItem }), saleOf([ARROZ, FEIJAO]));

    ui.stdin.write('\x1b[3~'); // DEL

    await expectFrame(ui.lastFrame, 'remover Feijão 1kg? ENTER confirma · ESC cancela');
    expectLayout(ui.lastFrame() ?? ''); // o overlay não estoura as 24 linhas
    expect(removeSaleItem).not.toHaveBeenCalled();
    expect(ui.lastFrame()).toContain('› 1 x Feijão 1kg — R$ 8,90'); // a venda intacta

    ui.stdin.write('\r'); // ENTER confirma

    await expectFrame(ui.lastFrame, 'removido: Feijão 1kg');
    expect(removeSaleItem).toHaveBeenCalledWith(SALE_ID, 'p2');
  });

  test('ESC cancela a confirmação sem chamar a API e sem mexer na venda', async () => {
    const removeSaleItem = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([ARROZ]) }),
    );
    const ui = renderSale(apiStub({ removeSaleItem }), saleOf([ARROZ, FEIJAO]));

    ui.stdin.write('\x1b[3~');
    await expectFrame(ui.lastFrame, 'remover Feijão 1kg? ENTER confirma · ESC cancela');

    ui.stdin.write('\x1b'); // ESC cancela

    // o Ink segura o ESC sozinho por alguns ms antes de entregá-lo (pode ser o início de uma sequência)
    await vi.waitFor(() => {
      expect(ui.lastFrame()).not.toContain('remover Feijão 1kg?');
    });
    expect(ui.lastFrame()).toContain('› 1 x Feijão 1kg — R$ 8,90');
    expect(removeSaleItem).not.toHaveBeenCalled();
  });

  test('remover o único item deixa a venda vazia (estado vazio do 1108)', async () => {
    const removeSaleItem = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([]) }),
    );
    const ui = renderSale(apiStub({ removeSaleItem }), saleOf([ARROZ]));

    ui.stdin.write('\x1b[3~');
    await expectFrame(ui.lastFrame, 'remover Arroz 5kg? ENTER confirma · ESC cancela');

    ui.stdin.write('\r');

    await expectFrame(ui.lastFrame, 'bipar o primeiro item para iniciar a venda');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 0,00');
    expect(highlighted(ui.lastFrame() ?? '')).toEqual([]); // a lista ficou vazia
  });

  test('durante a confirmação o leitor não vira item: a rajada morre no modal', async () => {
    const addSaleItem = vi.fn(
      async (): Promise<AddSaleItemOutcome> => ({ ok: true, sale: saleWithItems([ARROZ, FEIJAO]) }),
    );
    const removeSaleItem = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: true, sale: saleWithItems([ARROZ]) }),
    );
    const ui = renderSale(apiStub({ addSaleItem, removeSaleItem }), saleOf([ARROZ, FEIJAO]));

    ui.stdin.write('\x1b[3~');
    await expectFrame(ui.lastFrame, 'remover Feijão 1kg? ENTER confirma · ESC cancela');

    ui.stdin.write(`${BARCODE}\r`); // bipe com o modal à vista

    expect(addSaleItem).not.toHaveBeenCalled();
    expect(ui.lastFrame()).toContain('remover Feijão 1kg?'); // o overlay segue aberto

    ui.stdin.write('\x1b'); // ESC cancela

    await expectFrame(ui.lastFrame, '› 1 x Feijão 1kg — R$ 8,90');
    expect(addSaleItem).not.toHaveBeenCalled(); // o bipe engolido não reaparece depois
    expect(removeSaleItem).not.toHaveBeenCalled();
  });

  test('as setas movem o destaque e nas pontas não dão a volta (clamp)', async () => {
    const ui = renderSale(apiStub(), saleOf([ARROZ, FEIJAO, BANANA]));

    // sem seta, o destaque é o último item — o comportamento do 1108
    expect(highlighted(ui.lastFrame() ?? '')).toEqual(['› 0,750 x Banana prata — R$ 5,24']);

    ui.stdin.write('\x1b[A'); // ↑
    await expectFrame(ui.lastFrame, '› 1 x Feijão 1kg — R$ 8,90');
    expect(highlighted(ui.lastFrame() ?? '')).toHaveLength(1);

    ui.stdin.write('\x1b[B'); // ↓ volta para o último
    await expectFrame(ui.lastFrame, '› 0,750 x Banana prata — R$ 5,24');

    ui.stdin.write('\x1b[B'); // ↓ no último: clamp
    ui.stdin.write('\x1b[A');
    ui.stdin.write('\x1b[A');
    ui.stdin.write('\x1b[A'); // ↑ no primeiro: clamp

    await expectFrame(ui.lastFrame, '› 1 x Arroz 5kg — R$ 24,90');
    expect(highlighted(ui.lastFrame() ?? '')).toEqual(['› 1 x Arroz 5kg — R$ 24,90']);
  });

  test('404 SALE_ITEM_NOT_FOUND avisa e mantém a venda como está', async () => {
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'notFound' }),
    );
    const ui = renderSale(apiStub({ changeSaleItemQuantity }), saleOf([ARROZ]));

    ui.stdin.write('+');

    await expectFrame(ui.lastFrame, 'item já não está na venda: Arroz 5kg');
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90');
    expect(ui.lastFrame()).toContain('TOTAL: R$ 24,90');
  });

  test('falha de rede mantém o item e a mesma tecla tenta de novo', async () => {
    let call = 0;
    const changeSaleItemQuantity = vi.fn(
      async (): Promise<SaleItemMutationOutcome> => {
        call += 1;

        return call === 1
          ? {
              ok: false,
              kind: 'retryable',
              problem: { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' },
            }
          : { ok: true, sale: saleWithItems([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }]) };
      },
    );
    const ui = renderSale(apiStub({ changeSaleItemQuantity }), saleOf([ARROZ]));

    ui.stdin.write('+');
    await expectFrame(ui.lastFrame, 'falha ao falar com o servidor — +/- tenta de novo');
    expect(ui.lastFrame()).toContain('› 1 x Arroz 5kg — R$ 24,90'); // o item fica como estava

    ui.stdin.write('+');

    await expectFrame(ui.lastFrame, '› 2 x Arroz 5kg — R$ 49,80');
    expect(changeSaleItemQuantity).toHaveBeenCalledTimes(2);
  });

  test('409 SALE_NOT_OPEN bloqueia na tela de erro sem perder a venda (a tela despacha apiFailed)', async () => {
    const problem = { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' };
    const dispatch = vi.fn();
    const api = apiStub({
      changeSaleItemQuantity: vi.fn(
        async (): Promise<SaleItemMutationOutcome> => ({ ok: false, kind: 'failed', problem }),
      ),
    });
    const ui = render(
      <SaleScreen
        state={saleOf([ARROZ])}
        api={api}
        dispatch={dispatch}
        now={NOW}
        customer={null}
      />,
    );

    ui.stdin.write('+');

    await vi.waitFor(() => {
      expect(dispatch).toHaveBeenCalledWith({ type: 'apiFailed', problem });
    });
  });
});

describe('SaleScreen: cliente no cabeçalho (1112)', () => {
  const MARIA: CustomerOption = { id: 'c1', name: 'Maria Silva', taxId: '12345678900' };

  test('o vínculo do servidor aparece com o nome que a busca local capturou', () => {
    const { lastFrame } = renderSale(apiStub(), saleOfWithCustomer(MARIA.id), MARIA);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('Cliente: Maria Silva');
    expect(frame).toContain('Operador: Ana Souza · 14:32:05');
    expectLayout(frame); // a linha nova não estoura as 24
  });

  test('nome local sem o vínculo do servidor não aparece: o vínculo é o `customerId` da venda', () => {
    const anonima = renderSale(apiStub(), saleOfWithCustomer(null), MARIA);
    const outra = renderSale(
      apiStub(),
      saleOfWithCustomer('c9'), // vinculado a outro cliente
      MARIA,
    );

    expect(anonima.lastFrame()).not.toContain('Cliente:');
    expect(outra.lastFrame()).not.toContain('Cliente:');
  });
});
