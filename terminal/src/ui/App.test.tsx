import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import type {
  CashRegisterOption,
  CashRegistersOutcome,
  CurrentCashSessionOutcome,
  LoginOutcome,
  OpenCashRegisterOutcome,
  TerminalApi,
} from '../api/terminalApi';
import { App } from './App';

/**
 * Shell + entrada do operador (1106) e abertura de caixa (1107) pelo `ink-testing-library`, com a
 * camada de API dublada: o que se testa é o fluxo da tela — login, escolha do caixa, abertura,
 * erro que fica na tela e erro que bloqueia com volta —, nunca o HTTP (esse é do
 * `@minimarket/api-client`).
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

    await expectFrame(lastFrame, 'tela do passo 1108');
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

    await expectFrame(lastFrame, 'tela do passo 1108');
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
