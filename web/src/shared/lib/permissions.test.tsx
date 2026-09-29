import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../features/auth/AuthContext';
import { hasPermission, usePermission } from './permissions';

/** Sessão mínima para o hook: só o `permissions` do `/auth/me` importa aqui. */
function sessionWith(permissions: string[]): AuthContextValue {
  return {
    status: 'authenticated',
    user: { id: 'u1', username: 'ana' },
    roles: [],
    permissions,
    login: async () => {},
    logout: async () => {},
  };
}

function wrapper(permissions: string[]) {
  const value = sessionWith(permissions);
  return function Wrapper({ children }: { children: ReactNode }) {
    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
  };
}

describe('hasPermission', () => {
  it('casa o código exato do catálogo', () => {
    expect(hasPermission(['product.read', 'product.write'], 'product.write')).toBe(true);
    expect(hasPermission(['product.read'], 'product.write')).toBe(false);
    expect(hasPermission([], 'product.write')).toBe(false);
  });
});

describe('usePermission', () => {
  it('concede quando a sessão tem o código', () => {
    const { result } = renderHook(() => usePermission('product.write'), {
      wrapper: wrapper(['product.read', 'product.write']),
    });

    expect(result.current).toBe(true);
  });

  it('nega quando a sessão não tem o código', () => {
    const { result } = renderHook(() => usePermission('product.write'), {
      wrapper: wrapper(['product.read']),
    });

    expect(result.current).toBe(false);
  });
});
