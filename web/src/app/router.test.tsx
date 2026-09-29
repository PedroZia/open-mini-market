import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it } from 'vitest';
import { routes } from './router';

describe('rota inicial', () => {
  it('renderiza a página Início dentro do layout, com a navegação lateral', async () => {
    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(<RouterProvider router={router} />);

    // Casca do 1201b: cabeçalho, navegação lateral e área de conteúdo.
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Navegação principal' })).toBeInTheDocument();

    // O conteúdo da rota entra pelo Outlet, dentro do <main> do layout.
    const heading = await screen.findByRole('heading', { name: 'Início' });
    expect(screen.getByRole('main')).toContainElement(heading);

    // "Início" é a rota atual: item ativo marcado para leitor de tela e para o teclado.
    expect(screen.getByRole('link', { name: 'Início' })).toHaveAttribute('aria-current', 'page');

    // Atalho de conteúdo: primeiro Tab do operador pula a navegação.
    expect(screen.getByRole('link', { name: 'Ir para o conteúdo' })).toHaveAttribute(
      'href',
      '#conteudo',
    );
  });
});
