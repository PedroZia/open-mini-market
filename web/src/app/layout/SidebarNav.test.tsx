import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../features/auth/AuthContext';
import { SidebarNav } from './SidebarNav';

/**
 * Navegação com permissão (1208a/1208b): "Usuários" e "Papéis" só existem para quem tem `user.read`
 * na sessão — é assim que GERENTE e OPERADOR não veem a administração de acessos. Sem permissão
 * declarada, o item continua aparecendo para todo mundo. O servidor é quem barra de verdade (403 na
 * rota).
 */

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

function renderNav(permissions: string[]) {
  render(
    <MemoryRouter>
      <AuthContext.Provider value={sessionWith(permissions)}>
        <SidebarNav />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe('SidebarNav — itens com permissão', () => {
  it('mostra Usuários para quem tem user.read (ADMIN)', () => {
    renderNav(['user.read', 'user.write']);

    expect(screen.getByRole('link', { name: 'Usuários' })).toHaveAttribute('href', '/users');
  });

  it('esconde Usuários de GERENTE/OPERADOR sem user.read', () => {
    renderNav(['product.read', 'sale.create', 'customer.write']);

    expect(screen.queryByRole('link', { name: 'Usuários' })).toBeNull();
    // Os itens sem permissão declarada continuam na navegação.
    expect(screen.getByRole('link', { name: 'Produtos' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Início' })).toBeInTheDocument();
  });

  it('mostra Papéis para quem tem user.read (ADMIN)', () => {
    renderNav(['user.read', 'role.write']);

    expect(screen.getByRole('link', { name: 'Papéis' })).toHaveAttribute('href', '/roles');
  });

  it('esconde Papéis de quem não tem user.read', () => {
    renderNav(['product.read', 'sale.create']);

    expect(screen.queryByRole('link', { name: 'Papéis' })).toBeNull();
  });
});
