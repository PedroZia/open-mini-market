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
