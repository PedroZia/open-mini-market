import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { ProductsPage } from './ProductsPage';

/**
 * Produtos com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala o seu
 * stub e confere a requisição que a tela fez (URL, método, cabeçalhos e corpo), como manda o
 * contrato (§9.3/§9.4): a lista (1204a) e o cadastro/edição/preço (1204b).
 */

/** Stub do `fetch` no nível em que o client o usa: `ok`, `status` e `text()`. */
type FetchStub = Mock<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>;

/** Chamada registrada pelo stub: o par que o client passou ao `fetch`. */
type StubCall = [input: RequestInfo | URL, init?: RequestInit | undefined];

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  } as unknown as Response;
}

const CATEGORIES = [
  { id: 'c1', name: 'Mercearia', active: true, sortOrder: 0 },
  { id: 'c2', name: 'Bebidas', active: true, sortOrder: 1 },
];

const PRODUCTS_RESPONSE = {
  items: [
    {
      id: 'p1',
      name: 'Arroz',
      barcode: '7891001',
      categoryId: 'c1',
      unit: 'KG',
      price: 10.5,
      active: true,
    },
    {
      id: 'p2',
      name: 'Refrigerante',
      barcode: '7891002',
      categoryId: 'c2',
      unit: 'UN',
      price: 7,
      active: false,
    },
  ],
  page: 0,
  size: 20,
  totalItems: 2,
  totalPages: 1,
};

/** Detalhe como o `GET /products/{id}` devolve (passo 408) — é dele que sai o `version`. */
const PRODUCT_DETAILS: Record<string, Record<string, unknown>> = {
  p1: {
    id: 'p1',
    name: 'Arroz',
    barcode: '7891001',
    description: 'Tipo 1',
    categoryId: 'c1',
    unit: 'KG',
    price: 10.5,
    minQuantity: 1,
    active: true,
    version: 3,
  },
  p2: {
    id: 'p2',
    name: 'Refrigerante',
    barcode: '7891002',
    categoryId: 'c2',
    unit: 'UN',
    price: 7,
    active: false,
    version: 1,
  },
};

interface StubBackendOptions {
  products?: unknown;
  productsStatus?: number;
  /** Resposta do `PUT /products/{id}`; sem ela o stub devolve o produto com a versão seguinte. */
  update?: { status: number; body: unknown };
  /** Resposta do `PATCH /products/{id}/price`; sem ela o stub devolve o produto com o preço novo. */
  price?: { status: number; body: unknown };
  /**
   * Detalhe servido **depois** de um 409 no `PUT`: é o que o outro operador gravou no meio. Sem
   * ela o stub continua devolvendo o mesmo detalhe.
   */
  detailAfterConflict?: Record<string, unknown>;
}

/** Serve cada rota do recurso como o servidor serve; qualquer rota inesperada falha o teste. */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  // O 409 do PUT é o que muda o detalhe: o "outro operador" gravou entre a leitura e a escrita.
  let conflicted = false;

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const pathname = new URL(url, 'http://localhost').pathname;

    if (pathname === '/api/v1/categories') {
      return jsonResponse(CATEGORIES);
    }
    if (pathname === '/api/v1/products' && method === 'GET') {
      return jsonResponse(options.products ?? PRODUCTS_RESPONSE, options.productsStatus ?? 200);
    }
    if (pathname === '/api/v1/products' && method === 'POST') {
      return jsonResponse({ ...PRODUCT_DETAILS.p1, id: 'p3', name: 'Café', version: 0 }, 201);
    }

    const detail = /^\/api\/v1\/products\/([^/]+)$/.exec(pathname);
    if (detail !== null) {
      const id = detail[1] ?? '';
      const product = PRODUCT_DETAILS[id] ?? {};
      if (method === 'GET') {
        if (conflicted && options.detailAfterConflict !== undefined) {
          return jsonResponse(options.detailAfterConflict);
        }
        return id in PRODUCT_DETAILS
          ? jsonResponse(product)
          : jsonResponse({ code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' }, 404);
      }
      if (method === 'PUT') {
        if (options.update === undefined) {
          return jsonResponse({ ...product, version: 4 });
        }
        conflicted = options.update.status === 409;
        return jsonResponse(options.update.body, options.update.status);
      }
    }

    if (/^\/api\/v1\/products\/[^/]+\/price$/.test(pathname) && method === 'PATCH') {
      return options.price === undefined
        ? jsonResponse({ ...PRODUCT_DETAILS.p1, price: 9.99, version: 4 })
        : jsonResponse(options.price.body, options.price.status);
    }

    const lifecycle = /^\/api\/v1\/products\/([^/]+)\/(disable|enable)$/.exec(pathname);
    if (lifecycle !== null) {
      const id = lifecycle[1] ?? '';
      return jsonResponse({ ...(PRODUCT_DETAILS[id] ?? {}), active: lifecycle[2] === 'enable' });
    }

    throw new Error(`fetch inesperado: ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
}

/** Chamadas que batem com método e caminho exatos, na ordem em que a tela as pediu. */
function calls(fetchStub: FetchStub, method: string, pathname: string): StubCall[] {
  return fetchStub.mock.calls.filter(([input, init]) => {
    const url = new URL(String(input), 'http://localhost');
    return (init?.method ?? 'GET') === method && url.pathname === pathname;
  });
}

/** Última chamada do par método/caminho; o teste falha claro quando nenhuma aconteceu. */
function lastCall(fetchStub: FetchStub, method: string, pathname: string): StubCall {
  const matched = calls(fetchStub, method, pathname);
  const call = matched.at(-1);
  if (call === undefined) {
    throw new Error(`nenhuma chamada ${method} ${pathname}`);
  }
  return call;
}

/** Cabeçalho da requisição como o `fetch` o recebeu (o client manda `Headers`). */
function headerOf(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name);
}

/** Consultas à **lista** de produtos (sem os POSTs de situação nem o detalhe). */
function listCalls(fetchStub: FetchStub): StubCall[] {
  return calls(fetchStub, 'GET', '/api/v1/products');
}

/** Query da última chamada à lista — é nela que a tela prova o que pediu. */
function lastListQuery(fetchStub: FetchStub): URLSearchParams {
  const last = listCalls(fetchStub).at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/products');
  }
  return new URL(String(last[0]), 'http://localhost').searchParams;
}

function sessionWith(permissions: string[]): AuthContextValue {
  return {
    status: 'authenticated',
    user: { id: 'u1', username: 'ana', displayName: 'Ana' },
    roles: [],
    permissions,
    login: async () => {},
    logout: async () => {},
  };
}

/** Monta a página com sessão e cache próprios; `retry` desligado para o 403 não repetir. */
function renderPage(permissions: string[] = ['product.read', 'product.write']) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AuthContext.Provider value={sessionWith(permissions)}>
        <ProductsPage />
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('ProductsPage — lista', () => {
  it('renderiza os produtos com categoria, preço em pt-BR e situação', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('cell', { name: 'Arroz' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Refrigerante' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '7891001' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Mercearia' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'KG' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /10,50/ })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Ativo' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Desativado' })).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ products: { code: 'ACCESS_DENIED' }, productsStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('ProductsPage — filtros, ordenação e paginação', () => {
  it('envia busca, categoria e situação como parâmetros e volta à página 1', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.change(screen.getByLabelText('Buscar'), { target: { value: 'arr' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('search')).toBe('arr'));

    await screen.findByRole('option', { name: 'Bebidas' });
    fireEvent.change(screen.getByLabelText('Categoria'), { target: { value: 'c2' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('categoryId')).toBe('c2'));

    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'inactive' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('active')).toBe('false'));

    // Filtro mudou: a consulta sai da página 0, nunca da página em que o operador estava.
    expect(lastListQuery(fetchStub).get('page')).toBe('0');
  });

  it('ordena pelo servidor com sort=name,desc no segundo clique', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Nome' }));
    await waitFor(() => expect(lastListQuery(fetchStub).get('sort')).toBe('name,asc'));

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Nome' }));
    await waitFor(() => expect(lastListQuery(fetchStub).get('sort')).toBe('name,desc'));

    expect(lastListQuery(fetchStub).get('page')).toBe('0');
  });

  it('pede a página seguinte ao servidor', async () => {
    const fetchStub = stubBackend({
      products: { ...PRODUCTS_RESPONSE, totalItems: 40, totalPages: 2 },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));

    await waitFor(() => expect(lastListQuery(fetchStub).get('page')).toBe('1'));
  });
});

describe('ProductsPage — desativar e reativar', () => {
  it('chama os endpoints de situação e recarrega a lista', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });
    const before = listCalls(fetchStub).length;

    fireEvent.click(screen.getByRole('button', { name: 'Desativar Arroz' }));

    await waitFor(() => {
      expect(
        fetchStub.mock.calls.some(
          ([input, init]) =>
            String(input) === '/api/v1/products/p1/disable' && init?.method === 'POST',
        ),
      ).toBe(true);
    });
    // Mutação invalidou a lista: a página corrente foi relida do servidor.
    await waitFor(() => expect(listCalls(fetchStub).length).toBeGreaterThan(before));

    fireEvent.click(screen.getByRole('button', { name: 'Reativar Refrigerante' }));

    await waitFor(() => {
      expect(
        fetchStub.mock.calls.some(
          ([input, init]) =>
            String(input) === '/api/v1/products/p2/enable' && init?.method === 'POST',
        ),
      ).toBe(true);
    });
  });

  it('esconde as ações sem product.write', async () => {
    stubBackend();
    renderPage(['product.read']);

    await screen.findByRole('cell', { name: 'Arroz' });

    expect(screen.queryByRole('columnheader', { name: 'Ações' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Desativar/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Reativar/ })).toBeNull();
  });
});

describe('ProductsPage — cadastro', () => {
  it('envia o corpo do contrato com o preço cru e fecha o modal', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });
    const before = listCalls(fetchStub).length;

    fireEvent.click(screen.getByRole('button', { name: 'Novo produto' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Café' } });
    fireEvent.change(screen.getByLabelText('Código de barras'), { target: { value: '7891003' } });
    fireEvent.change(screen.getByLabelText('Unidade'), { target: { value: 'UN' } });
    fireEvent.change(screen.getByLabelText('Preço (R$)'), { target: { value: '12,50' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', '/api/v1/products')).toHaveLength(1));

    const [input, init] = lastCall(fetchStub, 'POST', '/api/v1/products');
    expect(String(input)).toBe('/api/v1/products');
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Café',
      barcode: '7891003',
      unit: 'UN',
      price: 12.5,
    });

    // Quem diz o estado final é o servidor: a lista foi lida de novo e o modal fechou.
    await waitFor(() => expect(listCalls(fetchStub).length).toBeGreaterThan(before));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('bloqueia o cadastro sem nome e sem preço válido', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Novo produto' }));
    fireEvent.change(screen.getByLabelText('Preço (R$)'), { target: { value: '12,345' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Informe o nome.')).toBeInTheDocument();
    expect(screen.getByText('Informe um preço como 12,50.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/products')).toHaveLength(0);
  });
});

describe('ProductsPage — edição', () => {
  it('relê o detalhe, manda If-Match com a versão e não manda preço nem barcode', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Editar Arroz' }));

    const barcode = await screen.findByLabelText('Código de barras');
    expect(barcode).toHaveValue('7891001');
    expect(barcode).toHaveAttribute('readonly');
    // O preço não é editado por aqui: quem muda valor é o modal de preço (passo 411).
    expect(screen.queryByLabelText('Preço (R$)')).toBeNull();

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Arroz Tipo 1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'PUT', '/api/v1/products/p1')).toHaveLength(1));

    const [, init] = lastCall(fetchStub, 'PUT', '/api/v1/products/p1');
    expect(headerOf(init, 'if-match')).toBe('3');
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Arroz Tipo 1',
      categoryId: 'c1',
      unit: 'KG',
      description: 'Tipo 1',
      minQuantity: 1,
    });
  });

  it('mostra a mensagem e recarrega o produto quando a versão é velha', async () => {
    const fetchStub = stubBackend({
      update: {
        status: 409,
        body: {
          status: 409,
          code: 'CONCURRENT_MODIFICATION',
          detail: 'produto p1 foi alterado (versão esperada 3, atual 4); recarregue e tente de novo',
        },
      },
      // O "outro operador" renomeou o produto antes: é isto que o modal tem que mostrar depois.
      detailAfterConflict: { ...PRODUCT_DETAILS.p1, name: 'Arroz T1', version: 4 },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Editar Arroz' }));
    await screen.findByDisplayValue('Arroz');
    const readsBefore = calls(fetchStub, 'GET', '/api/v1/products/p1').length;

    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Alguém alterou este registro antes de você. Recarregue e tente de novo.',
    );
    // O 409 relê o produto: o próximo Salvar leva a versão atual, com o formulário aberto e com os
    // dados do servidor na tela (não os que o operador tinha digitado).
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/products/p1').length).toBeGreaterThan(readsBefore),
    );
    expect(await screen.findByDisplayValue('Arroz T1')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('ProductsPage — alteração de preço', () => {
  it('manda o preço cru com motivo e sem If-Match', async () => {
    const fetchStub = stubBackend();
    renderPage(['product.read', 'price.write']);
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Alterar preço de Arroz' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/10,50/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Novo preço (R$)'), { target: { value: '9,99' } });
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'Promoção' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'PATCH', '/api/v1/products/p1/price')).toHaveLength(1),
    );

    const [, init] = lastCall(fetchStub, 'PATCH', '/api/v1/products/p1/price');
    expect(JSON.parse(String(init?.body))).toEqual({ price: 9.99, reason: 'Promoção' });
    // O recurso de preço não exige a versão: o cabeçalho do lock otimista não vai.
    expect(headerOf(init, 'if-match')).toBeNull();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});

describe('ProductsPage — permissões de escrita', () => {
  it('esconde cadastro, edição, preço e situação sem as permissões', async () => {
    stubBackend();
    renderPage(['product.read']);
    await screen.findByRole('cell', { name: 'Arroz' });

    expect(screen.queryByRole('button', { name: 'Novo produto' })).toBeNull();
    expect(
      screen.queryByRole('button', { name: /Editar|Alterar preço|Desativar|Reativar/ }),
    ).toBeNull();
  });

  it('com só price.write mostra apenas a alteração de preço, e só de produto ativo', async () => {
    stubBackend();
    renderPage(['product.read', 'price.write']);
    await screen.findByRole('cell', { name: 'Arroz' });

    expect(screen.getByRole('button', { name: 'Alterar preço de Arroz' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Novo produto' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Editar Arroz' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Desativar Arroz' })).toBeNull();
    // Produto desativado não aceita edição nem preço: o servidor responde 404 para ele.
    expect(screen.queryByRole('button', { name: 'Alterar preço de Refrigerante' })).toBeNull();
  });

  it('com product.write sem price.write não mostra a alteração de preço', async () => {
    stubBackend();
    renderPage(['product.read', 'product.write']);
    await screen.findByRole('cell', { name: 'Arroz' });

    expect(screen.getByRole('button', { name: 'Editar Arroz' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Desativar Arroz' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Alterar preço/ })).toBeNull();
  });
});
