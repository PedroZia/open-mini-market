import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Outlet } from 'react-router';
import { setUnauthorizedHandler } from '../../api/client';
import { AuthContext, type AuthContextValue, type AuthSession, type AuthStatus, type AuthUser } from './AuthContext';
import { fetchCurrentSession, loginRequest, revokeSession } from './api/authApi';
import { clearToken, restoreToken, storeToken } from './session';

interface AuthState extends AuthSession {
  status: AuthStatus;
}

/** Sessão sem ninguém: o estado inicial e o destino de todo logout. */
const ANONYMOUS: AuthState = { status: 'anonymous', user: null, roles: [], permissions: [] };

/**
 * Estado inicial lido da aba: sem espelho não há sessão a validar, já nasce anônimo; com token, o
 * `GET /auth/me` do efeito decide (e a guarda mostra "Validando a sessão…" enquanto isso).
 */
function initialAuthState(): AuthState {
  return restoreToken() === null
    ? ANONYMOUS
    : { status: 'loading', user: null, roles: [], permissions: [] };
}

/**
 * Login e `CurrentSessionResponse` trazem a mesma trinca de sessão; só ela interessa ao cliente —
 * `mustChangePassword` fica de fora (decisão do 1202, igual à TUI).
 */
function toSession(payload: {
  user?: AuthUser;
  roles?: string[];
  permissions?: string[];
}): AuthSession {
  return {
    user: payload.user ?? null,
    roles: payload.roles ?? [],
    permissions: payload.permissions ?? [],
  };
}

/**
 * Provider da sessão da retaguarda (1202). Fica dentro do roteador (rota sem path) porque é daqui
 * que sai a guarda: `RequireAuth` lê o status e manda quem não tem sessão ao login.
 *
 * - o token mora em memória + `sessionStorage` (`session.ts`); no refresh o `GET /auth/me` decide
 *   se ele ainda vale e popula usuário/roles/permissions com a resposta do servidor;
 * - `401` em chamada autenticada cai no `endSession` registrado no client: derruba a sessão e a
 *   guarda redireciona. O `401` do login é anônimo (sem token) e não passa por aqui;
 * - logout é melhor esforço: revoga no servidor, mas limpa token e cache de qualquer jeito — o
 *   servidor fora do ar não pode prender o operador na sessão.
 */
export function AuthProvider() {
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>(initialAuthState);

  const endSession = useCallback(() => {
    clearToken();
    // Nada do usuário que saiu pode sobrar para o próximo login.
    queryClient.clear();
    setState(ANONYMOUS);
  }, [queryClient]);

  // 401 em requisição autenticada: o client avisa e a sessão cai (a guarda mostra o login).
  useEffect(() => {
    setUnauthorizedHandler(endSession);
    return () => setUnauthorizedHandler(null);
  }, [endSession]);

  // Refresh da aba: só o token veio do `sessionStorage`; quem diz se ele ainda vale é o `/auth/me`.
  useEffect(() => {
    if (state.status !== 'loading') {
      // Sessão já resolvida (anônima ou autenticada): não há o que validar.
      return;
    }

    let active = true;
    fetchCurrentSession()
      .then((session) => {
        if (active) {
          setState({ status: 'authenticated', ...toSession(session) });
        }
      })
      .catch(() => {
        // Token inválido/expirado — ou servidor fora do ar: sem sessão confirmada, volta ao login.
        if (active) {
          endSession();
        }
      });

    return () => {
      active = false;
    };
  }, [endSession, state.status]);

  const login = useCallback(async (username: string, password: string) => {
    const response = await loginRequest(username, password);
    if (response.token === undefined || response.token === '') {
      // 200 fora do contrato: sem token não há sessão, e insistir só confundiria a tela.
      throw new Error('login sem token na resposta');
    }

    storeToken(response.token);
    setState({ status: 'authenticated', ...toSession(response) });
  }, []);

  const logout = useCallback(async () => {
    try {
      await revokeSession();
    } catch {
      // Melhor esforço: a revogação falhou, mas a sessão local sai mesmo assim (o token órfão
      // expira sozinho no servidor).
    } finally {
      endSession();
    }
  }, [endSession]);

  const value = useMemo<AuthContextValue>(
    () => ({
      status: state.status,
      user: state.user,
      roles: state.roles,
      permissions: state.permissions,
      login,
      logout,
    }),
    [state, login, logout],
  );

  return (
    <AuthContext.Provider value={value}>
      <Outlet />
    </AuthContext.Provider>
  );
}
