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
  put?: (path: string, body?: unknown) => Promise<unknown>;
  patch?: (path: string, body?: unknown) => Promise<unknown>;
  delete?: (path: string) => Promise<unknown>;
};

/** Client dublê: só as rotas da entrada do PDV entram; o resto é erro de teste. */
function stubClient(handlers: Handlers): ApiClient {
  return {
    get: <T>(path: string) =>
      (handlers.get?.(path) ?? Promise.reject(new Error(`GET inesperado: ${path}`))) as Promise<T>,
    post: <T>(path: string, body?: unknown) =>
      (handlers.post?.(path, body) ?? Promise.reject(new Error(`POST inesperado: ${path}`))) as
        Promise<T>,
    put: <T>(path: string, body?: unknown) =>
      (handlers.put?.(path, body) ?? Promise.reject(new Error(`PUT inesperado: ${path}`))) as
        Promise<T>,
    patch: <T>(path: string, body?: unknown) =>
      (handlers.patch?.(path, body) ?? Promise.reject(new Error(`PATCH inesperado: ${path}`))) as
        Promise<T>,
    delete: <T>(path: string) =>
      (handlers.delete?.(path) ?? Promise.reject(new Error(`DELETE inesperado: ${path}`))) as
        Promise<T>,
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
      sale: { id: 'sale-1', items: [], subtotal: 0, discountAmount: 0, total: 0 },
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
