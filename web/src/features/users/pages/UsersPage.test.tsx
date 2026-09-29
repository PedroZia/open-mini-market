import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { createQueryClient } from '../../../app/query-client';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { ToastProvider } from '../../../shared/ui/Toast';
import { UsersPage } from './UsersPage';

/**
 * Usuários (1208a) com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala o
 * seu stub e confere a requisição que a tela fez (URL, método e corpo), como manda o contrato
 * (§9.3): `GET /users` com `search`/`active`/`sort`/`page`/`size`, `POST`/`PUT` de usuário, os POST
 * de disable/enable/password-reset, o DELETE de sessões (204) e o `GET /roles` do formulário.
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

/** 204 sem corpo, como o DELETE de sessões responde. */
function noContent(): Response {
  return { ok: true, status: 204, text: async () => '' } as unknown as Response;
}

/** Usuário como o stub o guarda; espelha o contrato sem repetir o tipo do api-client. */
interface StubUser {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
  status: string;
  mustChangePassword: boolean;
}

const USERS: StubUser[] = [
  {
    id: 'u1',
    username: 'ana',
    displayName: 'Ana Souza',
    roles: ['ADMIN'],
    status: 'ACTIVE',
    mustChangePassword: false,
  },
  {
    id: 'u2',
    username: 'bruno',
    displayName: 'Bruno Lima',
    roles: ['GERENTE'],
    status: 'ACTIVE',
    mustChangePassword: true,
  },
  {
    id: 'u3',
    username: 'carla',
    displayName: 'Carla Dias',
    roles: ['OPERADOR'],
    status: 'DISABLED',
    mustChangePassword: false,
  },
];

/** Catálogo de papéis como `GET /roles` devolve (passo 114). */
const ROLES = [
  { code: 'ADMIN', name: 'Administrador', system: true, permissions: ['user.read'] },
  { code: 'GERENTE', name: 'Gerente', system: true, permissions: ['product.read'] },
  { code: 'OPERADOR', name: 'Operador', system: true, permissions: ['sale.create'] },
];

interface StubBackendOptions {
  users?: StubUser[];
  /** Status da leitura da lista; sem ele a lista vai com 200. */
  listStatus?: number;
  /** Resposta do `POST /users`; sem ela o stub cria o registro e devolve 201. */
  create?: { status: number; body: unknown };
  /** Resposta do `POST /users/{id}/disable`; sem ela o stub muda o status e devolve 200. */
  disable?: { status: number; body: unknown };
  /** Resposta do `POST /users/{id}/password-reset`; sem ela o stub marca mustChangePassword. */
  reset?: { status: number; body: unknown };
}

/**
 * Serve cada rota do recurso como o servidor serve; qualquer rota inesperada falha o teste. A lista
 * aplica busca, filtro de situação, ordenação e paginação como o `ListUsersUseCase`.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let users: StubUser[] = [...(options.users ?? USERS)];

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const parsed = new URL(url, 'http://localhost');
    const pathname = parsed.pathname;

    if (pathname === '/api/v1/roles' && method === 'GET') {
      return jsonResponse(ROLES);
    }

    if (pathname === '/api/v1/users' && method === 'GET') {
      const search = parsed.searchParams.get('search')?.toLowerCase() ?? '';
      const activeParam = parsed.searchParams.get('active');
      const sortParam = parsed.searchParams.get('sort') ?? 'username,asc';
      const page = Number(parsed.searchParams.get('page') ?? 0);
      const size = Number(parsed.searchParams.get('size') ?? 20);

      let found = users.filter((user) => {
        const matchesActive =
          activeParam === null || (user.status === 'ACTIVE') === (activeParam === 'true');
        const matchesSearch =
          search === '' ||
          user.username.toLowerCase().includes(search) ||
          user.displayName.toLowerCase().includes(search);
        return matchesActive && matchesSearch;
      });

      const [field = 'username', direction = 'asc'] = sortParam.split(',');
      const factor = direction === 'desc' ? -1 : 1;
      found = [...found].sort(
        (left, right) =>
          factor *
          (field === 'displayname'
            ? left.displayName.localeCompare(right.displayName)
            : left.username.localeCompare(right.username)),
      );

      return jsonResponse(
        {
          items: found.slice(page * size, page * size + size),
          page,
          size,
          totalItems: found.length,
          totalPages: Math.ceil(found.length / size),
        },
        options.listStatus ?? 200,
      );
    }

    if (pathname === '/api/v1/users' && method === 'POST') {
      if (options.create !== undefined) {
        return jsonResponse(options.create.body, options.create.status);
      }
      const body = JSON.parse(String(init?.body)) as {
        username: string;
        displayName: string;
        password: string;
        roleCodes?: string[];
      };
      const created: StubUser = {
        id: 'u9',
        username: body.username,
        displayName: body.displayName,
        roles: body.roleCodes ?? [],
        status: 'ACTIVE',
        mustChangePassword: false,
      };
      users = [...users, created];
      return jsonResponse(created, 201);
    }

    const statusRoute = /^\/api\/v1\/users\/([^/]+)\/(disable|enable)$/.exec(pathname);
    if (statusRoute !== null && method === 'POST') {
      const id = statusRoute[1] ?? '';
      const nextStatus = statusRoute[2] === 'disable' ? 'DISABLED' : 'ACTIVE';
      const current = users.find((user) => user.id === id);
      if (current === undefined) {
        return jsonResponse(
          { status: 404, code: 'USER_NOT_FOUND', detail: `usuário ${id} não encontrado` },
          404,
        );
      }
      if (options.disable !== undefined && nextStatus === 'DISABLED') {
        return jsonResponse(options.disable.body, options.disable.status);
      }
      const updated: StubUser = { ...current, status: nextStatus };
      users = users.map((user) => (user.id === id ? updated : user));
      return jsonResponse(updated);
    }

    const resetRoute = /^\/api\/v1\/users\/([^/]+)\/password-reset$/.exec(pathname);
    if (resetRoute !== null && method === 'POST') {
      if (options.reset !== undefined) {
        return jsonResponse(options.reset.body, options.reset.status);
      }
      const id = resetRoute[1] ?? '';
      const current = users.find((user) => user.id === id);
      if (current === undefined) {
        return jsonResponse(
          { status: 404, code: 'USER_NOT_FOUND', detail: `usuário ${id} não encontrado` },
          404,
        );
      }
      const updated: StubUser = { ...current, mustChangePassword: true };
      users = users.map((user) => (user.id === id ? updated : user));
      return jsonResponse(updated);
    }

    const sessionsRoute = /^\/api\/v1\/users\/([^/]+)\/sessions$/.exec(pathname);
    if (sessionsRoute !== null && method === 'DELETE') {
      return noContent();
    }

    const item = /^\/api\/v1\/users\/([^/]+)$/.exec(pathname);
    if (item !== null && method === 'PUT') {
      const id = item[1] ?? '';
      const body = JSON.parse(String(init?.body)) as {
        displayName: string;
        roleCodes?: string[];
      };
      const current = users.find((user) => user.id === id);
      if (current === undefined) {
        return jsonResponse(
          { status: 404, code: 'USER_NOT_FOUND', detail: `usuário ${id} não encontrado` },
          404,
        );
      }
      const updated: StubUser = {
        ...current,
        displayName: body.displayName,
        roles: body.roleCodes ?? [],
      };
      users = users.map((user) => (user.id === id ? updated : user));
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

/** Query da última leitura da lista — é nela que a tela prova o que pediu ao servidor. */
function lastListQuery(fetchStub: FetchStub): URLSearchParams {
  const last = calls(fetchStub, 'GET', '/api/v1/users').at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/users');
  }
  return new URL(String(last[0]), 'http://localhost').searchParams;
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
 * que o sucesso das ações e o erro global do 409 aparecem.
 */
function renderPage(
  permissions: string[] = ['user.read', 'user.write', 'user.session.revoke'],
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
            <UsersPage />
          </ToastProvider>
        ) : (
          <UsersPage />
        )}
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

/** Espera a lista aparecer; a linha da ana é a âncora dos testes. */
async function waitForList(): Promise<void> {
  await screen.findByRole('cell', { name: 'ana' });
}

describe('UsersPage — lista', () => {
  it('renderiza usuário, nome, papéis, situação e senha a trocar', async () => {
    stubBackend();
    renderPage();
    await waitForList();

    expect(screen.getByRole('cell', { name: 'Ana Souza' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'ADMIN' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'GERENTE' })).toBeInTheDocument();
    // Situação e o indicador de senha temporária convivem na mesma linha do bruno.
    const bruno = screen.getByRole('cell', { name: 'bruno' }).closest('tr');
    expect(bruno).not.toBeNull();
    expect(within(bruno as HTMLElement).getByRole('cell', { name: 'Ativo' })).toBeInTheDocument();
    expect(within(bruno as HTMLElement).getByRole('cell', { name: 'Sim' })).toBeInTheDocument();
    // Carla está desativada e não tem senha a trocar.
    const carla = screen.getByRole('cell', { name: 'carla' }).closest('tr');
    expect(within(carla as HTMLElement).getByRole('cell', { name: 'Desativado' })).toBeInTheDocument();
  });

  it('busca, filtra por situação e ordena pelo servidor', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.change(screen.getByLabelText('Buscar'), { target: { value: 'bruno' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('search')).toBe('bruno'));
    expect(lastListQuery(fetchStub).get('page')).toBe('0');
    expect(lastListQuery(fetchStub).get('size')).toBe('20');

    fireEvent.change(screen.getByLabelText('Buscar'), { target: { value: '' } });
    await screen.findByRole('cell', { name: 'ana' });

    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'active' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('active')).toBe('true'));

    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'inactive' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('active')).toBe('false'));
    expect(await screen.findByRole('cell', { name: 'carla' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'all' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('active')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Usuário' }));
    await waitFor(() => expect(lastListQuery(fetchStub).get('sort')).toBe('username,asc'));

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Usuário' }));
    await waitFor(() => expect(lastListQuery(fetchStub).get('sort')).toBe('username,desc'));
  });

  it('pede a próxima página ao servidor', async () => {
    // 25 usuários com página de 20: a segunda página existe e o botão leva até ela.
    const many = Array.from({ length: 25 }, (_, index) => ({
      id: `u${index + 1}`,
      username: `user${String(index + 1).padStart(2, '0')}`,
      displayName: `Usuário ${String(index + 1).padStart(2, '0')}`,
      roles: ['OPERADOR'],
      status: 'ACTIVE',
      mustChangePassword: false,
    }));
    const fetchStub = stubBackend({ users: many });
    renderPage();

    expect(await screen.findByRole('cell', { name: 'user01' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: 'user21' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));

    await waitFor(() => expect(lastListQuery(fetchStub).get('page')).toBe('1'));
    expect(await screen.findByRole('cell', { name: 'user21' })).toBeInTheDocument();
    expect(screen.getByText('Página 2 de 2 · 25 itens')).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ listStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('UsersPage — criação', () => {
  it('envia usuário, nome, senha e papéis do contrato', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Novo usuário' }));
    fireEvent.change(screen.getByLabelText('Usuário'), { target: { value: 'diego' } });
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Diego Alves' } });
    fireEvent.change(screen.getByLabelText('Senha'), { target: { value: 'segredo12' } });
    // Os papéis chegam de `GET /roles`: o checkbox só existe depois da resposta.
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Operador' }));
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', '/api/v1/users')).toHaveLength(1));

    const [input, init] = lastCall(fetchStub, 'POST', '/api/v1/users');
    expect(String(input)).toBe('/api/v1/users');
    expect(JSON.parse(String(init?.body))).toEqual({
      username: 'diego',
      displayName: 'Diego Alves',
      password: 'segredo12',
      roleCodes: ['OPERADOR'],
    });

    // Quem diz o estado final é o servidor: a lista foi lida de novo e o modal fechou.
    expect(await screen.findByRole('cell', { name: 'diego' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('bloqueia senha com menos de 8 caracteres, sem sair da tela', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Novo usuário' }));
    fireEvent.change(screen.getByLabelText('Usuário'), { target: { value: 'diego' } });
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Diego Alves' } });
    fireEvent.change(screen.getByLabelText('Senha'), { target: { value: '1234567' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('A senha deve ter ao menos 8 caracteres.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/users')).toHaveLength(0);
  });

  it('põe o 409 de usuário em uso no campo username, com o modal aberto', async () => {
    const fetchStub = stubBackend({
      create: {
        status: 409,
        body: {
          status: 409,
          code: 'USERNAME_ALREADY_EXISTS',
          detail: 'username ana já está em uso',
        },
      },
    });
    renderPage();
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Novo usuário' }));
    fireEvent.change(screen.getByLabelText('Usuário'), { target: { value: 'ana' } });
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Ana Maria' } });
    fireEvent.change(screen.getByLabelText('Senha'), { target: { value: 'segredo12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('username ana já está em uso');
    // O modal continua aberto para corrigir o username.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/users')).toHaveLength(1);
  });
});

describe('UsersPage — edição', () => {
  it('semeia o formulário e manda o PUT sem username', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Editar ana' }));

    const username = await screen.findByLabelText('Usuário');
    expect(username).toHaveValue('ana');
    expect(username).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Nome')).toHaveValue('Ana Souza');
    // Os papéis vêm de `GET /roles`; o que o usuário tem chega marcado depois da resposta.
    expect(await screen.findByRole('checkbox', { name: 'Administrador' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Operador' })).not.toBeChecked();

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Ana Souza Lima' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'PUT', '/api/v1/users/u1')).toHaveLength(1));

    const body = JSON.parse(String(lastCall(fetchStub, 'PUT', '/api/v1/users/u1')[1]?.body));
    // Username e senha não mudam por este PUT: fora do corpo.
    expect(body).toEqual({ displayName: 'Ana Souza Lima', roleCodes: ['ADMIN'] });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await screen.findByRole('cell', { name: 'Ana Souza Lima' })).toBeInTheDocument();
  });
});

describe('UsersPage — desativar e habilitar', () => {
  it('desativa e habilita pelo servidor, com aviso de sucesso', async () => {
    const fetchStub = stubBackend();
    renderPage(['user.read', 'user.write'], { withToast: true });
    await waitForList();
    const before = calls(fetchStub, 'GET', '/api/v1/users').length;

    fireEvent.click(screen.getByRole('button', { name: 'Desativar ana' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/users/u1/disable')).toHaveLength(1),
    );
    expect(await screen.findByText('Usuário desativado.')).toBeInTheDocument();
    // A lista é relida do servidor: a linha da ana passa a mostrar Desativado.
    await waitFor(() => expect(calls(fetchStub, 'GET', '/api/v1/users').length).toBeGreaterThan(before));
    await waitFor(() => {
      const ana = screen.getByRole('cell', { name: 'ana' }).closest('tr');
      expect(within(ana as HTMLElement).getByRole('cell', { name: 'Desativado' })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Habilitar ana' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/users/u1/enable')).toHaveLength(1),
    );
    expect(await screen.findByText('Usuário habilitado.')).toBeInTheDocument();
  });

  it('mostra o 409 do último ADMIN ativo e recarrega a lista', async () => {
    const fetchStub = stubBackend({
      disable: {
        status: 409,
        body: {
          status: 409,
          code: 'CONFLICT',
          detail: 'não é possível desativar o último ADMIN ativo',
        },
      },
    });
    renderPage(['user.read', 'user.write'], { withToast: true });
    await waitForList();
    const before = calls(fetchStub, 'GET', '/api/v1/users').length;

    fireEvent.click(screen.getByRole('button', { name: 'Desativar ana' }));

    expect(
      await screen.findByText('A operação não cabe no estado atual do registro. Recarregue e tente de novo.'),
    ).toBeInTheDocument();
    // A recusa vem de uma regra de estado do servidor: a tela relê a lista.
    await waitFor(() => expect(calls(fetchStub, 'GET', '/api/v1/users').length).toBeGreaterThan(before));
  });
});

describe('UsersPage — senha e sessões', () => {
  it('exige 8 caracteres no reset e manda a nova senha', async () => {
    const fetchStub = stubBackend();
    renderPage(['user.read', 'user.write'], { withToast: true });
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Resetar senha de ana' }));
    fireEvent.change(await screen.findByLabelText('Nova senha'), { target: { value: '1234567' } });
    fireEvent.click(screen.getByRole('button', { name: 'Redefinir' }));

    expect(await screen.findByText('A senha deve ter ao menos 8 caracteres.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/users/u1/password-reset')).toHaveLength(0);

    fireEvent.change(screen.getByLabelText('Nova senha'), { target: { value: 'novasenha1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Redefinir' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/users/u1/password-reset')).toHaveLength(1),
    );
    const body = JSON.parse(
      String(lastCall(fetchStub, 'POST', '/api/v1/users/u1/password-reset')[1]?.body),
    );
    expect(body).toEqual({ newPassword: 'novasenha1' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(
      await screen.findByText('Senha redefinida; o usuário vai trocá-la no próximo acesso.'),
    ).toBeInTheDocument();
  });

  it('revoga as sessões (204) e avisa no toast', async () => {
    const fetchStub = stubBackend();
    renderPage(['user.read', 'user.session.revoke'], { withToast: true });
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Revogar sessões de ana' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'DELETE', '/api/v1/users/u1/sessions')).toHaveLength(1),
    );
    expect(await screen.findByText('Sessões revogadas.')).toBeInTheDocument();
  });
});

describe('UsersPage — permissões de escrita', () => {
  it('esconde criação, edição, reset e desativação sem user.write', async () => {
    stubBackend();
    renderPage(['user.read', 'user.session.revoke']);
    await waitForList();

    expect(screen.queryByRole('button', { name: 'Novo usuário' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Editar|Desativar|Resetar senha/ })).toBeNull();
    // A revogação de sessão tem permissão própria e continua à mão.
    expect(screen.getByRole('button', { name: 'Revogar sessões de ana' })).toBeInTheDocument();
  });

  it('esconde a revogação de sessões sem user.session.revoke', async () => {
    stubBackend();
    renderPage(['user.read', 'user.write']);
    await waitForList();

    expect(screen.queryByRole('button', { name: /Revogar sessões/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Desativar ana' })).toBeInTheDocument();
  });
});
