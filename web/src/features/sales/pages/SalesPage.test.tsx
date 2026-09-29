import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { SalesPage } from './SalesPage';

/**
 * Vendas (1209a) com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala o
 * seu stub e confere o que a tela pediu ao servidor, como manda o contrato (§9.3): `GET /sales` só
 * com os filtros preenchidos (período ISO com offset, situação, operador, página e tamanho) e
 * `GET /users` apenas para o picker de quem tem `user.read`.
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

/** Venda como o stub a guarda; espelha o `SaleSummaryResponse` sem repetir o tipo do api-client. */
interface StubSale {
  id: string;
  number: number;
  status: string;
  operatorUserId: string;
  itemCount: number;
  total: number;
  createdAt: string;
}

/** Operador como `GET /users` devolve (só o que o picker usa). */
interface StubUser {
  id: string;
  username: string;
  displayName: string;
}

// UUID no formato do contrato: a coluna de operador sem `user.read` mostra os 8 primeiros dígitos.
const ANA_ID = '0198f3a2-4c1d-7a2e-9b3f-000000000001';
const BRUNO_ID = '0198f3b7-8e2f-7c4a-8d1e-000000000002';
const CARLA_ID = '0198f3c9-1a3b-7d5c-9e2f-000000000003';

const COMPLETED_SALE: StubSale = {
  id: 's1',
  number: 12,
  status: 'COMPLETED',
  operatorUserId: BRUNO_ID,
  itemCount: 3,
  total: 31.9,
  createdAt: '2026-09-28T22:21:54Z',
};

const OPEN_SALE: StubSale = {
  id: 's2',
  number: 11,
  status: 'OPEN',
  operatorUserId: CARLA_ID,
  itemCount: 1,
  total: 5.5,
  createdAt: '2026-09-28T21:00:00Z',
};

const CANCELLED_SALE: StubSale = {
  id: 's3',
  number: 10,
  status: 'CANCELLED',
  operatorUserId: BRUNO_ID,
  itemCount: 2,
  total: 14,
  createdAt: '2026-09-28T20:00:00Z',
};

const SALES: StubSale[] = [COMPLETED_SALE, OPEN_SALE, CANCELLED_SALE];

/** Operadores do picker: `displayName` quando existe, senão `username`. */
const USERS: StubUser[] = [
  { id: ANA_ID, username: 'ana', displayName: 'Ana Souza' },
  { id: BRUNO_ID, username: 'bruno', displayName: 'Bruno Lima' },
  { id: CARLA_ID, username: 'carla', displayName: '' },
];

interface StubBackendOptions {
  sales?: StubSale[];
  users?: StubUser[];
  /** Status da leitura da lista de vendas; sem ele a lista vai com 200. */
  listStatus?: number;
}

/**
 * Serve cada rota como o servidor serve; qualquer rota inesperada falha o teste. A lista de vendas
 * aplica período (piso inclusivo, teto exclusivo), situação, operador e paginação, como o
 * `ListSalesUseCase`.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const sales = options.sales ?? SALES;

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const parsed = new URL(url, 'http://localhost');
    const pathname = parsed.pathname;

    if (pathname === '/api/v1/sales' && method === 'GET') {
      if (options.listStatus !== undefined && options.listStatus !== 200) {
        return jsonResponse(
          { status: options.listStatus, code: 'ACCESS_DENIED', detail: 'acesso negado' },
          options.listStatus,
        );
      }

      const from = parsed.searchParams.get('from');
      const to = parsed.searchParams.get('to');
      const status = parsed.searchParams.get('status');
      const operatorUserId = parsed.searchParams.get('operatorUserId');
      const page = Number(parsed.searchParams.get('page') ?? 0);
      const size = Number(parsed.searchParams.get('size') ?? 20);

      const found = sales.filter((sale) => {
        const matchesFrom = from === null || Date.parse(sale.createdAt) >= Date.parse(from);
        const matchesTo = to === null || Date.parse(sale.createdAt) < Date.parse(to);
        const matchesStatus = status === null || sale.status === status;
        const matchesOperator =
          operatorUserId === null || sale.operatorUserId === operatorUserId;
        return matchesFrom && matchesTo && matchesStatus && matchesOperator;
      });

      return jsonResponse({
        items: found.slice(page * size, page * size + size),
        page,
        size,
        totalItems: found.length,
        totalPages: Math.ceil(found.length / size),
      });
    }

    if (pathname === '/api/v1/users' && method === 'GET') {
      const users = options.users ?? USERS;
      return jsonResponse({
        items: users,
        page: 0,
        size: 100,
        totalItems: users.length,
        totalPages: 1,
      });
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

/** Query da última leitura da lista — é nela que a tela prova o que pediu ao servidor. */
function lastListQuery(fetchStub: FetchStub): URLSearchParams {
  const last = calls(fetchStub, 'GET', '/api/v1/sales').at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/sales');
  }
  return new URL(String(last[0]), 'http://localhost').searchParams;
}

/** Data formatada como a coluna mostra: pt-BR, fuso local (só apresentação). */
function expectedDate(instant: string): string {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

/** Total formatado como a coluna mostra: BRL, sem recálculo nenhum. */
function expectedMoney(value: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value);
}

function sessionWith(permissions: string[]): AuthContextValue {
  return {
    status: 'authenticated',
    user: { id: ANA_ID, username: 'ana', displayName: 'Ana' },
    roles: [],
    permissions,
    login: async () => {},
    logout: async () => {},
  };
}

/** Monta a página com sessão e cache próprios; `retry` desligado para o 403 não repetir. */
function renderPage(permissions: string[] = ['report.read', 'user.read']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={sessionWith(permissions)}>
        <SalesPage />
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

/** Espera a lista aparecer; a venda 12 é a âncora dos testes. */
async function waitForList(): Promise<void> {
  await screen.findByRole('cell', { name: '12' });
}

/** Linha da tabela da venda pelo número. */
function rowOf(number: number): HTMLElement {
  const cell = screen.getByRole('cell', { name: String(number) });
  const row = cell.closest('tr');
  if (row === null) {
    throw new Error(`linha da venda ${number} não encontrada`);
  }
  return row;
}

describe('SalesPage — lista', () => {
  it('renderiza número, situação, data, operador, itens e total', async () => {
    stubBackend();
    renderPage();
    await waitForList();

    const completed = rowOf(12);
    expect(within(completed).getByRole('cell', { name: 'Concluída' })).toBeInTheDocument();
    expect(
      within(completed).getByRole('cell', { name: expectedDate(COMPLETED_SALE.createdAt) }),
    ).toBeInTheDocument();
    // O nome sai de `GET /users`; o id curto só entraria sem a permissão.
    expect(within(completed).getByRole('cell', { name: 'Bruno Lima' })).toBeInTheDocument();
    expect(within(completed).queryByRole('cell', { name: '0198f3b7' })).toBeNull();
    expect(within(completed).getByRole('cell', { name: '3' })).toBeInTheDocument();
    expect(within(completed).getByRole('cell', { name: expectedMoney(31.9) })).toBeInTheDocument();

    // Sem `displayName`, o picker/coluna usam o username (a Carla é o caso do stub).
    const open = rowOf(11);
    expect(within(open).getByRole('cell', { name: 'Aberta' })).toBeInTheDocument();
    expect(within(open).getByRole('cell', { name: 'carla' })).toBeInTheDocument();

    // Cancelada é situação, não cor: o texto do selo diz o que aconteceu.
    expect(within(rowOf(10)).getByRole('cell', { name: 'Cancelada' })).toBeInTheDocument();

    // O servidor não tem `sort`: nenhum cabeçalho é botão de ordenação.
    expect(screen.queryByRole('button', { name: /^Ordenar por/ })).toBeNull();
  });

  it('põe período, situação e operador na query do servidor', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    expect(lastListQuery(fetchStub).get('size')).toBe('20');
    expect(lastListQuery(fetchStub).get('page')).toBe('0');
    // Nada preenchido: nenhum filtro vazio vira parâmetro.
    expect(lastListQuery(fetchStub).get('from')).toBeNull();
    expect(lastListQuery(fetchStub).get('to')).toBeNull();
    expect(lastListQuery(fetchStub).get('status')).toBeNull();
    expect(lastListQuery(fetchStub).get('operatorUserId')).toBeNull();

    // O datetime-local (hora local) vira ISO com offset — é o que o servidor aceita.
    const from = '2026-09-28T08:00';
    fireEvent.change(screen.getByLabelText('De'), { target: { value: from } });
    await waitFor(() =>
      expect(lastListQuery(fetchStub).get('from')).toBe(new Date(from).toISOString()),
    );

    const to = '2026-09-28T18:00';
    fireEvent.change(screen.getByLabelText('Até (não inclui)'), { target: { value: to } });
    await waitFor(() =>
      expect(lastListQuery(fetchStub).get('to')).toBe(new Date(to).toISOString()),
    );

    // Limpa o período para os próximos filtros não cruzarem com ele.
    fireEvent.change(screen.getByLabelText('De'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Até (não inclui)'), { target: { value: '' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('from')).toBeNull());
    expect(lastListQuery(fetchStub).get('to')).toBeNull();

    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'CANCELLED' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('status')).toBe('CANCELLED'));
    // O que a tela mostra é a resposta do servidor: só a cancelada continua na lista.
    expect(await screen.findByRole('cell', { name: 'Cancelada' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: 'Concluída' })).toBeNull();

    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'all' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('status')).toBeNull());

    fireEvent.change(screen.getByLabelText('Operador'), { target: { value: BRUNO_ID } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('operatorUserId')).toBe(BRUNO_ID));
    expect(await screen.findByRole('cell', { name: '12' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: '11' })).toBeNull();
  });

  it('pede a próxima página ao servidor e volta à primeira quando o filtro muda', async () => {
    // 25 vendas com página de 20: a segunda página existe e o botão leva até ela.
    const many = Array.from({ length: 25 }, (_, index) => ({
      id: `s${index + 1}`,
      number: index + 1,
      status: 'COMPLETED',
      operatorUserId: BRUNO_ID,
      itemCount: 1,
      total: 10,
      createdAt: '2026-09-28T12:00:00Z',
    }));
    const fetchStub = stubBackend({ sales: many });
    renderPage();
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));

    await waitFor(() => expect(lastListQuery(fetchStub).get('page')).toBe('1'));
    expect(await screen.findByRole('cell', { name: '21' })).toBeInTheDocument();
    expect(screen.getByText('Página 2 de 2 · 25 itens')).toBeInTheDocument();

    // Filtro novo recomeça da página 1 (0 no contrato), como o servidor espera.
    fireEvent.change(screen.getByLabelText('Situação'), { target: { value: 'OPEN' } });
    await waitFor(() => expect(lastListQuery(fetchStub).get('status')).toBe('OPEN'));
    expect(lastListQuery(fetchStub).get('page')).toBe('0');
  });

  it('mostra o vazio quando não há venda para os filtros', async () => {
    stubBackend({ sales: [] });
    renderPage();

    expect(
      await screen.findByText('Nenhuma venda encontrada para os filtros.'),
    ).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ listStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('SalesPage — operador sem user.read', () => {
  it('esconde o picker e mostra o id curto do operador', async () => {
    const fetchStub = stubBackend();
    renderPage(['report.read']);
    await waitForList();

    // Dado de usuário exige `user.read`: o filtro não existe e a rota nem é chamada.
    expect(screen.queryByLabelText('Operador')).toBeNull();
    expect(calls(fetchStub, 'GET', '/api/v1/users')).toHaveLength(0);

    // Sem o nome, a coluna mostra o id curto — nunca um nome inventado.
    expect(within(rowOf(12)).getByRole('cell', { name: '0198f3b7' })).toBeInTheDocument();
  });
});
