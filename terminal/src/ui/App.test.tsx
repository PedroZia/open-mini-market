import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import type {
  AddSaleItemOutcome,
  ApplyDiscountOutcome,
  BarcodeLookupOutcome,
  CashRegisterOption,
  CashRegistersOutcome,
  CreateSaleOutcome,
  CurrentCashSessionOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  SaleItemMutationOutcome,
  TerminalApi,
} from '../api/terminalApi';
import type { SaleView } from '../core/state';
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
  };
}

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
    resolveBarcode: vi.fn(
      async (): Promise<BarcodeLookupOutcome> => ({
        ok: true,
        product: { name: 'Arroz 5kg', price: 24.9, quantity: null },
      }),
    ),
    createSale: vi.fn(
      async (): Promise<CreateSaleOutcome> => ({
        ok: true,
        sale: { id: 'sale-1', items: [], subtotal: 0, discountAmount: 0, total: 0 },
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
        product: { name: 'Banana prata', price: 6.99, quantity: 0.75 },
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
        sale: { id: 'sale-1', items: [], subtotal: 0, discountAmount: 0, total: 0 },
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
