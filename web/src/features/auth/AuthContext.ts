import type { components } from '@minimarket/api-client';
import { createContext, useContext } from 'react';

/** Usuário da sessão — tipo do contrato (`LoginUser`), nunca um payload escrito à mão. */
export type AuthUser = components['schemas']['LoginUser'];

/** `loading` é só a inicialização (validando o token da aba); depois vira `anonymous` ou `authenticated`. */
export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

/** Sessão corrente no cliente: quem entrou e o que o servidor disse que ele pode (RBAC). */
export interface AuthSession {
  user: AuthUser | null;
  roles: string[];
  permissions: string[];
}

/** O que o provider expõe: a sessão, o status e as duas ações que a mudam. */
export interface AuthContextValue extends AuthSession {
  status: AuthStatus;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

/** Sessão corrente para as telas; fora do `AuthProvider` é erro de montagem. */
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (value === null) {
    throw new Error('useAuth usado fora do AuthProvider');
  }
  return value;
}
