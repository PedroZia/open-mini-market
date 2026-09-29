import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi } from 'vitest';
import { TOKEN_STORAGE_KEY } from '../features/auth/session';
import { routes } from './router';

describe('rota inicial', () => {
  it('renderiza a página Início dentro do layout, com a navegação lateral', async () => {
    // A rota é protegida (1202): a sessão vem do espelho da aba e é validada no `GET /auth/me`.
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            user: { id: 'u1', username: 'ana', displayName: 'Ana' },
            roles: [],
            permissions: [],
          }),
      })),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    // O conteúdo da rota entra pelo Outlet, dentro do <main> do layout — e só depois que a
    // validação da sessão termina (antes disso a guarda mostra "Validando a sessão…").
    const heading = await screen.findByRole('heading', { name: 'Início' });

    // Casca do 1201b: cabeçalho, navegação lateral e área de conteúdo.
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Navegação principal' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toContainElement(heading);

    // "Início" é a rota atual: item ativo marcado para leitor de tela e para o teclado.
    expect(screen.getByRole('link', { name: 'Início' })).toHaveAttribute('aria-current', 'page');

    // Atalho de conteúdo: primeiro Tab do operador pula a navegação.
    expect(screen.getByRole('link', { name: 'Ir para o conteúdo' })).toHaveAttribute(
      'href',
      '#conteudo',
    );

    // Sessão (1202): quem entrou aparece no cabeçalho e o logout está à mão.
    expect(screen.getByText('Ana')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sair' })).toBeInTheDocument();
  });

  it('navega para Produtos pelo item lateral e renderiza a lista (1204a)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        // A sessão é validada em paralelo com a lista; cada rota responde o seu contrato.
        if (url.startsWith('/api/v1/products')) {
          return jsonFetchResponse({
            items: [],
            page: 0,
            size: 20,
            totalItems: 0,
            totalPages: 0,
          });
        }
        if (url.startsWith('/api/v1/categories')) {
          return jsonFetchResponse([]);
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['product.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    fireEvent.click(screen.getByRole('link', { name: 'Produtos' }));

    expect(await screen.findByRole('heading', { name: 'Produtos' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /products.
    expect(screen.getByRole('link', { name: 'Produtos' })).toHaveAttribute('aria-current', 'page');
    expect(router.state.location.pathname).toBe('/products');
  });

  it('navega para Categorias pelo item lateral e renderiza a lista (1205)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/categories')) {
          return jsonFetchResponse([{ id: 'c1', name: 'Mercearia', active: true, sortOrder: 0 }]);
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['product.read', 'category.write'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    fireEvent.click(screen.getByRole('link', { name: 'Categorias' }));

    expect(await screen.findByRole('heading', { name: 'Categorias' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Mercearia' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /categories.
    expect(screen.getByRole('link', { name: 'Categorias' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(router.state.location.pathname).toBe('/categories');
  });

  it('navega para Estoque pelo item lateral e renderiza a lista (1206a)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/stock')) {
          return jsonFetchResponse({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['stock.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    fireEvent.click(screen.getByRole('link', { name: 'Estoque' }));

    expect(await screen.findByRole('heading', { name: 'Estoque' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /stock.
    expect(screen.getByRole('link', { name: 'Estoque' })).toHaveAttribute('aria-current', 'page');
    expect(router.state.location.pathname).toBe('/stock');
  });

  it('navega para Clientes pelo item lateral e renderiza a lista (1207)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/customers')) {
          return jsonFetchResponse({
            items: [
              { id: 'cli1', name: 'Ana Souza', taxId: '52998224725', phone: '11999990000' },
            ],
            page: 0,
            size: 20,
            totalItems: 1,
            totalPages: 1,
          });
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['customer.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    fireEvent.click(screen.getByRole('link', { name: 'Clientes' }));

    expect(await screen.findByRole('heading', { name: 'Clientes' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Ana Souza' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /customers.
    expect(screen.getByRole('link', { name: 'Clientes' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(router.state.location.pathname).toBe('/customers');
  });

  it('navega para Usuários pelo item lateral e renderiza a lista (1208a)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/users')) {
          return jsonFetchResponse({
            items: [
              {
                id: 'u1',
                username: 'ana',
                displayName: 'Ana Souza',
                roles: ['ADMIN'],
                status: 'ACTIVE',
                mustChangePassword: false,
              },
            ],
            page: 0,
            size: 20,
            totalItems: 1,
            totalPages: 1,
          });
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['user.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    // O item só aparece com `user.read` na sessão (ADMIN).
    fireEvent.click(screen.getByRole('link', { name: 'Usuários' }));

    expect(await screen.findByRole('heading', { name: 'Usuários' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Ana Souza' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /users.
    expect(screen.getByRole('link', { name: 'Usuários' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(router.state.location.pathname).toBe('/users');
  });

  it('navega para Papéis pelo item lateral e renderiza o catálogo (1208b)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/roles')) {
          return jsonFetchResponse([
            {
              code: 'ADMIN',
              name: 'Administrador',
              description: 'Acesso total ao sistema.',
              system: true,
              permissions: ['user.read'],
            },
          ]);
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['user.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    fireEvent.click(screen.getByRole('link', { name: 'Papéis' }));

    expect(await screen.findByRole('heading', { name: 'Administrador' })).toBeInTheDocument();
    expect(screen.getByText('user.read')).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /roles.
    expect(screen.getByRole('link', { name: 'Papéis' })).toHaveAttribute('aria-current', 'page');
    expect(router.state.location.pathname).toBe('/roles');
  });

  it('navega para Caixa pelo item lateral e renderiza a lista (1210a)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/cash-registers')) {
          return jsonFetchResponse([
            {
              id: '0198f5a1-3c4d-7e5f-8a91-000000000001',
              code: 'C1',
              name: 'Frente de loja',
              status: 'OPEN',
              operatorName: 'Ana Souza',
            },
          ]);
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['cash.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    // O item só aparece com `cash.read` na sessão.
    fireEvent.click(screen.getByRole('link', { name: 'Caixa' }));

    expect(await screen.findByRole('heading', { name: 'Caixa' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Frente de loja' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /cash-registers.
    expect(screen.getByRole('link', { name: 'Caixa' })).toHaveAttribute('aria-current', 'page');
    expect(router.state.location.pathname).toBe('/cash-registers');
  });

  it('navega para Vendas pelo item lateral e renderiza a lista (1209a)', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-de-teste');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/sales')) {
          return jsonFetchResponse({
            items: [
              {
                id: 's1',
                number: 12,
                status: 'COMPLETED',
                operatorUserId: '0198f3b7-8e2f-7c4a-8d1e-000000000002',
                itemCount: 3,
                total: 31.9,
                createdAt: '2026-09-28T22:21:54Z',
              },
            ],
            page: 0,
            size: 20,
            totalItems: 1,
            totalPages: 1,
          });
        }
        return jsonFetchResponse({
          user: { id: 'u1', username: 'ana', displayName: 'Ana' },
          roles: [],
          permissions: ['report.read'],
        });
      }),
    );

    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Início' });
    // O item só aparece com `report.read` na sessão (GERENTE/ADMIN).
    fireEvent.click(screen.getByRole('link', { name: 'Vendas' }));

    expect(await screen.findByRole('heading', { name: 'Vendas' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Concluída' })).toBeInTheDocument();

    // Rota real (sem link morto): o item fica ativo e a URL muda para /sales.
    expect(screen.getByRole('link', { name: 'Vendas' })).toHaveAttribute('aria-current', 'page');
    expect(router.state.location.pathname).toBe('/sales');
  });
});

/** Resposta mínima do `fetch` para as rotas do teste — mesmo formato que o client consome. */
function jsonFetchResponse(body: unknown): {
  ok: boolean;
  status: number;
  text: () => Promise<string>;
} {
  return {
    ok: true,
    status: 200,
    text: async () => JSON.stringify(body),
  };
}
