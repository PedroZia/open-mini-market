import { render } from 'ink-testing-library';
import { useReducer } from 'react';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddSaleItemOutcome,
  BarcodeLookupOutcome,
  CashRegistersOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
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
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
};

const FEIJAO: SaleItemView = {
  productId: 'p2',
  name: 'Feijão 1kg',
  quantity: 1,
  unitPrice: 8.9,
  lineTotal: 8.9,
};

/** Venda como o servidor devolveu: o fixture repete a conta dele; a tela só exibe (BR-12). */
function saleWithItems(items: SaleItemView[]): SaleView {
  const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
  return { id: SALE_ID, items, subtotal, discountAmount: 0, total: subtotal };
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
  };
}

/** Estado da venda como o server devolveria: 0 item é a venda ainda não criada (`sale: null`). */
function saleWith(count: number): SaleOpenState {
  const items: SaleItemView[] = PRODUCTS.slice(0, count).map((name, index) => ({
    productId: `p${index}`,
    name,
    quantity: 2,
    unitPrice: index + 3,
    lineTotal: (index + 3) * 2,
  }));

  return { ...saleOpen(), sale: count === 0 ? null : saleWithItems(items) };
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
    ...overrides,
  };
}

/** Shell mínimo do teste: o reducer real (1103) por trás da tela, como no App. */
function SaleHarness({ api, initial }: { api: TerminalApi; initial: SaleOpenState }) {
  const [state, dispatch] = useReducer(reduce, initial);

  if (state.kind !== 'saleOpen') {
    throw new Error(`estado inesperado no harness da venda: ${state.kind}`);
  }

  return <SaleScreen state={state} api={api} dispatch={dispatch} now={NOW} />;
}

function renderSale(api: TerminalApi = apiStub(), initial: SaleOpenState = saleOpen()) {
  return render(<SaleHarness api={api} initial={initial} />);
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
