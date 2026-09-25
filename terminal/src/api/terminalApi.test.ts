import { ApiError, type ApiClient, type RequestOptions } from '@minimarket/api-client';
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
  post?: (path: string, body?: unknown, options?: RequestOptions) => Promise<unknown>;
  put?: (path: string, body?: unknown, options?: RequestOptions) => Promise<unknown>;
  patch?: (path: string, body?: unknown, options?: RequestOptions) => Promise<unknown>;
  delete?: (path: string, options?: RequestOptions) => Promise<unknown>;
};

/** Client dublê: só as rotas da entrada do PDV entram; o resto é erro de teste. */
function stubClient(handlers: Handlers): ApiClient {
  return {
    get: <T>(path: string) => orFail(handlers.get?.(path), `GET inesperado: ${path}`) as Promise<T>,
    post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      orFail(
        options === undefined ? handlers.post?.(path, body) : handlers.post?.(path, body, options),
        `POST inesperado: ${path}`,
      ) as Promise<T>,
    put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      orFail(
        options === undefined ? handlers.put?.(path, body) : handlers.put?.(path, body, options),
        `PUT inesperado: ${path}`,
      ) as Promise<T>,
    patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      orFail(
        options === undefined ? handlers.patch?.(path, body) : handlers.patch?.(path, body, options),
        `PATCH inesperado: ${path}`,
      ) as Promise<T>,
    delete: <T>(path: string, options?: RequestOptions) =>
      orFail(
        options === undefined ? handlers.delete?.(path) : handlers.delete?.(path, options),
        `DELETE inesperado: ${path}`,
      ) as Promise<T>,
  };
}

/**
 * Handler ausente é erro de teste; a chamada vai com os argumentos que a rota mandou — sem
 * `options` o espião não recebe um `undefined` a mais e as asserções continuam exatas.
 */
function orFail(call: Promise<unknown> | undefined, message: string): Promise<unknown> {
  return call ?? Promise.reject(new Error(message));
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

  test('abrir a venda (201) sai como SaleView vazia com os totais do servidor', async () => {
    const post = vi.fn(async () => ({
      id: 'sale-1',
      number: 42,
      status: 'OPEN',
      subtotal: 0,
      discountAmount: 0,
      total: 0,
      itemCount: 0,
    }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.createSale()).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [],
        subtotal: 0,
        discountAmount: 0,
        total: 0,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: null,
      },
    });
    expect(post).toHaveBeenCalledWith('/api/v1/sales', undefined);
  });

  test('201 sem id de venda é falha bloqueante: sem venda não há onde incluir item', async () => {
    const api = createTerminalApi(stubClient({ post: async () => ({ status: 'OPEN' }) }));

    expect(await api.createSale()).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'venda aberta sem id na resposta' },
    });
  });

  test('falha de rede ao abrir a venda é transitória: o bipe fica para o retry', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await api.createSale()).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('adicionar item manda o código bruto e devolve a venda inteira do servidor', async () => {
    const post = vi.fn(async () => ({
      id: 'sale-1',
      status: 'OPEN',
      subtotal: 49.8,
      discountAmount: 0,
      total: 49.8,
      items: [
        { productId: 'p1', barcode: '7891000100103', name: 'Arroz 5kg', unit: 'UN', unitPrice: 24.9, quantity: 2, lineTotal: 49.8 },
      ],
    }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.addSaleItem('sale-1', { barcode: '7891000100103', quantity: 2 })).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [
          { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 2, unitPrice: 24.9, lineTotal: 49.8 },
        ],
        subtotal: 49.8,
        discountAmount: 0,
        total: 49.8,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: null,
      },
    });
    expect(post).toHaveBeenCalledWith('/api/v1/sales/sale-1/items', {
      barcode: '7891000100103',
      quantity: 2,
    });
  });

  test('404 PRODUCT_NOT_FOUND é desfecho próprio: a venda continua, a tela avisa', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(404, {
            code: 'PRODUCT_NOT_FOUND',
            detail: 'produto com código de barras 789 não encontrado',
          });
        },
      }),
    );

    expect(await api.addSaleItem('sale-1', { barcode: '789', quantity: 1 })).toEqual({
      ok: false,
      kind: 'notFound',
      barcode: '789',
    });
  });

  test('404 SALE_NOT_FOUND não é desfecho de produto: segue como falha bloqueante', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(404, { code: 'SALE_NOT_FOUND', detail: 'venda não encontrada' });
        },
      }),
    );

    expect(await api.addSaleItem('sale-1', { barcode: '789', quantity: 1 })).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 404, code: 'SALE_NOT_FOUND', detail: 'venda não encontrada' },
    });
  });

  test('422 PRODUCT_INACTIVE vira recusa com mensagem clara e o código bipado', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(422, {
            code: 'PRODUCT_INACTIVE',
            detail: 'produto 01924d2c está inativo',
          });
        },
      }),
    );

    expect(await api.addSaleItem('sale-1', { barcode: '789', quantity: 1 })).toEqual({
      ok: false,
      kind: 'rejected',
      barcode: '789',
      message: 'produto desativado no cadastro: 789 — fale com o gerente',
    });
  });

  test('422 INVALID_INTERNAL_BARCODE vira recusa com o detail do servidor', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(422, {
            code: 'INVALID_INTERNAL_BARCODE',
            detail: 'etiqueta de balança 2000420000000 embute valor zero',
          });
        },
      }),
    );

    expect(await api.addSaleItem('sale-1', { barcode: '2000420000000', quantity: 1 })).toEqual({
      ok: false,
      kind: 'rejected',
      barcode: '2000420000000',
      message: 'código recusado: etiqueta de balança 2000420000000 embute valor zero',
    });
  });

  test('403 no item é falha bloqueante; rede e 5xx são transitórias (retry manual)', async () => {
    const forbidden = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão sale.create' });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(503, { code: 'UNAVAILABLE', detail: 'servidor fora do ar' });
        },
      }),
    );

    expect(await forbidden.addSaleItem('sale-1', { barcode: '789', quantity: 1 })).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 403, code: 'ACCESS_DENIED', detail: 'permissão sale.create' },
    });
    expect(await offline.addSaleItem('sale-1', { barcode: '789', quantity: 1 })).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' },
    });
  });

  test('trocar a quantidade manda o PATCH com a quantidade absoluta e devolve a venda do servidor', async () => {
    const patch = vi.fn(async () => ({
      id: 'sale-1',
      status: 'OPEN',
      subtotal: 49.8,
      discountAmount: 0,
      total: 49.8,
      items: [
        { productId: 'p1', barcode: '7891000100103', name: 'Arroz 5kg', unit: 'UN', unitPrice: 24.9, quantity: 2, lineTotal: 49.8 },
      ],
    }));
    const api = createTerminalApi(stubClient({ patch }));

    expect(await api.changeSaleItemQuantity('sale-1', 'p1', 2)).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [
          { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 2, unitPrice: 24.9, lineTotal: 49.8 },
        ],
        subtotal: 49.8,
        discountAmount: 0,
        total: 49.8,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: null,
      },
    });
    // o `{itemId}` da rota é o productId do item (decisão do 802/809b)
    expect(patch).toHaveBeenCalledWith('/api/v1/sales/sale-1/items/p1', { quantity: 2 });
  });

  test('404 SALE_ITEM_NOT_FOUND é desfecho próprio: o item sumiu entre a leitura e a ação', async () => {
    const api = createTerminalApi(
      stubClient({
        patch: async () => {
          throw new ApiError(404, {
            code: 'SALE_ITEM_NOT_FOUND',
            detail: 'produto 01924d2c não está na venda',
          });
        },
      }),
    );

    expect(await api.changeSaleItemQuantity('sale-1', 'p1', 2)).toEqual({
      ok: false,
      kind: 'notFound',
    });
  });

  test('remover item manda o DELETE no productId e devolve a venda já recalculada', async () => {
    const remove = vi.fn(async () => ({
      id: 'sale-1',
      status: 'OPEN',
      subtotal: 8.9,
      discountAmount: 0,
      total: 8.9,
      items: [
        { productId: 'p2', name: 'Feijão 1kg', unit: 'UN', unitPrice: 8.9, quantity: 1, lineTotal: 8.9 },
      ],
    }));
    const api = createTerminalApi(stubClient({ delete: remove }));

    expect(await api.removeSaleItem('sale-1', 'p1')).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [
          { productId: 'p2', name: 'Feijão 1kg', unit: 'UN', quantity: 1, unitPrice: 8.9, lineTotal: 8.9 },
        ],
        subtotal: 8.9,
        discountAmount: 0,
        total: 8.9,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: null,
      },
    });
    expect(remove).toHaveBeenCalledWith('/api/v1/sales/sale-1/items/p1');
  });

  test('409 SALE_NOT_OPEN na mutação é falha bloqueante; rede é transitória (retry manual)', async () => {
    const closed = createTerminalApi(
      stubClient({
        delete: async () => {
          throw new ApiError(409, { code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        patch: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await closed.removeSaleItem('sale-1', 'p1')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' },
    });
    expect(await offline.changeSaleItemQuantity('sale-1', 'p1', 2)).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('200 sem venda na resposta é falha bloqueante: a tela não teria o que exibir', async () => {
    const api = createTerminalApi(stubClient({ patch: async () => ({ status: 'OPEN' }) }));

    expect(await api.changeSaleItemQuantity('sale-1', 'p1', 2)).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'item alterado sem venda na resposta' },
    });
  });

  test('desconto manda tipo, valor e motivo no PUT e devolve a venda recalculada pelo servidor', async () => {
    const put = vi.fn(async () => ({
      id: 'sale-1',
      status: 'OPEN',
      subtotal: 24.9,
      discountType: 'PERCENT',
      discountValue: 10,
      discountAmount: 2.49,
      total: 22.41,
      items: [
        { productId: 'p1', barcode: '7891000100103', name: 'Arroz 5kg', unit: 'UN', unitPrice: 24.9, quantity: 1, lineTotal: 24.9 },
      ],
    }));
    const api = createTerminalApi(stubClient({ put }));

    expect(
      await api.applyDiscount('sale-1', { type: 'PERCENT', value: 10, reason: 'cliente pediu' }),
    ).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [
          { productId: 'p1', name: 'Arroz 5kg', unit: 'UN', quantity: 1, unitPrice: 24.9, lineTotal: 24.9 },
        ],
        subtotal: 24.9,
        discountAmount: 2.49,
        total: 22.41,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: null,
      },
    });
    expect(put).toHaveBeenCalledWith('/api/v1/sales/sale-1/discount', {
      type: 'PERCENT',
      value: 10,
      reason: 'cliente pediu',
    });
  });

  test('403 sem `sale.discount.apply` vira recusa com a mensagem fixa, sem expor o código', async () => {
    const api = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(403, {
            code: 'ACCESS_DENIED',
            detail: 'permissão sale.discount.apply',
          });
        },
      }),
    );

    expect(
      await api.applyDiscount('sale-1', { type: 'VALUE', value: 10, reason: 'cliente pediu' }),
    ).toEqual({ ok: false, kind: 'rejected', message: 'sem permissão para aplicar desconto' });
  });

  test('422 do limite da loja vira recusa com a mensagem que o servidor mandou', async () => {
    const api = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(422, {
            code: 'DISCOUNT_LIMIT_EXCEEDED',
            detail: 'desconto de 30% excede o limite de 10% da loja',
          });
        },
      }),
    );

    expect(
      await api.applyDiscount('sale-1', { type: 'PERCENT', value: 30, reason: 'promoção' }),
    ).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'desconto de 30% excede o limite de 10% da loja',
    });
  });

  test('400 da forma/motivo vira recusa com o detail; 409 e rede não são recusa do modal', async () => {
    const invalid = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(400, { code: 'VALIDATION_ERROR', detail: 'motivo do desconto é obrigatório' });
        },
      }),
    );
    const closed = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(409, { code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        put: async () => {
          throw new Error('fetch failed');
        },
      }),
    );
    const discount = { type: 'VALUE', value: 10, reason: 'cliente pediu' } as const;

    expect(await invalid.applyDiscount('sale-1', discount)).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'motivo do desconto é obrigatório',
    });
    expect(await closed.applyDiscount('sale-1', discount)).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' },
    });
    expect(await offline.applyDiscount('sale-1', discount)).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('200 sem venda na resposta do desconto é falha bloqueante', async () => {
    const api = createTerminalApi(stubClient({ put: async () => ({ status: 'OPEN' }) }));

    expect(
      await api.applyDiscount('sale-1', { type: 'VALUE', value: 10, reason: 'cliente pediu' }),
    ).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'desconto aplicado sem venda na resposta' },
    });
  });
});

describe('createTerminalApi: cliente na venda (1112)', () => {
  test('busca manda o termo com a primeira página pequena e normaliza os clientes do servidor', async () => {
    const get = vi.fn(async () => ({
      items: [
        { id: 'c1', name: 'Maria Silva', taxId: '12345678900', active: true },
        { id: 'c2', name: 'Ana Souza' },
        { name: 'sem id não é vinculável' },
      ],
      page: 0,
      size: 10,
      totalItems: 2,
      totalPages: 1,
    }));
    const api = createTerminalApi(stubClient({ get }));

    expect(await api.searchCustomers('maria')).toEqual({
      ok: true,
      customers: [
        { id: 'c1', name: 'Maria Silva', taxId: '12345678900' },
        { id: 'c2', name: 'Ana Souza', taxId: null },
      ],
    });
    expect(get).toHaveBeenCalledWith('/api/v1/customers?search=maria&page=0&size=10');
  });

  test('403 sem `customer.read` na busca vira recusa; rede é transitória (retry no modal)', async () => {
    const denied = createTerminalApi(
      stubClient({
        get: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão customer.read' });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        get: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await denied.searchCustomers('maria')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para consultar clientes',
    });
    expect(await offline.searchCustomers('maria')).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('vincular manda o customerId no PUT e devolve a venda com o cliente do servidor', async () => {
    const put = vi.fn(async () => ({
      id: 'sale-1',
      status: 'OPEN',
      customerId: 'c1',
      subtotal: 24.9,
      discountAmount: 0,
      total: 24.9,
    }));
    const api = createTerminalApi(stubClient({ put }));

    expect(await api.linkCustomer('sale-1', 'c1')).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [],
        subtotal: 24.9,
        discountAmount: 0,
        total: 24.9,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: 'c1',
      },
    });
    expect(put).toHaveBeenCalledWith('/api/v1/sales/sale-1/customer', { customerId: 'c1' });
  });

  test('404 CUSTOMER_NOT_FOUND e 422 CUSTOMER_INACTIVE viram recusa com mensagem clara', async () => {
    const missing = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(404, { code: 'CUSTOMER_NOT_FOUND', detail: 'cliente c9 não encontrado' });
        },
      }),
    );
    const inactive = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(422, { code: 'CUSTOMER_INACTIVE', detail: 'cliente c9 está inativo' });
        },
      }),
    );

    expect(await missing.linkCustomer('sale-1', 'c9')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'cliente não encontrado — busque de novo',
    });
    expect(await inactive.linkCustomer('sale-1', 'c9')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'cliente desativado no cadastro — escolha outro',
    });
  });

  test('403 do vínculo vira recusa com a mensagem fixa; 404 SALE_NOT_FOUND bloqueia', async () => {
    const denied = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão sale.create' });
        },
      }),
    );
    const gone = createTerminalApi(
      stubClient({
        put: async () => {
          throw new ApiError(404, { code: 'SALE_NOT_FOUND', detail: 'venda não encontrada' });
        },
      }),
    );

    expect(await denied.linkCustomer('sale-1', 'c1')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para alterar o cliente da venda',
    });
    expect(await gone.linkCustomer('sale-1', 'c1')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 404, code: 'SALE_NOT_FOUND', detail: 'venda não encontrada' },
    });
  });

  test('remover manda o DELETE e devolve a venda anônima; 200 sem venda é falha bloqueante', async () => {
    const remove = vi.fn(async () => ({
      id: 'sale-1',
      status: 'OPEN',
      subtotal: 24.9,
      discountAmount: 0,
      total: 24.9,
    }));
    const api = createTerminalApi(stubClient({ delete: remove }));
    const empty = createTerminalApi(stubClient({ delete: async () => ({ status: 'OPEN' }) }));

    expect(await api.unlinkCustomer('sale-1')).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [],
        subtotal: 24.9,
        discountAmount: 0,
        total: 24.9,
        paidAmount: 0,
        changeAmount: 0,
        payments: [],
        customerId: null,
      },
    });
    expect(remove).toHaveBeenCalledWith('/api/v1/sales/sale-1/customer');
    expect(await empty.unlinkCustomer('sale-1')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'cliente alterado sem venda na resposta' },
    });
  });
});

describe('createTerminalApi: pagamento e conclusão (1113)', () => {
  /** Venda com o pagamento em dinheiro que o servidor registrou: troco de 50 − 24,90 (BR-05). */
  const PAID_CASH = {
    id: 'sale-1',
    status: 'OPEN',
    subtotal: 24.9,
    discountAmount: 0,
    total: 24.9,
    paidAmount: 24.9,
    changeAmount: 25.1,
    payments: [
      {
        id: 'pay-1',
        method: 'CASH',
        amount: 24.9,
        tenderedAmount: 50,
        changeAmount: 25.1,
        status: 'APPROVED',
      },
    ],
  };

  test('registrar o pagamento manda forma, valor e recebido no POST e devolve a venda recalculada', async () => {
    const post = vi.fn(async () => PAID_CASH);
    const api = createTerminalApi(stubClient({ post }));

    expect(
      await api.addPayment('sale-1', { method: 'CASH', amount: 24.9, tenderedAmount: 50 }, 'key-1'),
    ).toEqual({
      ok: true,
      sale: {
        id: 'sale-1',
        items: [],
        subtotal: 24.9,
        discountAmount: 0,
        total: 24.9,
        paidAmount: 24.9,
        changeAmount: 25.1,
        payments: [
          { id: 'pay-1', method: 'CASH', amount: 24.9, changeAmount: 25.1, status: 'APPROVED' },
        ],
        customerId: null,
      },
    });
    expect(post).toHaveBeenCalledWith(
      '/api/v1/sales/sale-1/payments',
      { method: 'CASH', amount: 24.9, tenderedAmount: 50 },
      { idempotencyKey: 'key-1' },
    );
  });

  test('nas demais formas o recebido não vai no corpo (o servidor recusaria com 422)', async () => {
    const post = vi.fn(async () => ({ ...PAID_CASH, paidAmount: 24.9, changeAmount: 0, payments: [] }));
    const api = createTerminalApi(stubClient({ post }));

    await api.addPayment('sale-1', { method: 'PIX', amount: 24.9 }, 'key-2');

    expect(post).toHaveBeenCalledWith(
      '/api/v1/sales/sale-1/payments',
      { method: 'PIX', amount: 24.9 },
      { idempotencyKey: 'key-2' },
    );
  });

  test('422 PAYMENT_EXCEEDS_TOTAL e INVALID_TENDERED_AMOUNT viram recusa com mensagem clara', async () => {
    const exceeds = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(422, {
            code: 'PAYMENT_EXCEEDS_TOTAL',
            detail: 'pagamento de 30 excede o restante 24,90 da venda',
          });
        },
      }),
    );
    const tendered = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(422, {
            code: 'INVALID_TENDERED_AMOUNT',
            detail: 'valor entregue não cobre o pagamento em dinheiro',
          });
        },
      }),
    );

    expect(await exceeds.addPayment('sale-1', { method: 'CASH', amount: 30 }, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'valor acima do que falta na venda — ajuste o valor',
    });
    expect(await tendered.addPayment('sale-1', { method: 'CASH', amount: 24.9 }, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'valor recebido inválido — o dinheiro precisa cobrir o valor do pagamento',
    });
  });

  test('403 sem `payment.add` vira recusa fixa; 409 é falha bloqueante e rede é transitória', async () => {
    const denied = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão payment.add' });
        },
      }),
    );
    const closed = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(409, { code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        post: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await denied.addPayment('sale-1', { method: 'PIX', amount: 10 }, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para registrar o pagamento',
    });
    expect(await closed.addPayment('sale-1', { method: 'PIX', amount: 10 }, 'k')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' },
    });
    expect(await offline.addPayment('sale-1', { method: 'PIX', amount: 10 }, 'k')).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('201 sem venda na resposta é falha bloqueante', async () => {
    const api = createTerminalApi(stubClient({ post: async () => ({ status: 'OPEN' }) }));

    expect(await api.addPayment('sale-1', { method: 'PIX', amount: 10 }, 'k')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'pagamento sem venda na resposta' },
    });
  });

  test('concluir manda a chave do chamador e devolve o resumo do corpo do complete', async () => {
    const post = vi.fn(async () => ({
      ...PAID_CASH,
      status: 'COMPLETED',
      number: 42,
      completedAt: '2026-09-24T12:00:00Z',
    }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.completeSale('sale-1', 'key-9')).toEqual({
      ok: true,
      receipt: { number: 42, total: 24.9, changeAmount: 25.1 },
    });
    expect(post).toHaveBeenCalledWith('/api/v1/sales/sale-1/complete', undefined, {
      idempotencyKey: 'key-9',
    });
  });

  test('422 PAYMENT_INSUFFICIENT vira recusa com a mensagem que a tela mostra', async () => {
    const api = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(422, {
            code: 'PAYMENT_INSUFFICIENT',
            detail: 'venda exige 24,90 e tem 10,00 pagos',
          });
        },
      }),
    );

    expect(await api.completeSale('sale-1', 'key-9')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'pagamento insuficiente — registre o valor que falta',
    });
  });

  test('403 sem `sale.complete` vira recusa fixa; rede é transitória (a chave volta no retry)', async () => {
    const denied = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão sale.complete' });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        post: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await denied.completeSale('sale-1', 'key-9')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para concluir a venda',
    });
    expect(await offline.completeSale('sale-1', 'key-9')).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('200 sem venda na resposta é falha bloqueante: a tela de sucesso não teria resumo', async () => {
    const api = createTerminalApi(
      stubClient({ post: async () => ({ number: 42, total: 24.9, changeAmount: 0 }) }),
    );

    expect(await api.completeSale('sale-1', 'key-9')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'conclusão sem venda na resposta' },
    });
  });
});

describe('createTerminalApi: sangria e suprimento (1114)', () => {
  test('a sangria manda valor, motivo e a chave do chamador, e devolve o movimento do servidor', async () => {
    const post = vi.fn(async () => ({
      sessionId: 'session-1',
      type: 'WITHDRAWAL',
      amount: 10,
      reason: 'troco para o banco',
      expectedBefore: 150,
      expectedAfter: 140,
      aboveExpected: true,
    }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.withdrawCash('r1', { amount: 10, reason: 'troco para o banco' }, 'key-7')).toEqual({
      ok: true,
      movement: {
        sessionId: 'session-1',
        type: 'WITHDRAWAL',
        amount: 10,
        reason: 'troco para o banco',
        expectedBefore: 150,
        expectedAfter: 140,
        aboveExpected: true, // o alerta da sangria acima do esperado é do servidor (609)
      },
    });
    // o `{id}` da rota é o **caixa**, não a sessão (passo 609)
    expect(post).toHaveBeenCalledWith(
      '/api/v1/cash-registers/r1/withdrawals',
      { amount: 10, reason: 'troco para o banco' },
      { idempotencyKey: 'key-7' },
    );
  });

  test('o suprimento usa a rota do F8, no mesmo contrato', async () => {
    const post = vi.fn(async () => ({
      sessionId: 'session-1',
      type: 'SUPPLY',
      amount: 50,
      reason: 'fundo de troco',
      expectedBefore: 150,
      expectedAfter: 200,
      aboveExpected: false,
    }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.supplyCash('r1', { amount: 50, reason: 'fundo de troco' }, 'key-8')).toEqual({
      ok: true,
      movement: {
        sessionId: 'session-1',
        type: 'SUPPLY',
        amount: 50,
        reason: 'fundo de troco',
        expectedBefore: 150,
        expectedAfter: 200,
        aboveExpected: false,
      },
    });
    expect(post).toHaveBeenCalledWith(
      '/api/v1/cash-registers/r1/supplies',
      { amount: 50, reason: 'fundo de troco' },
      { idempotencyKey: 'key-8' },
    );
  });

  test('403 sem a permissão vira recusa fixa, sem expor o código; a sessão do caixa tem mensagem própria', async () => {
    const denied = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão cash.withdrawal' });
        },
      }),
    );
    const notOpen = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(404, {
            code: 'CASH_SESSION_NOT_OPEN',
            detail: 'caixa sem sessão aberta',
          });
        },
      }),
    );
    const required = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(409, {
            code: 'CASH_SESSION_REQUIRED',
            detail: 'sessão de caixa aberta obrigatória',
          });
        },
      }),
    );
    const missing = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(404, { code: 'CASH_REGISTER_NOT_FOUND', detail: 'caixa r9 não existe' });
        },
      }),
    );
    const movement = { amount: 10, reason: 'troco para o banco' } as const;

    expect(await denied.withdrawCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para registrar sangria',
    });
    expect(await denied.supplyCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para registrar suprimento',
    });
    expect(await notOpen.withdrawCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'caixa sem sessão aberta — fale com o gerente',
    });
    expect(await required.supplyCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'caixa sem sessão aberta — fale com o gerente',
    });
    expect(await missing.withdrawCash('r9', movement, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'caixa não encontrado — verifique o cadastro',
    });
  });

  test('400 do valor/motivo vira recusa com o detail; rede e 5xx são transitórias (retry no modal)', async () => {
    const invalid = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(400, {
            code: 'VALIDATION_ERROR',
            detail: 'amount deve ser maior que zero',
          });
        },
      }),
    );
    const offline = createTerminalApi(
      stubClient({
        post: async () => {
          throw new Error('fetch failed');
        },
      }),
    );
    const unavailable = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(503, { code: 'UNAVAILABLE', detail: 'servidor fora do ar' });
        },
      }),
    );
    const movement = { amount: 0, reason: 'troco para o banco' } as const;

    expect(await invalid.withdrawCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'amount deve ser maior que zero',
    });
    expect(await offline.supplyCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
    expect(await unavailable.withdrawCash('r1', movement, 'k')).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' },
    });
  });

  test('201 sem movimento na resposta é falha bloqueante', async () => {
    const api = createTerminalApi(stubClient({ post: async () => ({ amount: 10 }) }));

    expect(await api.withdrawCash('r1', { amount: 10, reason: 'troco' }, 'k')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'sangria sem movimento na resposta' },
    });
  });
});

describe('createTerminalApi: fechamento de caixa e cancelamento da venda (1115)', () => {
  test('o resumo é lido da sessão e normalizado para a tela, com os mapas zero-preenchidos do servidor', async () => {
    const get = vi.fn(async () => ({
      sessionId: 'session-1',
      status: 'OPEN',
      openingAmount: 10,
      expectedAmount: 34.9,
      countedAmount: null,
      differenceAmount: null,
      totalsByType: { OPENING: 10, SALE: 24.9, WITHDRAWAL: 0, SUPPLY: 0 },
      paymentsByMethod: { CASH: 24.9, PIX: 0, DEBIT: 0, CREDIT: 0, VOUCHER: 0 },
    }));
    const api = createTerminalApi(stubClient({ get }));

    expect(await api.cashSessionSummary('session-1')).toEqual({
      ok: true,
      summary: {
        sessionId: 'session-1',
        status: 'OPEN',
        openingAmount: 10,
        expectedAmount: 34.9,
        countedAmount: null, // nulos enquanto a sessão está aberta
        differenceAmount: null,
        totalsByType: { OPENING: 10, SALE: 24.9, WITHDRAWAL: 0, SUPPLY: 0 },
        paymentsByMethod: { CASH: 24.9, PIX: 0, DEBIT: 0, CREDIT: 0, VOUCHER: 0 },
      },
    });
    expect(get).toHaveBeenCalledWith('/api/v1/cash-sessions/session-1/summary');
  });

  test('resumo fora do contrato cai nos zeros da tela; falha de rede bloqueia com o problema', async () => {
    const empty = createTerminalApi(stubClient({ get: async () => ({}) }));
    const offline = createTerminalApi(
      stubClient({
        get: async () => {
          throw new Error('fetch failed');
        },
      }),
    );

    expect(await empty.cashSessionSummary('session-1')).toEqual({
      ok: true,
      summary: {
        sessionId: '',
        status: '',
        openingAmount: 0,
        expectedAmount: 0,
        countedAmount: null,
        differenceAmount: null,
        totalsByType: {},
        paymentsByMethod: {},
      },
    });
    expect(await offline.cashSessionSummary('session-1')).toEqual({
      ok: false,
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
  });

  test('o fechamento manda o contado e a chave do chamador, e devolve a conferência do servidor', async () => {
    const post = vi.fn(async () => ({
      id: 'session-1',
      status: 'CLOSED',
      countedAmount: 30,
      expectedAmount: 34.9,
      differenceAmount: -4.9,
    }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.closeCashSession('r1', { countedAmount: 30 }, 'key-9')).toEqual({
      ok: true,
      closing: { countedAmount: 30, expectedAmount: 34.9, differenceAmount: -4.9 },
    });
    // o `{id}` da rota é o **caixa**, não a sessão (passo 611)
    expect(post).toHaveBeenCalledWith(
      '/api/v1/cash-registers/r1/close',
      { countedAmount: 30 },
      { idempotencyKey: 'key-9' },
    );
  });

  test('a observação opcional só vai no corpo quando informada', async () => {
    const post = vi.fn(async () => ({
      countedAmount: 30,
      expectedAmount: 34.9,
      differenceAmount: -4.9,
    }));
    const api = createTerminalApi(stubClient({ post }));

    await api.closeCashSession('r1', { countedAmount: 30, notes: 'faltou troco' }, 'key-9');

    expect(post).toHaveBeenCalledWith(
      '/api/v1/cash-registers/r1/close',
      { countedAmount: 30, notes: 'faltou troco' },
      { idempotencyKey: 'key-9' },
    );
  });

  test('403 vira recusa fixa e a venda em andamento diz o que fazer; 400 mostra o detail', async () => {
    const denied = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão cash.close' });
        },
      }),
    );
    const openSales = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(409, {
            code: 'SESSION_HAS_OPEN_SALES',
            detail: 'Sessão de caixa com venda em andamento',
          });
        },
      }),
    );
    const invalid = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(400, {
            code: 'VALIDATION_ERROR',
            detail: 'countedAmount deve ser maior ou igual a zero',
          });
        },
      }),
    );

    expect(await denied.closeCashSession('r1', { countedAmount: 30 }, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para fechar o caixa',
    });
    expect(await openSales.closeCashSession('r1', { countedAmount: 30 }, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'há venda em andamento — cancele a venda (F4) antes de fechar',
    });
    expect(await invalid.closeCashSession('r1', { countedAmount: -1 }, 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'countedAmount deve ser maior ou igual a zero',
    });
  });

  test('rede é transitória (retry com a mesma chave) e 200 sem a conferência é falha bloqueante', async () => {
    const offline = createTerminalApi(
      stubClient({
        post: async () => {
          throw new Error('fetch failed');
        },
      }),
    );
    const noClosing = createTerminalApi(stubClient({ post: async () => ({ status: 'CLOSED' }) }));

    expect(await offline.closeCashSession('r1', { countedAmount: 30 }, 'k')).toEqual({
      ok: false,
      kind: 'retryable',
      problem: { status: 0, code: null, detail: 'fetch failed' },
    });
    expect(await noClosing.closeCashSession('r1', { countedAmount: 30 }, 'k')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 0, code: null, detail: 'fechamento sem conferência na resposta' },
    });
  });

  test('o cancelamento manda o motivo e a chave, e devolve só o fato de a venda ter sido cancelada', async () => {
    const post = vi.fn(async () => ({ id: 'sale-1', number: 7, status: 'CANCELLED' }));
    const api = createTerminalApi(stubClient({ post }));

    expect(await api.cancelSale('sale-1', 'cliente desistiu', 'key-10')).toEqual({ ok: true });
    expect(post).toHaveBeenCalledWith(
      '/api/v1/sales/sale-1/cancel',
      { reason: 'cliente desistiu' },
      { idempotencyKey: 'key-10' },
    );
  });

  test('403 sem sale.cancel vira recusa fixa; 400 do motivo mostra o detail; 409 bloqueia na tela de erro', async () => {
    const denied = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(403, { code: 'ACCESS_DENIED', detail: 'permissão sale.cancel' });
        },
      }),
    );
    const invalid = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(400, { code: 'VALIDATION_ERROR', detail: 'reason obrigatório' });
        },
      }),
    );
    const notOpen = createTerminalApi(
      stubClient({
        post: async () => {
          throw new ApiError(409, { code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' });
        },
      }),
    );

    expect(await denied.cancelSale('sale-1', 'desistiu', 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'sem permissão para cancelar a venda',
    });
    expect(await invalid.cancelSale('sale-1', '', 'k')).toEqual({
      ok: false,
      kind: 'rejected',
      message: 'reason obrigatório',
    });
    expect(await notOpen.cancelSale('sale-1', 'desistiu', 'k')).toEqual({
      ok: false,
      kind: 'failed',
      problem: { status: 409, code: 'SALE_NOT_OPEN', detail: 'venda não está aberta' },
    });
  });
});
