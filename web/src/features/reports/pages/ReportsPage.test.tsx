import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { ReportsPage } from './ReportsPage';

/**
 * Relatórios (1212b) com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala
 * o seu stub e confere o que a tela pediu ao servidor: `GET /reports/sales-summary` com o período em
 * ISO-8601 com offset e o `groupBy` (nada além disso — o contrato do 1212a não tem filtro de
 * operador), `GET /reports/low-stock` com a paginação e `GET /users` só para o picker de quem tem
 * `user.read`.
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

const BRUNO_ID = '0198f3b7-8e2f-7c4a-8d1e-000000000002';
const CARLA_ID = '0198f3c9-1a3b-7d5c-9e2f-000000000003';

/** Resumo por dia como o `sales-summary` devolve; só o que a tela desenha. */
const DAY_SUMMARY = {
  groupBy: 'day',
  salesCount: 5,
  total: 150.25,
  ticketAverage: 30.05,
  groups: [
    { key: '2026-09-28', salesCount: 3, total: 100.25 },
    { key: '2026-09-29', salesCount: 2, total: 50 },
  ],
};

/** Resumo por operador: a chave do grupo é o id do usuário, como no `groupBy` do servidor. */
const OPERATOR_SUMMARY = {
  groupBy: 'operator',
  salesCount: 5,
  total: 150.25,
  ticketAverage: 30.05,
  groups: [
    { key: BRUNO_ID, salesCount: 4, total: 120 },
    { key: CARLA_ID, salesCount: 1, total: 30.25 },
  ],
};

/** Página do estoque baixo como o `low-stock` devolve. */
const LOW_STOCK_PAGE = {
  items: [
    {
      productId: 'p1',
      name: 'Arroz',
      barcode: '7891001',
      unit: 'KG',
      quantity: 2.5,
      minQuantity: 5,
    },
    {
      productId: 'p2',
      name: 'Refrigerante',
      barcode: '7891002',
      unit: 'UN',
      quantity: 4,
      minQuantity: 6,
    },
  ],
  page: 0,
  size: 20,
  totalItems: 2,
  totalPages: 1,
};

/** Operadores do picker: `displayName` quando existe, senão `username`. */
const USERS = [
  { id: BRUNO_ID, username: 'bruno', displayName: 'Bruno Lima' },
  { id: CARLA_ID, username: 'carla', displayName: '' },
];

interface StubBackendOptions {
  /** Resposta do `low-stock`; sem ela o stub devolve a página de dois itens. */
  lowStock?: unknown;
  /** Status da leitura do estoque baixo. */
  lowStockStatus?: number;
}

/** Serve cada rota do recurso como o servidor serve; qualquer rota inesperada falha o teste. */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const fetchStub = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost');

    if (url.pathname === '/api/v1/reports/sales-summary') {
      return jsonResponse(
        url.searchParams.get('groupBy') === 'operator' ? OPERATOR_SUMMARY : DAY_SUMMARY,
      );
    }

    if (url.pathname === '/api/v1/reports/low-stock') {
      const status = options.lowStockStatus ?? 200;
      if (status !== 200) {
        return jsonResponse({ status, code: 'ACCESS_DENIED', detail: 'acesso negado' }, status);
      }
      return jsonResponse(options.lowStock ?? LOW_STOCK_PAGE);
    }

    if (url.pathname === '/api/v1/users') {
      return jsonResponse({ items: USERS, page: 0, size: 100, totalItems: 2, totalPages: 1 });
    }

    throw new Error(`fetch inesperado: ${url.pathname}`);
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

/** Consultas ao resumo de vendas. */
function summaryCalls(fetchStub: FetchStub): StubCall[] {
  return calls(fetchStub, 'GET', '/api/v1/reports/sales-summary');
}

/** Query da última consulta ao resumo — é nela que a tela prova o que pediu. */
function lastSummaryQuery(fetchStub: FetchStub): URLSearchParams {
  const last = summaryCalls(fetchStub).at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/reports/sales-summary');
  }
  return new URL(String(last[0]), 'http://localhost').searchParams;
}

/** Query da última consulta ao estoque baixo. */
function lastLowStockQuery(fetchStub: FetchStub): URLSearchParams {
  const last = calls(fetchStub, 'GET', '/api/v1/reports/low-stock').at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/reports/low-stock');
  }
  return new URL(String(last[0]), 'http://localhost').searchParams;
}

/** Data formatada como a coluna do grupo mostra: pt-BR, sem deslocar a chave UTC do servidor. */
function expectedDay(key: string): string {
  const [year = '', month = '', day = ''] = key.split('-');
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' }).format(
    new Date(Number(year), Number(month) - 1, Number(day)),
  );
}

/** Dinheiro como a tela mostra: BRL, sem recálculo nenhum (BR-12). O espaço do `Intl` é não
 * separável e a query normaliza o texto do DOM com `\s+`: a expectativa precisa da mesma
 * normalização para casar. */
function expectedMoney(value: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
    .format(value)
    .replace(/\s+/g, ' ');
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

/** Monta a página com sessão e cache próprios; `retry` desligado para o 403 não repetir. */
function renderPage(permissions: string[] = ['report.read', 'user.read']) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AuthContext.Provider value={sessionWith(permissions)}>
        <ReportsPage />
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

/** Período que os testes digitam nos campos; o valor vira ISO com offset no client. */
const FROM_INPUT = '2026-09-28T08:00';
const TO_INPUT = '2026-09-30T18:00';

/** Preenche o período como o operador faria; a consulta só sai com os dois campos preenchidos. */
function fillPeriod(): void {
  fireEvent.change(screen.getByLabelText('De'), { target: { value: FROM_INPUT } });
  fireEvent.change(screen.getByLabelText('Até (não inclui)'), { target: { value: TO_INPUT } });
}

/** Troca o agrupamento pelo select. */
function selectGrouping(value: 'day' | 'operator'): void {
  fireEvent.change(screen.getByLabelText('Agrupar por'), { target: { value } });
}

/** Espera a tabela de grupos aparecer (o dia 28/09 é a âncora dos testes). */
async function waitForGroups(): Promise<void> {
  await screen.findByRole('cell', { name: expectedDay('2026-09-28') });
}

describe('ReportsPage — vendas por período', () => {
  it('envia o período em ISO com offset e o agrupamento, sem parâmetro que o contrato não tem', async () => {
    const fetchStub = stubBackend();
    renderPage();

    // Sem período completo a consulta não sai: o servidor exige `from` e `to`.
    expect(
      screen.getByText('Informe o período (De e Até) para consultar as vendas.'),
    ).toBeInTheDocument();
    expect(summaryCalls(fetchStub)).toHaveLength(0);

    fillPeriod();

    await waitFor(() =>
      expect(lastSummaryQuery(fetchStub).get('from')).toBe(new Date(FROM_INPUT).toISOString()),
    );
    expect(lastSummaryQuery(fetchStub).get('to')).toBe(new Date(TO_INPUT).toISOString());
    expect(lastSummaryQuery(fetchStub).get('groupBy')).toBe('day');
    // O contrato do 1212a é `from`/`to`/`groupBy`: nada de filtro fantasma na URL.
    expect(lastSummaryQuery(fetchStub).get('operatorUserId')).toBeNull();

    selectGrouping('operator');

    await waitFor(() => expect(lastSummaryQuery(fetchStub).get('groupBy')).toBe('operator'));
    expect(lastSummaryQuery(fetchStub).get('operatorUserId')).toBeNull();
  });

  it('mostra os totais gerais do servidor e a tabela de grupos formatada', async () => {
    stubBackend();
    renderPage();
    fillPeriod();
    await waitForGroups();

    // Totais gerais: os campos do servidor, não a soma dos grupos da tabela. O escopo é a seção
    // de vendas porque a tabela de estoque também tem números.
    const sales = screen.getByRole('region', { name: 'Vendas' });
    expect(within(sales).getByText(expectedMoney(150.25))).toBeInTheDocument();
    expect(within(sales).getByText(expectedMoney(30.05))).toBeInTheDocument();
    expect(within(sales).getByText('5')).toBeInTheDocument();

    const first = screen.getByRole('cell', { name: expectedDay('2026-09-28') }).closest('tr');
    if (first === null) {
      throw new Error('linha do dia 28/09 não encontrada');
    }
    expect(within(first).getByRole('cell', { name: '3' })).toBeInTheDocument();
    expect(within(first).getByText(expectedMoney(100.25))).toBeInTheDocument();

    const second = screen.getByRole('cell', { name: expectedDay('2026-09-29') }).closest('tr');
    if (second === null) {
      throw new Error('linha do dia 29/09 não encontrada');
    }
    expect(within(second).getByRole('cell', { name: '2' })).toBeInTheDocument();
    expect(within(second).getByText(expectedMoney(50))).toBeInTheDocument();
  });

  it('por operador usa o nome do picker e foca a linha escolhida, sem nova consulta', async () => {
    const fetchStub = stubBackend();
    renderPage();
    fillPeriod();
    await waitForGroups();

    selectGrouping('operator');

    // O nome sai de `GET /users` (a Carla não tem `displayName`: cai no username).
    expect(await screen.findByRole('cell', { name: 'Bruno Lima' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'carla' })).toBeInTheDocument();

    const before = summaryCalls(fetchStub).length;
    fireEvent.change(screen.getByLabelText('Operador'), { target: { value: BRUNO_ID } });

    // O foco é visual: a linha do Bruno fica, a da Carla sai e nada é pedido de novo ao servidor.
    expect(screen.getByRole('cell', { name: 'Bruno Lima' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: 'carla' })).toBeNull();
    expect(summaryCalls(fetchStub)).toHaveLength(before);
  });

  it('sem user.read não há picker e a chave do operador vira o id curto', async () => {
    const fetchStub = stubBackend();
    renderPage(['report.read']);
    fillPeriod();
    await waitForGroups();

    selectGrouping('operator');
    await screen.findByRole('cell', { name: '0198f3b7' });

    // Dado de usuário exige `user.read`: o picker não existe e a rota nem é chamada.
    expect(screen.queryByLabelText('Operador')).toBeNull();
    expect(calls(fetchStub, 'GET', '/api/v1/users')).toHaveLength(0);
    expect(screen.getByRole('cell', { name: '0198f3c9' })).toBeInTheDocument();
  });
});

describe('ReportsPage — estoque baixo', () => {
  it('lista produto, código, unidade, saldo e mínimo, e pagina no servidor', async () => {
    const fetchStub = stubBackend({
      lowStock: { ...LOW_STOCK_PAGE, totalItems: 40, totalPages: 2 },
    });
    renderPage();

    expect(await screen.findByRole('cell', { name: 'Arroz' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '7891001' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'KG' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '2,5' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '5' })).toBeInTheDocument();
    expect(screen.getByText('Página 1 de 2 · 40 itens')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));

    await waitFor(() => expect(lastLowStockQuery(fetchStub).get('page')).toBe('1'));
    expect(lastLowStockQuery(fetchStub).get('size')).toBe('20');
  });

  it('mostra o vazio quando ninguém está no mínimo', async () => {
    stubBackend({ lowStock: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 } });
    renderPage();

    expect(
      await screen.findByText('Nenhum produto no mínimo de estoque.'),
    ).toBeInTheDocument();
  });

  it('403 vira o estado sem permissão', async () => {
    stubBackend({ lowStockStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});
