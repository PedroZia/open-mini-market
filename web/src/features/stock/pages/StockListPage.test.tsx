import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { StockDetailPage } from './StockDetailPage';
import { StockListPage } from './StockListPage';

/**
 * Estoque (1206a) com a rede stubada. Cada teste instala o seu stub e confere a requisição que a
 * tela fez (URL e método), como manda o contrato (§9.3): `GET /stock` com `search`/`lowStock`/
 * `page`/`size` — sem `sort`, que o recurso não tem — e o detalhe em `GET /stock/{productId}`.
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

const STOCK_RESPONSE = {
  items: [
    {
      productId: 'p1',
      name: 'Arroz',
      barcode: '7891001',
      unit: 'KG',
      quantity: 2.5,
      minQuantity: 5,
      lowStock: true,
    },
    {
      productId: 'p2',
      name: 'Refrigerante',
      barcode: '7891002',
      unit: 'UN',
      quantity: 12,
      // Produto sem mínimo configurado: o servidor manda `null` e ele nunca é estoque baixo.
      minQuantity: null,
      lowStock: false,
    },
  ],
  page: 0,
  size: 20,
  totalItems: 2,
  totalPages: 1,
};

/** Detalhe como o `GET /stock/{productId}` devolve (passo 704). */
const STOCK_DETAIL = {
  productId: 'p1',
  name: 'Arroz',
  barcode: '7891001',
  unit: 'KG',
  quantity: 2.5,
  minQuantity: 5,
  lowStock: true,
  movements: [],
};

interface StubBackendOptions {
  /** Resposta do `GET /stock`; sem ela o stub devolve os dois saldos acima. */
  stock?: unknown;
  stockStatus?: number;
  /** Resposta do `GET /stock/{productId}`. */
  detail?: unknown;
  detailStatus?: number;
}

/** Serve cada rota do recurso como o servidor serve; qualquer rota inesperada falha o teste. */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const fetchStub = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const pathname = new URL(url, 'http://localhost').pathname;

    if (pathname === '/api/v1/stock') {
      return jsonResponse(options.stock ?? STOCK_RESPONSE, options.stockStatus ?? 200);
    }

    if (/^\/api\/v1\/stock\/[^/]+$/.test(pathname)) {
      return jsonResponse(options.detail ?? STOCK_DETAIL, options.detailStatus ?? 200);
    }

    throw new Error(`fetch inesperado: ${url}`);
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

/** Consultas à lista de saldos (o detalhe tem o próprio caminho). */
function listCalls(fetchStub: FetchStub): StubCall[] {
  return calls(fetchStub, 'GET', '/api/v1/stock');
}

/** Query da última chamada à lista — é nela que a tela prova o que pediu. */
function lastListQuery(fetchStub: FetchStub): URLSearchParams {
  const last = listCalls(fetchStub).at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/stock');
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

/** A lista e o detalhe reais nas rotas reais: é o clique do nome que muda de tela. */
const routes: RouteObject[] = [
  { path: '/stock', element: <StockListPage /> },
  { path: '/stock/:productId', element: <StockDetailPage /> },
];

/** Monta a tela em `/stock` com sessão e cache próprios; `retry` desligado para o 403 não repetir. */
function renderPage(permissions: string[] = ['stock.read']) {
  const router = createMemoryRouter(routes, { initialEntries: ['/stock'] });
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AuthContext.Provider value={sessionWith(permissions)}>
        <RouterProvider router={router} />
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
  return router;
}

describe('StockListPage — lista', () => {
  it('renderiza os saldos com unidade, mínimo em pt-BR e o selo de estoque baixo', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('cell', { name: 'Arroz' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Refrigerante' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '7891001' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'KG' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /2,5/ })).toBeInTheDocument();
    // O selo é do servidor (`lowStock`), não de uma conta feita na tela.
    expect(screen.getByText('Estoque baixo')).toBeInTheDocument();
    // Produto sem mínimo não tem selo nem mínimo na tela.
    expect(screen.getByRole('cell', { name: '—' })).toBeInTheDocument();
    // A rota não tem ordenação: nenhuma coluna é botão de ordenar.
    expect(screen.queryByRole('button', { name: /Ordenar por/ })).toBeNull();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ stock: { code: 'ACCESS_DENIED' }, stockStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('StockListPage — filtros e paginação', () => {
  it('envia a busca e manda lowStock=true só no filtro de estoque baixo', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.change(screen.getByLabelText('Buscar'), { target: { value: 'arr' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('search')).toBe('arr'));

    fireEvent.change(screen.getByLabelText('Filtro de estoque'), { target: { value: 'low' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('lowStock')).toBe('true'));

    // "Todos" não vira `lowStock=false`: o filtro sai da URL.
    const before = listCalls(fetchStub).length;
    fireEvent.change(screen.getByLabelText('Filtro de estoque'), { target: { value: 'all' } });
    await waitFor(() => expect(listCalls(fetchStub).length).toBeGreaterThan(before));
    expect(lastListQuery(fetchStub).get('lowStock')).toBeNull();
    expect(lastListQuery(fetchStub).get('sort')).toBeNull();
  });

  it('volta à página 1 quando o filtro muda e pede a página seguinte ao servidor', async () => {
    const fetchStub = stubBackend({
      stock: { ...STOCK_RESPONSE, totalItems: 40, totalPages: 2 },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));
    await waitFor(() => expect(lastListQuery(fetchStub).get('page')).toBe('1'));

    fireEvent.change(screen.getByLabelText('Filtro de estoque'), { target: { value: 'low' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('lowStock')).toBe('true'));
    // Filtro mudou: a consulta sai da página 0, nunca da página em que o operador estava.
    expect(lastListQuery(fetchStub).get('page')).toBe('0');
  });
});

describe('StockListPage — detalhe', () => {
  it('abre o detalhe do produto ao clicar no nome', async () => {
    stubBackend();
    const router = renderPage();
    await screen.findByRole('cell', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('link', { name: 'Arroz' }));

    expect(await screen.findByRole('heading', { name: 'Arroz' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/stock/p1');
    expect(screen.getByText('Estoque baixo')).toBeInTheDocument();
    expect(screen.getByText('Saldo')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Voltar para o estoque/ })).toBeInTheDocument();
  });
});
