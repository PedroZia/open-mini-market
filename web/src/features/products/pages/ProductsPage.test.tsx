import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { ProductsPage } from './ProductsPage';

/**
 * Lista de produtos (1204a) com a rede stubada — o setup do web desliga o `fetch` real. Cada
 * teste instala o seu stub e confere a URL que a tela pediu, como manda o contrato (§9.3).
 */

/** Stub do `fetch` no nível em que o client o usa: `ok`, `status` e `text()`. */
type FetchStub = Mock<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>;

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

/** Serve `/products` e `/categories` como o servidor serve; qualquer outra rota falha o teste. */
function stubBackend(options: { products?: unknown; productsStatus?: number } = {}): FetchStub {
  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.startsWith('/api/v1/products')) {
      return jsonResponse(options.products ?? PRODUCTS_RESPONSE, options.productsStatus ?? 200);
    }
    if (url.startsWith('/api/v1/categories')) {
      return jsonResponse(CATEGORIES);
    }
    throw new Error(`fetch inesperado: ${init?.method ?? 'GET'} ${url}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
}

/** Chamadas à **lista** de produtos (sem os POSTs de disable/enable). */
function listCalls(fetchStub: FetchStub): string[] {
  return fetchStub.mock.calls
    .map(([input]) => String(input))
    .filter((url) => new URL(url, 'http://localhost').pathname === '/api/v1/products');
}

/** Query da última chamada à lista — é nela que a tela prova o que pediu. */
function lastListQuery(fetchStub: FetchStub): URLSearchParams {
  const last = listCalls(fetchStub).at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/products');
  }
  return new URL(last, 'http://localhost').searchParams;
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
