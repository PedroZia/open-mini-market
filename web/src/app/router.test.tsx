import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it } from 'vitest';
import { routes } from './router';

describe('rota inicial', () => {
  it('renderiza a página Início', async () => {
    const router = createMemoryRouter(routes, { initialEntries: ['/'] });

    render(<RouterProvider router={router} />);

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument();
  });
});
