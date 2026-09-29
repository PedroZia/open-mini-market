import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { StockDetailPage } from './StockDetailPage';

/**
 * Detalhe do estoque (1206a) com a rede stubada: `GET /stock/{productId}` é lido e o resumo sai
 * formatado em pt-BR — sem movimento, ajuste ou entrada, que são o 1206b.
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

interface StubBackendOptions {
  detail?: unknown;
  status?: number;
}

/** Serve o detalhe do estoque; qualquer outra rota falha o teste. */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const fetchStub = vi.fn(async (input: RequestInfo | URL) => {
    const pathname = new URL(String(input), 'http://localhost').pathname;
    if (/^\/api\/v1\/stock\/[^/]+$/.test(pathname)) {
      return jsonResponse(
        options.detail ?? {
          productId: 'p1',
          name: 'Arroz',
          barcode: '7891001',
          unit: 'KG',
          quantity: 2.5,
          minQuantity: 5,
          lowStock: true,
          movements: [],
        },
        options.status ?? 200,
      );
    }
    throw new Error(`fetch inesperado: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
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

const routes: RouteObject[] = [
  { path: '/stock', element: <p>Lista de estoque</p> },
  { path: '/stock/:productId', element: <StockDetailPage /> },
];

/** Monta o detalhe de `p1`; `retry` desligado para o 403/404 não repetir a chamada. */
function renderPage(permissions: string[] = ['stock.read']) {
  const router = createMemoryRouter(routes, { initialEntries: ['/stock/p1'] });
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

describe('StockDetailPage — resumo', () => {
  it('mostra nome, código, unidade, saldo e mínimo em pt-BR com o selo de estoque baixo', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Arroz' })).toBeInTheDocument();
    expect(screen.getByText('7891001')).toBeInTheDocument();
    expect(screen.getByText('KG')).toBeInTheDocument();
    expect(screen.getByText('2,5')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('Estoque baixo')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Voltar para o estoque/ })).toHaveAttribute(
      'href',
      '/stock',
    );
  });

  it('mostra — no mínimo quando o produto não tem mínimo configurado', async () => {
    stubBackend({
      detail: {
        productId: 'p2',
        name: 'Refrigerante',
        barcode: '7891002',
        unit: 'UN',
        quantity: 12,
        minQuantity: null,
        lowStock: false,
        movements: [],
      },
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Refrigerante' })).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByText('Estoque baixo')).toBeNull();
  });
});

describe('StockDetailPage — erros', () => {
  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ detail: { code: 'ACCESS_DENIED' }, status: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByText('Saldo')).toBeNull();
  });

  it('mostra a mensagem do problem+json e o caminho de volta quando o produto não existe', async () => {
    stubBackend({
      detail: { code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' },
      status: 404,
    });
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O registro não existe mais. Atualize a lista.',
    );
    expect(screen.getByRole('link', { name: /Voltar para o estoque/ })).toBeInTheDocument();
  });
});
