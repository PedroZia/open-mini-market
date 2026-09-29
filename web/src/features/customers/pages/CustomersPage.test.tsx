import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { createQueryClient } from '../../../app/query-client';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { ToastProvider } from '../../../shared/ui/Toast';
import { CustomersPage } from './CustomersPage';

/**
 * Clientes (1207) com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala o
 * seu stub e confere a requisição que a tela fez (URL, método e corpo), como manda o contrato
 * (§9.3): `GET /customers` com `search`/`page`/`size` — sem `sort`, que o recurso não tem —, o
 * detalhe em `GET /customers/{id}`, `POST`/`PUT` sem `If-Match` e o `disable` como `POST` sem corpo.
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

/** Cliente como o stub o guarda; espelha o contrato sem repetir o tipo do api-client. */
interface StubCustomer {
  id: string;
  name: string;
  taxId?: string;
  phone?: string;
  email?: string;
  notes?: string;
  active: boolean;
}

const CUSTOMERS: StubCustomer[] = [
  {
    id: 'cli1',
    name: 'Ana Souza',
    taxId: '52998224725',
    phone: '11999990000',
    email: 'ana@exemplo.com',
    active: true,
  },
  {
    id: 'cli2',
    name: 'Bruno Lima',
    taxId: '11144477735',
    phone: '11988887777',
    active: true,
  },
  {
    id: 'cli3',
    name: 'Carla Dias',
    active: true,
  },
];

interface StubBackendOptions {
  customers?: StubCustomer[];
  /** Status da leitura da lista; sem ele a lista vai com 200. */
  listStatus?: number;
  /** Resposta do `POST /customers`; sem ela o stub cria o registro e devolve 201. */
  create?: { status: number; body: unknown };
  /** Resposta do `PUT /customers/{id}`; sem ela o stub grava o corpo e devolve 200. */
  update?: { status: number; body: unknown };
}

/**
 * Serve cada rota do recurso como o servidor serve; qualquer rota inesperada falha o teste. A lista
 * só devolve vivos e o `disable` tira a linha da busca, como o soft delete do servidor.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let list: StubCustomer[] = [...(options.customers ?? CUSTOMERS)];

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const parsed = new URL(url, 'http://localhost');
    const pathname = parsed.pathname;

    if (pathname === '/api/v1/customers' && method === 'GET') {
      const search = parsed.searchParams.get('search')?.toLowerCase() ?? '';
      const page = Number(parsed.searchParams.get('page') ?? 0);
      const size = Number(parsed.searchParams.get('size') ?? 20);
      const alive = list.filter((customer) => customer.active);
      const found =
        search === ''
          ? alive
          : alive.filter(
              (customer) =>
                (customer.name ?? '').toLowerCase().includes(search) ||
                (customer.taxId ?? '').includes(search) ||
                (customer.phone ?? '').includes(search),
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

    if (pathname === '/api/v1/customers' && method === 'POST') {
      if (options.create !== undefined) {
        return jsonResponse(options.create.body, options.create.status);
      }
      const body = JSON.parse(String(init?.body)) as {
        name: string;
        taxId?: string;
        phone?: string;
        email?: string;
        notes?: string;
      };
      const created: StubCustomer = { id: 'cli9', ...body, active: true };
      list = [...list, created];
      return jsonResponse(created, 201);
    }

    const disable = /^\/api\/v1\/customers\/([^/]+)\/disable$/.exec(pathname);
    if (disable !== null && method === 'POST') {
      const id = disable[1] ?? '';
      const current = list.find((customer) => customer.id === id);
      if (current === undefined) {
        return jsonResponse(
          { status: 404, code: 'CUSTOMER_NOT_FOUND', detail: `cliente ${id} não encontrado` },
          404,
        );
      }
      list = list.map((customer) =>
        customer.id === id ? { ...customer, active: false } : customer,
      );
      return jsonResponse({ ...current, active: false });
    }

    const item = /^\/api\/v1\/customers\/([^/]+)$/.exec(pathname);
    if (item !== null) {
      const id = item[1] ?? '';
      const current = list.find((customer) => customer.id === id);

      if (method === 'GET') {
        if (current === undefined || !current.active) {
          return jsonResponse(
            { status: 404, code: 'CUSTOMER_NOT_FOUND', detail: `cliente ${id} não encontrado` },
            404,
          );
        }
        return jsonResponse(current);
      }

      if (method === 'PUT') {
        if (options.update !== undefined) {
          return jsonResponse(options.update.body, options.update.status);
        }
        const body = JSON.parse(String(init?.body)) as {
          name: string;
          taxId?: string;
          phone?: string;
          email?: string;
          notes?: string;
        };
        const updated: StubCustomer = {
          id,
          name: body.name,
          taxId: body.taxId,
          phone: body.phone,
          email: body.email,
          notes: body.notes,
          active: current?.active ?? true,
        };
        list = list.map((customer) => (customer.id === id ? updated : customer));
        return jsonResponse(updated);
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

/** Query da última leitura da lista — é nela que a tela prova o que pediu ao servidor. */
function lastListQuery(fetchStub: FetchStub): URLSearchParams {
  const last = calls(fetchStub, 'GET', '/api/v1/customers').at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/customers');
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
 * que o sucesso do desativar aparece.
 */
function renderPage(
  permissions: string[] = ['customer.read', 'customer.write'],
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
            <CustomersPage />
          </ToastProvider>
        ) : (
          <CustomersPage />
        )}
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('CustomersPage — lista', () => {
  it('renderiza nome, CPF, telefone e e-mail', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('cell', { name: 'Ana Souza' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '52998224725' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '11999990000' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'ana@exemplo.com' })).toBeInTheDocument();
    // Cliente sem documento/telefone/e-mail mostra o traço, nunca uma célula vazia.
    expect(screen.getAllByRole('cell', { name: '—' }).length).toBeGreaterThan(0);
  });

  it('busca e pagina pelo servidor, sem `sort`', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.change(screen.getByLabelText('Buscar'), { target: { value: 'Bruno' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('search')).toBe('Bruno'));
    expect(lastListQuery(fetchStub).get('page')).toBe('0');
    expect(lastListQuery(fetchStub).get('size')).toBe('20');
    // O recurso não tem ordenação: a tela nunca pede `sort`.
    expect(lastListQuery(fetchStub).get('sort')).toBeNull();

    fireEvent.change(screen.getByLabelText('Buscar'), { target: { value: '' } });
    await screen.findByRole('cell', { name: 'Ana Souza' });
    await waitFor(() => expect(lastListQuery(fetchStub).get('search')).toBeNull());
  });

  it('pede a próxima página ao servidor', async () => {
    // 25 clientes com página de 20: a segunda página existe e o botão leva até ela.
    const many = Array.from({ length: 25 }, (_, index) => ({
      id: `cli${index + 1}`,
      name: `Cliente ${String(index + 1).padStart(2, '0')}`,
      active: true,
    }));
    const fetchStub = stubBackend({ customers: many });
    renderPage();

    expect(await screen.findByRole('cell', { name: 'Cliente 01' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: 'Cliente 21' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));

    await waitFor(() => expect(lastListQuery(fetchStub).get('page')).toBe('1'));
    expect(await screen.findByRole('cell', { name: 'Cliente 21' })).toBeInTheDocument();
    expect(screen.getByText('Página 2 de 2 · 25 itens')).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ listStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('CustomersPage — criação', () => {
  it('envia o CPF normalizado (fora da máscara) do contrato', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.click(screen.getByRole('button', { name: 'Novo cliente' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Diego Alves' } });
    fireEvent.change(screen.getByLabelText('CPF'), { target: { value: '529.982.247-25' } });
    fireEvent.change(screen.getByLabelText('Telefone'), { target: { value: '(11) 97777-6666' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', '/api/v1/customers')).toHaveLength(1));

    const [input, init] = lastCall(fetchStub, 'POST', '/api/v1/customers');
    expect(String(input)).toBe('/api/v1/customers');
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Diego Alves',
      taxId: '52998224725',
      phone: '(11) 97777-6666',
    });

    // Quem diz o estado final é o servidor: a lista foi lida de novo e o modal fechou.
    expect(await screen.findByRole('cell', { name: 'Diego Alves' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('bloqueia o cadastro sem nome', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.click(screen.getByRole('button', { name: 'Novo cliente' }));
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Informe o nome.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/customers')).toHaveLength(0);
  });

  it('barra CPF inválido pelo Zod, sem sair da tela', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.click(screen.getByRole('button', { name: 'Novo cliente' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Diego Alves' } });
    fireEvent.change(screen.getByLabelText('CPF'), { target: { value: '111.111.111-11' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(
      await screen.findByText('Informe um CPF válido ou deixe o campo vazio.'),
    ).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/customers')).toHaveLength(0);
  });

  it('põe o 409 de CPF em uso no campo taxId, com o modal aberto', async () => {
    const fetchStub = stubBackend({
      create: {
        status: 409,
        body: {
          status: 409,
          code: 'TAX_ID_ALREADY_EXISTS',
          detail: 'CPF 52998224725 já está em uso',
        },
      },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.click(screen.getByRole('button', { name: 'Novo cliente' }));
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Diego Alves' } });
    fireEvent.change(screen.getByLabelText('CPF'), { target: { value: '529.982.247-25' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'CPF 52998224725 já está em uso',
    );
    // O modal continua aberto para corrigir o CPF.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/customers')).toHaveLength(1);
  });
});

describe('CustomersPage — edição', () => {
  it('semeia o formulário com o detalhe e manda o PUT sem If-Match', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.click(screen.getByRole('button', { name: 'Editar Ana Souza' }));

    expect(await screen.findByLabelText('Nome')).toHaveValue('Ana Souza');
    expect(screen.getByLabelText('CPF')).toHaveValue('52998224725');
    expect(screen.getByLabelText('Telefone')).toHaveValue('11999990000');
    expect(screen.getByLabelText('E-mail')).toHaveValue('ana@exemplo.com');

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Ana Souza Lima' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'PUT', '/api/v1/customers/cli1')).toHaveLength(1),
    );

    const [input, init] = lastCall(fetchStub, 'PUT', '/api/v1/customers/cli1');
    expect(String(input)).toBe('/api/v1/customers/cli1');
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Ana Souza Lima',
      taxId: '52998224725',
      phone: '11999990000',
      email: 'ana@exemplo.com',
    });
    // O contrato não expõe `version`: a escrita não leva o lock otimista.
    expect(new Headers(init?.headers).get('if-match')).toBeNull();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await screen.findByRole('cell', { name: 'Ana Souza Lima' })).toBeInTheDocument();
  });

  it('mostra a mensagem e relê o cliente quando o PUT leva 409 de concorrência', async () => {
    const fetchStub = stubBackend({
      update: {
        status: 409,
        body: {
          status: 409,
          code: 'CONCURRENT_MODIFICATION',
          detail: 'cliente alterado por outra requisição',
        },
      },
    });
    renderPage();
    await screen.findByRole('cell', { name: 'Ana Souza' });

    fireEvent.click(screen.getByRole('button', { name: 'Editar Ana Souza' }));
    await screen.findByLabelText('Nome');
    const detailReadsBefore = calls(fetchStub, 'GET', '/api/v1/customers/cli1').length;

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Ana Souza Lima' } });
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Alguém alterou este registro antes de você. Recarregue e tente de novo.',
    );
    // A releitura traz o registro como o servidor o tem para o próximo Salvar.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/customers/cli1').length).toBeGreaterThan(
        detailReadsBefore,
      ),
    );
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('CustomersPage — desativar', () => {
  it('manda o POST de disable, avisa o sucesso e recarrega a lista', async () => {
    const fetchStub = stubBackend();
    renderPage(['customer.read', 'customer.write'], { withToast: true });
    await screen.findByRole('cell', { name: 'Ana Souza' });
    const before = calls(fetchStub, 'GET', '/api/v1/customers').length;

    fireEvent.click(screen.getByRole('button', { name: 'Desativar Ana Souza' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/customers/cli1/disable')).toHaveLength(1),
    );
    // Toast de sucesso (o canal global só mostra erro) e lista relida: a linha some porque a busca
    // só devolve clientes vivos.
    expect(await screen.findByText('Cliente desativado.')).toBeInTheDocument();
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/customers').length).toBeGreaterThan(before),
    );
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'Ana Souza' })).toBeNull());
  });
});

describe('CustomersPage — permissões de escrita', () => {
  it('esconde cadastro, edição e desativação sem customer.write', async () => {
    stubBackend();
    renderPage(['customer.read']);
    await screen.findByRole('cell', { name: 'Ana Souza' });

    expect(screen.queryByRole('button', { name: 'Novo cliente' })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Ações' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Editar|Desativar/ })).toBeNull();
  });
});
