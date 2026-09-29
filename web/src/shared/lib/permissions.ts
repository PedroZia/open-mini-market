import { useMemo } from 'react';
import { useAuth } from '../../features/auth/AuthContext';

/**
 * Autorização na UI (§10.3): o `permissions` do `/auth/me` diz o que o operador pode, mas quem
 * manda é o servidor — esconder/desabilitar ação aqui é só para não frustrar quem clica e leva
 * 403. Código é string do catálogo (`product.write`, `sale.cancel`...), nunca papel.
 */

/** `true` quando a lista de permissões da sessão contém o código pedido. */
export function hasPermission(granted: readonly string[], code: string): boolean {
  return granted.includes(code);
}

/** Permissão da sessão corrente; reage a login/logout porque lê o contexto de auth. */
export function usePermission(code: string): boolean {
  const { permissions } = useAuth();
  return useMemo(() => hasPermission(permissions, code), [permissions, code]);
}
