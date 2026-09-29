import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import { routes } from '../../app/router';
import { AuthProvider } from './AuthProvider';
import { RequireAuth } from './RequireAuth';
import { LoginPage } from './pages/LoginPage';
import { TOKEN_STORAGE_KEY } from './session';

/** Resposta do `POST /auth/login` como o servidor a devolve (205): token + RBAC do usuário. */
const LOGIN_RESPONSE = {
  token: 'token-1',
  expiresAt: '2026-09-28T12:00:00Z',
  user: { id: 'u1', username: 'ana', displayName: 'Ana' },
  roles: ['MANAGER'],
  permissions: ['product.read', 'product.write'],
};

/** Resposta do `GET /auth/me` (207): é ela que revalida o token do refresh e relê o RBAC. */
const SESSION_RESPONSE = {
  user: { id: 'u1', username: 'ana', displayName: 'Ana' },
  roles: ['MANAGER'],
  permissions: ['product.read'],
  expiresAt: '2026-09-28T12:00:00Z',
};

/**
 * Resposta mínima do `fetch` (sem rede real): o client só lê `ok`, `status` e `text()`. Não usamos
 * `Response` de propósito — o jsdom não a implementa.
 */
function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  } as unknown as Response;
}

/** Monta o app com as mesmas rotas de produção, num router de memória (sem history do browser). */
function renderApp(initialEntries: string[]) {
  const router = createMemoryRouter(routes, { initialEntries });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

function fillLogin(username: string, password: string) {
  fireEvent.change(screen.getByLabelText('Usuário'), { target: { value: username } });
  fireEvent.change(screen.getByLabelText('Senha'), { target: { value: password } });
}

describe('autenticação do web', () => {
  it('login navega ao dashboard, guarda o token e mostra o usuário no cabeçalho', async () => {
    const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/auth/login' && init?.method === 'POST') {
        return jsonResponse(LOGIN_RESPONSE);
      }
      return jsonResponse({ code: 'NOT_FOUND' }, 404);
    });
    vi.stubGlobal('fetch', fetchStub);

    const router = renderApp(['/login']);
    fillLogin('ana', 'segredo');
    fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('token-1');
    expect(screen.getByText('Ana')).toBeInTheDocument();

    // Contrato do login: corpo cru e **sem** `X-Client` (o default do servidor é WEB).
    const loginCall = fetchStub.mock.calls.find(([url]) => String(url) === '/api/v1/auth/login');
    expect(loginCall).toBeDefined();
    expect(loginCall?.[1]?.method).toBe('POST');
    expect(JSON.parse(String(loginCall?.[1]?.body))).toEqual({ username: 'ana', password: 'segredo' });
    expect(new Headers(loginCall?.[1]?.headers).has('x-client')).toBe(false);
  });

  it('refresh mantém a sessão validando o token no /auth/me', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-da-aba');
    const authHeaders: (string | null)[] = [];
    const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/auth/me') {
        authHeaders.push(new Headers(init?.headers).get('authorization'));
        return jsonResponse(SESSION_RESPONSE);
      }
      return jsonResponse({ code: 'NOT_FOUND' }, 404);
    });
    vi.stubGlobal('fetch', fetchStub);

    const router = renderApp(['/']);

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(screen.getByText('Ana')).toBeInTheDocument();
    expect(authHeaders).toEqual(['Bearer token-da-aba']);
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('token-da-aba');
  });

  it('401 no /auth/me desloga e volta ao login', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-expirado');
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(
          { code: 'SESSION_EXPIRED', title: 'Sessão expirada', detail: 'sessão expirada' },
          401,
        ),
      ),
    );

    const router = renderApp(['/']);

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('401 em chamada autenticada derruba a sessão e volta ao login', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-vivo');
    const fetchStub = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/v1/auth/me') {
        return jsonResponse(SESSION_RESPONSE);
      }
      if (url === '/api/v1/probe') {
        return jsonResponse({ code: 'INVALID_CREDENTIALS', detail: 'token revogado' }, 401);
      }
      return jsonResponse({ code: 'NOT_FOUND' }, 404);
    });
    vi.stubGlobal('fetch', fetchStub);

    const router = renderAppWithProbe(['/']);

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(fetchStub).toHaveBeenCalledWith('/api/v1/probe', expect.objectContaining({ method: 'GET' }));
  });

  it('sem sessão, a rota protegida redireciona ao login', async () => {
    const fetchStub = vi.fn();
    vi.stubGlobal('fetch', fetchStub);

    const router = renderApp(['/']);

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    // Sem token não há o que validar: o /auth/me nem chega a ser chamado.
    expect(fetchStub).not.toHaveBeenCalled();
  });

  it('credencial inválida mostra a mensagem e fica no login', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(
          {
            code: 'INVALID_CREDENTIALS',
            title: 'Credenciais inválidas',
            detail: 'usuário ou senha inválidos',
          },
          401,
        ),
      ),
    );

    const router = renderApp(['/login']);
    fillLogin('ana', 'errada');
    fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Usuário ou senha inválidos.');
    expect(router.state.location.pathname).toBe('/login');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('Sair revoga a sessão, limpa o token e volta ao login', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-vivo');
    const fetchStub = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/v1/auth/me') {
        return jsonResponse(SESSION_RESPONSE);
      }
      if (url === '/api/v1/auth/logout') {
        return jsonResponse(undefined, 204);
      }
      return jsonResponse({ code: 'NOT_FOUND' }, 404);
    });
    vi.stubGlobal('fetch', fetchStub);

    const router = renderApp(['/']);
    fireEvent.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(fetchStub).toHaveBeenCalledWith(
      '/api/v1/auth/logout',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('logout limpa a sessão mesmo se a revogação falhar', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-vivo');
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === '/api/v1/auth/me') {
          return jsonResponse(SESSION_RESPONSE);
        }
        throw new Error('servidor fora do ar');
      }),
    );

    const router = renderApp(['/']);
    fireEvent.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });
});

/** Sonda de teste: uma rota protegida que dispara uma chamada autenticada ao montar. */
function Probe() {
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    api.get('/api/v1/probe').catch(() => setFailed(true));
  }, []);
  return <p>{failed ? 'probe falhou' : 'probe'}</p>;
}

/** Mesmas rotas de produção, trocando o dashboard por uma chamada autenticada controlada. */
const probeRoutes: RouteObject[] = [
  {
    element: <AuthProvider />,
    children: [
      { path: '/login', element: <LoginPage /> },
      {
        element: <RequireAuth />,
        children: [{ path: '/', element: <Probe /> }],
      },
    ],
  },
];

function renderAppWithProbe(initialEntries: string[]) {
  const router = createMemoryRouter(probeRoutes, { initialEntries });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}
