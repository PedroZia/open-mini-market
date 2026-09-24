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
});
