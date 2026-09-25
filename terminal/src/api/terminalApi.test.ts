import { ApiError, type ApiClient } from '@minimarket/api-client';
import { beforeEach, describe, expect, test, vi } from 'vitest';

import { clearToken, getToken } from './session';
import { createTerminalApi } from './terminalApi';

/**
 * Camada de API da TUI (1106) com um client dublê: o que se testa aqui é a tradução do contrato —
 * login guardando o token só na sessão em memória, recusa de credencial separada de falha
 * bloqueante e a lista de caixas normalizada para a tela. Sem HTTP e sem PostgreSQL (o contrato em
 * si é testado no `@minimarket/api-client` e no backend).
 */

type Handlers = {
  get?: (path: string) => Promise<unknown>;
  post?: (path: string, body?: unknown) => Promise<unknown>;
};

/** Client dublê: só GET e POST entram na entrada do PDV; o resto é erro de teste. */
function stubClient(handlers: Handlers): ApiClient {
  const unused = (method: string) => () => Promise.reject(new Error(`${method} não usado aqui`));

  return {
    get: <T>(path: string) =>
      (handlers.get?.(path) ?? Promise.reject(new Error(`GET inesperado: ${path}`))) as Promise<T>,
    post: <T>(path: string, body?: unknown) =>
      (handlers.post?.(path, body) ?? Promise.reject(new Error(`POST inesperado: ${path}`))) as
        Promise<T>,
    put: unused('PUT') as ApiClient['put'],
    patch: unused('PATCH') as ApiClient['patch'],
    delete: unused('DELETE') as ApiClient['delete'],
  };
}

const LOGIN_RESPONSE = {
  token: 'tok-123',
  expiresAt: '2026-09-24T12:00:00Z',
  user: { id: 'u1', username: 'ana', displayName: 'Ana Souza' },
  roles: ['OPERADOR'],
  permissions: ['cash.read'],
};

describe('createTerminalApi', () => {
  beforeEach(() => {
    clearToken();
  });

  test('login guarda o token na sessão e devolve o operador, sem cashRegisterId no corpo', async () => {
    const post = vi.fn(async () => LOGIN_RESPONSE);
    const api = createTerminalApi(stubClient({ post }));

    const outcome = await api.login('ana', 'segredo');

    expect(outcome).toEqual({ ok: true, operator: { id: 'u1', name: 'Ana Souza' } });
    expect(getToken()).toBe('tok-123');
    expect(post).toHaveBeenCalledWith('/api/v1/auth/login', { username: 'ana', password: 'segredo' });
  });

  test('login com o caixa escolhido leva o cashRegisterId no corpo', async () => {
    const post = vi.fn(async () => LOGIN_RESPONSE);
    const api = createTerminalApi(stubClient({ post }));

    await api.login('ana', 'segredo', 'r2');

    expect(post).toHaveBeenCalledWith('/api/v1/auth/login', {
      username: 'ana',
      password: 'segredo',
      cashRegisterId: 'r2',
    });
  });

  test('sem displayName, o operador assume o username', async () => {
    const api = createTerminalApi(
      stubClient({ post: async () => ({ token: 'tok', user: { id: 'u1', username: 'ana' } }) }),
    );

    expect(await api.login('ana', 'segredo')).toEqual({
      ok: true,
      operator: { id: 'u1', name: 'ana' },
    });
  });

  test('200 sem token é falha: sem token não há sessão', async () => {
    const api = createTerminalApi(stubClient({ post: async () => ({ user: { id: 'u1' } }) }));

    const outcome = await api.login('ana', 'segredo');

    expect(outcome).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'login sem token ou operador na resposta' },
    });
    expect(getToken()).toBeNull();
  });

  test('401 INVALID_CREDENTIALS é recusa de credencial, não falha bloqueante', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(401, {
            code: 'INVALID_CREDENTIALS',
            detail: 'usuário ou senha inválidos',
          });
        },
      }),
    );

    expect(await api.login('ana', 'errada')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'usuário ou senha inválidos',
    });
    expect(getToken()).toBeNull();
  });

  test('423 ACCOUNT_LOCKED também é recusa de credencial', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(423, { code: 'ACCOUNT_LOCKED', detail: 'conta bloqueada até 12:30' });
        },
      }),
    );

    expect(await api.login('ana', 'segredo')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'conta bloqueada até 12:30',
    });
  });

  test('400 do cashRegisterId é recusa: o operador escolhe outro caixa, não é falha bloqueante', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(400, {
            code: 'VALIDATION_ERROR',
            detail: 'caixa não encontrado ou inativo',
          });
        },
      }),
    );

    expect(await api.login('ana', 'segredo', 'r9')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'caixa não encontrado ou inativo',
    });
  });

  test('logout revoga a sessão e esquece o token local', async () => {
    const post = vi.fn(async (path: string) => (path === '/api/v1/auth/logout' ? undefined : LOGIN_RESPONSE));
    const api = createTerminalApi(stubClient({ post }));

    await api.login('ana', 'segredo');
    await api.logout();

    expect(post).toHaveBeenLastCalledWith('/api/v1/auth/logout', undefined);
    expect(getToken()).toBeNull();
  });

  test('falha na revogação não rejeita e ainda limpa o token: o login seguinte sai anônimo', async () => {
    const post = vi.fn(async (path: string) => {
      if (path === '/api/v1/auth/logout') {
        throw new ApiError(500, { code: 'INTERNAL_ERROR', detail: 'falha inesperada' });
      }
      return LOGIN_RESPONSE;
    });
    const api = createTerminalApi(stubClient({ post }));

    await api.login('ana', 'segredo');
    await expect(api.logout()).resolves.toBeUndefined();

    expect(getToken()).toBeNull();
  });

  test('5xx é falha bloqueante com o problem+json do servidor', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(500, { code: 'INTERNAL_ERROR', detail: 'falha inesperada' });
        },
      }),
    );

    expect(await api.login('ana', 'segredo')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 500, code: 'INTERNAL_ERROR', detail: 'falha inesperada' },
    });
  });

  test('falha de rede vira falha sem status HTTP', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await api.login('ana', 'segredo')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('lista de caixas sai normalizada para a tela', async () => {
    const api = createTerminalApi(
      stubClient({
        get: async () => [
          { id: 'r1', code: '01', name: 'Caixa principal', status: 'CLOSED', operatorName: null },
          { id: 'r2', code: '02', name: 'Caixa do fundo', status: 'OPEN', operatorName: 'Maria' },
          { code: '03', name: 'sem id' },
        ],
      }),
    );

    expect(await api.listCashRegisters()).toEqual({
      ok: true,
      registers: [
        { id: 'r1', code: '01', name: 'Caixa principal', open: false, operatorName: null },
        { id: 'r2', code: '02', name: 'Caixa do fundo', open: true, operatorName: 'Maria' },
      ],
    });
  });

  test('403 na lista de caixas é falha bloqueante com o code', async () => {
    const api = createTerminalApi(
      stubClient({
        get: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão cash.read' });
        },
      }),
    );

    expect(await api.listCashRegisters()).toEqual({
      ok: false,
      problem: { status: 403, code: 'ACCESS_DENIED', detail: 'permissão cash.read' },
    });
  });

  test('abertura devolve a sessão criada e envia o fundo de troco no corpo', async () => {
    const post = vi.fn(async () => ({ id: 's1', status: 'OPEN', openingAmount: 12.5 }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.openCashRegister('r1', 12.5)).toEqual({ ok: true, sessionId: 's1' });
    expect(post).toHaveBeenCalledWith('/api/v1/cash-registers/r1/open', { openingAmount: 12.5 });
  });

  test('201 sem id de sessão é falha: sem sessão a venda não tem onde acontecer', async () => {
    const api = createTerminalApi(stubClient({ post: async () => ({ status: 'OPEN' }) }));

    expect(await api.openCashRegister('r1', 0)).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'abertura sem sessão na resposta' },
    });
  });

  test('409 CASH_REGISTER_ALREADY_OPEN é caixa já aberto, não falha bloqueante', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(409, {
            code: 'CASH_REGISTER_ALREADY_OPEN',
            detail: 'caixa já está aberto',
          });
        },
      }),
    );

    expect(await api.openCashRegister('r1', 50)).toEqual({ ok: false, kind: 'alreadyOpen' });
  });

  test('outro 409 da abertura continua sendo falha bloqueante', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(409, { code: 'CONFLICT', detail: 'estado mudou' });
        },
      }),
    );

    expect(await api.openCashRegister('r1', 50)).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 409, code: 'CONFLICT', detail: 'estado mudou' },
    });
  });

  test('sessão corrente devolve o id que a venda vai usar', async () => {
    const get = vi.fn(async () => ({ sessionId: 's9', status: 'OPEN' }));
    const api = createTerminalApi(stubClient({ get }));

    expect(await api.currentCashSession('r1')).toEqual({ ok: true, sessionId: 's9' });
    expect(get).toHaveBeenCalledWith('/api/v1/cash-registers/r1/current-session');
  });

  test('caixa sem sessão aberta (404) é falha bloqueante', async () => {
    const api = createTerminalApi(
      stubClient({
        get: async () => {
          throw new ApiError(404, { code: 'CASH_SESSION_NOT_OPEN', detail: 'caixa sem sessão aberta' });
        },
      }),
    );

    expect(await api.currentCashSession('r1')).toEqual({
      ok: false,
      problem: { status: 404, code: 'CASH_SESSION_NOT_OPEN', detail: 'caixa sem sessão aberta' },
    });
  });

  test('bipe devolve o produto do servidor e a quantidade sugerida da etiqueta', async () => {
    const get = vi.fn(async () => ({
      id: 'p1',
      barcode: '7891000100103',
      name: 'Arroz 5kg',
      price: 24.9,
      unit: 'UN',
      quantity: null,
    }));
    const api = createTerminalApi(stubClient({ get }));

    expect(await api.resolveBarcode('7891000100103')).toEqual({
      ok: true,
      product: { name: 'Arroz 5kg', price: 24.9, quantity: null },
    });
    expect(get).toHaveBeenCalledWith('/api/v1/products/barcode/7891000100103');
  });

  test('etiqueta de balança sugere a quantidade e o código vai codificado no caminho', async () => {
    const get = vi.fn(async () => ({ name: 'Banana prata', price: 6.99, quantity: 0.75 }));
    const api = createTerminalApi(stubClient({ get }));

    expect(await api.resolveBarcode('20004200012 34')).toEqual({
      ok: true,
      product: { name: 'Banana prata', price: 6.99, quantity: 0.75 },
    });
    expect(get).toHaveBeenCalledWith('/api/v1/products/barcode/20004200012%2034');
  });

  test('produto não encontrado (404) volta como recusa com o problem+json', async () => {
    const api = createTerminalApi(
      stubClient({
        get: async () => {
          throw new ApiError(404, {
            code: 'PRODUCT_NOT_FOUND',
            detail: 'produto com código de barras 789 não encontrado',
          });
        },
      }),
    );

    expect(await api.resolveBarcode('789')).toEqual({
      ok: false,
      problem: {
        status: 404,
        code: 'PRODUCT_NOT_FOUND',
        detail: 'produto com código de barras 789 não encontrado',
      },
    });
  });
});
