import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
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
});
