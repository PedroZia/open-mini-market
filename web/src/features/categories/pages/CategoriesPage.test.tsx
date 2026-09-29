import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { createQueryClient } from '../../../app/query-client';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { ToastProvider } from '../../../shared/ui/Toast';
import { CategoriesPage } from './CategoriesPage';

/**
 * Categorias com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala o seu
 * stub e confere a requisição que a tela fez (URL, método, cabeçalhos e corpo), como manda o
 * contrato (§9.3): a lista é array simples, o `PUT` não tem `If-Match` e o `DELETE` responde 204.
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

const CATEGORIES = [
  { id: 'c1', name: 'Mercearia', active: true, sortOrder: 0 },
  { id: 'c2', name: 'Bebidas', active: true, sortOrder: 1, parentId: 'c1' },
  { id: 'c3', name: 'Descontinuados', active: false, sortOrder: 9 },
];

/** Categoria como o stub a guarda; espelha o contrato sem repetir o tipo do api-client. */
interface StubCategory {
  id: string;
  name: string;
  parentId?: string;
  active: boolean;
  sortOrder: number;
}

interface StubBackendOptions {
  categories?: StubCategory[];
  /** Status da leitura da lista; sem ele a lista vai com 200. */
  listStatus?: number;
  /** Resposta do `POST /categories`; sem ela o stub cria o registro e devolve 201. */
  create?: { status: number; body: unknown };
  /** Resposta do `PUT /categories/{id}`; sem ela o stub grava o corpo e devolve 200. */
  update?: { status: number; body: unknown };
  /** Resposta do `DELETE /categories/{id}`; sem ela o stub desativa e devolve 204. */
  remove?: { status: number; body: unknown };
}

/** Serve cada rota do recurso como o servidor serve; qualquer rota inesperada falha o teste. */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let list: StubCategory[] = [...(options.categories ?? CATEGORIES)];

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const pathname = new URL(url, 'http://localhost').pathname;

    if (pathname === '/api/v1/categories' && method === 'GET') {
      return jsonResponse(list, options.listStatus ?? 200);
    }

    if (pathname === '/api/v1/categories' && method === 'POST') {
      if (options.create !== undefined) {
        return jsonResponse(options.create.body, options.create.status);
      }
      const body = JSON.parse(String(init?.body)) as {
        name: string;
        parentId?: string;
        sortOrder?: number;
      };
      const created: StubCategory = {
        id: 'c9',
        name: body.name,
        parentId: body.parentId,
        sortOrder: body.sortOrder ?? 0,
        active: true,
      };
      list = [...list, created];
      return jsonResponse(created, 201);
    }

    const item = /^\/api\/v1\/categories\/([^/]+)$/.exec(pathname);
    if (item !== null) {
      const id = item[1] ?? '';

      if (method === 'PUT') {
        if (options.update !== undefined) {
          return jsonResponse(options.update.body, options.update.status);
        }
        const body = JSON.parse(String(init?.body)) as {
          name: string;
          parentId?: string;
          sortOrder?: number;
        };
        const current = list.find((category) => category.id === id);
        const updated: StubCategory = {
          id,
          name: body.name,
          parentId: body.parentId,
          sortOrder: body.sortOrder ?? 0,
          active: current?.active ?? true,
        };
        list = list.map((category) => (category.id === id ? updated : category));
        return jsonResponse(updated);
      }

      if (method === 'DELETE') {
        if (options.remove !== undefined) {
          if (options.remove.status !== 204) {
            // O caso real do 404: alguém desativou a categoria entre a lista e o clique; a releitura
            // seguinte já a mostra desativada.
            list = list.map((category) =>
              category.id === id ? { ...category, active: false } : category,
            );
            return jsonResponse(options.remove.body, options.remove.status);
          }
        }
        list = list.map((category) =>
          category.id === id ? { ...category, active: false } : category,
        );
        return jsonResponse(undefined, 204);
      }
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

/** Cabeçalho da requisição como o `fetch` o recebeu (o client manda `Headers`). */
function headerOf(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name);
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
 * `withToast`, o cache é o da aplicação (`createQueryClient`), que liga o toast global de erro de
 * mutação — é por ele que o 404 do `DELETE` repetido aparece.
 */
function renderPage(
  permissions: string[] = ['product.read', 'category.write'],
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
            <CategoriesPage />
          </ToastProvider>
        ) : (
          <CategoriesPage />
        )}
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('CategoriesPage — lista', () => {
  it('renderiza nome, categoria pai, ordem e situação', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('cell', { name: 'Bebidas' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Descontinuados' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '9' })).toBeInTheDocument();
    expect(screen.getAllByRole('cell', { name: 'Ativa' })).toHaveLength(2);
    expect(screen.getByRole('cell', { name: 'Desativada' })).toBeInTheDocument();
    // A linha "Bebidas" resolve o pai pelo nome da própria lista; "Mercearia" aparece como nome e
    // como pai de "Bebidas".
    expect(screen.getAllByRole('cell', { name: 'Mercearia' })).toHaveLength(2);
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ listStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('CategoriesPage — criação', () => {
  it('envia nome, pai e ordem do contrato e recarrega a lista', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });
    const before = calls(fetchStub, 'GET', '/api/v1/categories').length;

    fireEvent.click(screen.getByRole('button', { name: 'Nova categoria' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Padaria' } });
    fireEvent.change(screen.getByLabelText('Categoria pai'), { target: { value: 'c1' } });
    fireEvent.change(screen.getByLabelText('Ordem'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', '/api/v1/categories')).toHaveLength(1));

    const [input, init] = lastCall(fetchStub, 'POST', '/api/v1/categories');
    expect(String(input)).toBe('/api/v1/categories');
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Padaria',
      parentId: 'c1',
      sortOrder: 2,
    });

    // Quem diz o estado final é o servidor: a lista foi lida de novo e o modal fechou.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/categories').length).toBeGreaterThan(before),
    );
    expect(await screen.findByRole('cell', { name: 'Padaria' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('omite pai e ordem quando os campos ficam vazios (default do contrato)', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });

    fireEvent.click(screen.getByRole('button', { name: 'Nova categoria' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Padaria' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', '/api/v1/categories')).toHaveLength(1));

    const [, init] = lastCall(fetchStub, 'POST', '/api/v1/categories');
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Padaria' });
  });

  it('bloqueia o cadastro sem nome', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });

    fireEvent.click(screen.getByRole('button', { name: 'Nova categoria' }));
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Informe o nome.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/categories')).toHaveLength(0);
  });
});

describe('CategoriesPage — edição', () => {
  it('semeia o formulário com a linha e manda o PUT sem If-Match', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });

    fireEvent.click(screen.getByRole('button', { name: 'Editar Bebidas' }));

    expect(await screen.findByLabelText('Nome')).toHaveValue('Bebidas');
    expect(screen.getByLabelText('Categoria pai')).toHaveValue('c1');
    expect(screen.getByLabelText('Ordem')).toHaveValue('1');
    // A própria categoria em edição não é opção de pai (evita o ciclo direto).
    expect(screen.queryByRole('option', { name: 'Bebidas' })).toBeNull();

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Bebidas e Sucos' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'PUT', '/api/v1/categories/c2')).toHaveLength(1),
    );

    const [input, init] = lastCall(fetchStub, 'PUT', '/api/v1/categories/c2');
    expect(String(input)).toBe('/api/v1/categories/c2');
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Bebidas e Sucos',
      parentId: 'c1',
      sortOrder: 1,
    });
    // O contrato de categoria não tem `version`: a escrita não leva o lock otimista.
    expect(headerOf(init, 'if-match')).toBeNull();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});

describe('CategoriesPage — nome duplicado (409)', () => {
  it('põe a mensagem no campo name quando o problema vem só com o code', async () => {
    const fetchStub = stubBackend({
      create: {
        status: 409,
        body: {
          status: 409,
          code: 'CATEGORY_NAME_ALREADY_EXISTS',
          detail: 'nome Mercearia já está em uso',
        },
      },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });

    fireEvent.click(screen.getByRole('button', { name: 'Nova categoria' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Mercearia' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Já existe uma categoria com este nome.',
    );
    // O modal continua aberto para corrigir o nome.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/categories')).toHaveLength(1);
  });

  it('prefere a mensagem de errors[] quando o servidor manda o campo', async () => {
    stubBackend({
      create: {
        status: 409,
        body: {
          status: 409,
          code: 'CATEGORY_NAME_ALREADY_EXISTS',
          detail: 'nome Mercearia já está em uso',
          errors: [{ field: 'name', message: 'nome Mercearia já está em uso' }],
        },
      },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });

    fireEvent.click(screen.getByRole('button', { name: 'Nova categoria' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Mercearia' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'nome Mercearia já está em uso',
    );
  });
});

describe('CategoriesPage — desativar', () => {
  it('manda o DELETE e a linha desativada perde o botão de desativar', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Bebidas' });
    const before = calls(fetchStub, 'GET', '/api/v1/categories').length;

    fireEvent.click(screen.getByRole('button', { name: 'Desativar Mercearia' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'DELETE', '/api/v1/categories/c1')).toHaveLength(1),
    );
    // O 204 sem corpo invalida a lista: a leitura seguinte já mostra a categoria desativada.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/categories').length).toBeGreaterThan(before),
    );
    expect((await screen.findAllByRole('cell', { name: 'Desativada' })).length).toBeGreaterThan(1);
    expect(screen.queryByRole('button', { name: 'Desativar Mercearia' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Editar Mercearia' })).toBeInTheDocument();
  });

  it('mostra mensagem clara e recarrega a lista quando o DELETE repetido leva 404', async () => {
    const fetchStub = stubBackend({
      remove: {
        status: 404,
        body: {
          status: 404,
          code: 'CATEGORY_NOT_FOUND',
          detail: 'categoria c1 não encontrada',
        },
      },
    });
    renderPage(['product.read', 'category.write'], { withToast: true });
    await screen.findByRole('cell', { name: 'Bebidas' });
    const before = calls(fetchStub, 'GET', '/api/v1/categories').length;

    fireEvent.click(screen.getByRole('button', { name: 'Desativar Mercearia' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O registro não existe mais. Atualize a lista.',
    );
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/categories').length).toBeGreaterThan(before),
    );
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Desativar Mercearia' })).toBeNull(),
    );
  });
});

describe('CategoriesPage — permissões de escrita', () => {
  it('esconde cadastro, edição e desativação sem category.write', async () => {
    stubBackend();
    renderPage(['product.read']);
    await screen.findByRole('cell', { name: 'Bebidas' });

    expect(screen.queryByRole('button', { name: 'Nova categoria' })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Ações' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Editar|Desativar/ })).toBeNull();
  });
});
