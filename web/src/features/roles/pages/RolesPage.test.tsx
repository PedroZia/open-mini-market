import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { createQueryClient } from '../../../app/query-client';
import { ToastProvider } from '../../../shared/ui/Toast';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { RolesPage } from './RolesPage';

/**
 * Papéis (1208b) com a rede stubada — o setup do web desliga o `fetch` real. `GET /roles` entrega
 * o catálogo com as permissões de cada papel e `PUT /roles/{code}/permissions` substitui o
 * conjunto; cada teste confere a requisição que a tela fez (URL, método e corpo), como manda o
 * contrato (§9.3). O catálogo dos checkboxes é a união das permissões de todos os papéis.
 */

/** Stub do `fetch` no nível em que o client o usa: `ok`, `status` e `text()`. */
type FetchStub = Mock<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>;

/** Chamada registrada pelo stub: o par que o client passou ao `fetch`. */
type StubCall = [input: RequestInfo | URL, init?: RequestInit | undefined];

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  } as unknown as Response;
}

/** Papel como o stub o guarda; espelha o contrato sem repetir o tipo do api-client. */
interface StubRole {
  code: string;
  name: string;
  description: string;
  system: boolean;
  permissions: string[];
}

/** Catálogo de `GET /roles`: a união cobre os grupos cash, product, role, sale e user. */
const ROLES: StubRole[] = [
  {
    code: 'ADMIN',
    name: 'Administrador',
    description: 'Acesso total ao sistema.',
    system: true,
    permissions: [
      'cash.open',
      'product.read',
      'product.write',
      'role.write',
      'sale.cancel',
      'sale.create',
      'user.read',
      'user.write',
    ],
  },
  {
    code: 'GERENTE',
    name: 'Gerente',
    description: 'Retaguarda e supervisão.',
    system: true,
    permissions: ['product.read', 'product.write', 'sale.cancel', 'user.read'],
  },
  {
    code: 'OPERADOR',
    name: 'Operador',
    description: 'Frente de caixa.',
    system: true,
    permissions: ['sale.create'],
  },
];

interface StubBackendOptions {
  roles?: StubRole[];
  /** Status da leitura do catálogo; sem ele a lista vai com 200. */
  listStatus?: number;
  /** Resposta do `PUT /roles/{code}/permissions`; sem ela o stub troca o conjunto e devolve 200. */
  replace?: { status: number; body: unknown };
}

/**
 * Serve as duas rotas do recurso como o servidor as serve; qualquer rota inesperada falha o teste.
 * O PUT guarda o conjunto enviado — é o estado que a releitura devolve.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let roles = [...(options.roles ?? ROLES)];

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const pathname = new URL(url, 'http://localhost').pathname;

    if (pathname === '/api/v1/roles' && method === 'GET') {
      return jsonResponse(roles, options.listStatus ?? 200);
    }

    const replaceRoute = /^\/api\/v1\/roles\/([^/]+)\/permissions$/.exec(pathname);
    if (replaceRoute !== null && method === 'PUT') {
      if (options.replace !== undefined) {
        return jsonResponse(options.replace.body, options.replace.status);
      }
      const code = replaceRoute[1] ?? '';
      const current = roles.find((role) => role.code === code);
      if (current === undefined) {
        return jsonResponse(
          { status: 404, code: 'ROLE_NOT_FOUND', detail: `papel ${code} não encontrado` },
          404,
        );
      }
      const body = JSON.parse(String(init?.body)) as { permissions: string[] };
      const updated: StubRole = { ...current, permissions: body.permissions };
      roles = roles.map((role) => (role.code === code ? updated : role));
      return jsonResponse(updated);
    }

    throw new Error(`fetch inesperado: ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
}

/** Chamadas que batem com método e caminho exatos, na ordem em que a tela as pediu. */
function calls(fetchStub: FetchStub, method: string, pathname: string): StubCall[] {
  return fetchStub.mock.calls.filter(([input, init]) => {
    const url = new URL(String(input), 'http://localhost');
    return (init?.method ?? 'GET') === method && url.pathname === pathname;
  });
}

/** Última chamada do par método/caminho; o teste falha claro quando nenhuma aconteceu. */
function lastCall(fetchStub: FetchStub, method: string, pathname: string): StubCall {
  const matched = calls(fetchStub, method, pathname);
  const call = matched.at(-1);
  if (call === undefined) {
    throw new Error(`nenhuma chamada ${method} ${pathname}`);
  }
  return call;
}

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

/**
 * Monta a página com sessão e cache próprios; `retry` desligado para o 403 não repetir. Com
 * `withToast`, o cache é o da aplicação (`createQueryClient`), que liga o toast global — é por ele
 * que o sucesso da troca de permissões aparece.
 */
function renderPage(
  permissions: string[] = ['user.read', 'role.write'],
  options: { withToast?: boolean } = {},
) {
  const client =
    options.withToast === true
      ? createQueryClient()
      : new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={sessionWith(permissions)}>
        {options.withToast === true ? (
          <ToastProvider>
            <RolesPage />
          </ToastProvider>
        ) : (
          <RolesPage />
        )}
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

/** Espera a lista aparecer; o cartão do ADMIN é a âncora dos testes. */
async function waitForList(): Promise<void> {
  await screen.findByRole('heading', { name: 'Administrador' });
}

describe('RolesPage — lista', () => {
  it('renderiza papel, código, selo do sistema e as permissões atuais', async () => {
    stubBackend();
    renderPage();
    await waitForList();

    expect(screen.getByRole('heading', { name: 'Gerente' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Operador' })).toBeInTheDocument();
    expect(screen.getByText('Retaguarda e supervisão.')).toBeInTheDocument();
    expect(screen.getByText('ADMIN')).toBeInTheDocument();

    // Permissões do papel em chips: 'product.read' está em ADMIN e GERENTE; 'cash.open' só em ADMIN.
    expect(screen.getAllByText('product.read')).toHaveLength(2);
    expect(screen.getByText('cash.open')).toBeInTheDocument();

    const admin = screen.getByRole('heading', { name: 'Administrador' }).closest('article');
    expect(admin).not.toBeNull();
    expect(within(admin as HTMLElement).getByText('Do sistema')).toBeInTheDocument();
    expect(
      within(admin as HTMLElement).getByRole('heading', { name: 'Permissões (8)' }),
    ).toBeInTheDocument();

    // Papel do sistema é editável; o que não pode ficar vazio é a lista de permissões.
    expect(screen.getByRole('heading', { name: 'Permissões (4)' })).toBeInTheDocument();
    expect(screen.queryByText('Sem permissões.')).toBeNull();
  });

  it('mostra "Sem permissões" para papel sem nenhuma', async () => {
    stubBackend({
      roles: [{ ...(ROLES[2] as StubRole), permissions: [] }],
    });
    renderPage();
    await screen.findByRole('heading', { name: 'Operador' });

    expect(screen.getByRole('heading', { name: 'Permissões (0)' })).toBeInTheDocument();
    expect(screen.getByText('Sem permissões.')).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ listStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Administrador' })).toBeNull();
  });
});

describe('RolesPage — edição do mapa', () => {
  it('abre com as permissões atuais e envia o conjunto marcado', async () => {
    const fetchStub = stubBackend();
    renderPage(['user.read', 'role.write'], { withToast: true });
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Editar permissões de Gerente' }));

    const dialog = await screen.findByRole('dialog');
    // O catálogo é a união das permissões de todos os papéis, agrupada pelo prefixo antes do ponto.
    expect(within(dialog).getByRole('group', { name: 'product' })).toBeInTheDocument();
    expect(within(dialog).getByRole('group', { name: 'cash' })).toBeInTheDocument();
    // O que o papel tem chega marcado; o resto do catálogo, não.
    expect(within(dialog).getByRole('checkbox', { name: 'user.read' })).toBeChecked();
    expect(within(dialog).getByRole('checkbox', { name: 'product.write' })).toBeChecked();
    expect(within(dialog).getByRole('checkbox', { name: 'cash.open' })).not.toBeChecked();

    // Ganha abrir caixa e perde escrita de produto.
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'cash.open' }));
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'product.write' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Salvar' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'PUT', '/api/v1/roles/GERENTE/permissions')).toHaveLength(1),
    );
    const [input, init] = lastCall(fetchStub, 'PUT', '/api/v1/roles/GERENTE/permissions');
    expect(String(input)).toBe('/api/v1/roles/GERENTE/permissions');
    // O PUT substitui o conjunto (não soma); os códigos viajam em ordem estável.
    expect(JSON.parse(String(init?.body))).toEqual({
      permissions: ['cash.open', 'product.read', 'sale.cancel', 'user.read'],
    });

    // Sucesso: avisa no toast, fecha o modal e relê a lista do servidor.
    expect(await screen.findByText('Permissões atualizadas.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await screen.findByRole('heading', { name: 'Permissões (4)' })).toBeInTheDocument();
  });

  it('mostra o 400 UNKNOWN_PERMISSION no banner, sem tela branca', async () => {
    const fetchStub = stubBackend({
      replace: {
        status: 400,
        body: {
          status: 400,
          code: 'UNKNOWN_PERMISSION',
          detail: 'código de permissão desconhecido: cash.open',
        },
      },
    });
    renderPage(['user.read', 'role.write']);
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Editar permissões de Gerente' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Salvar' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Alguma permissão enviada não existe no catálogo do servidor. Atualize a lista e tente de novo.',
    );
    // O modal continua aberto para o operador corrigir a seleção.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(calls(fetchStub, 'PUT', '/api/v1/roles/GERENTE/permissions')).toHaveLength(1);
  });

  it('sem role.write a leitura continua e a edição não aparece', async () => {
    stubBackend();
    renderPage(['user.read']);

    await waitForList();
    expect(screen.queryByRole('button', { name: /Editar permissões/ })).toBeNull();
    // A leitura segue completa: papéis e permissões na tela.
    expect(screen.getByText('role.write')).toBeInTheDocument();
    expect(screen.getAllByText('product.read')).toHaveLength(2);
  });
});
