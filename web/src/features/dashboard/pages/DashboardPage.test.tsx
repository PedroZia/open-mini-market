import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { DashboardPage } from './DashboardPage';

/**
 * Dashboard (1212b) com a rede stubada — o setup do web desliga o `fetch` real. Cada teste instala o
 * seu stub e confere o que a tela pediu ao servidor: duas consultas do `sales-summary` do mesmo dia
 * (uma pelos totais gerais, outra pelas formas de pagamento) e _nenhuma_ sem `report.read`.
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

/** Totais gerais como o servidor devolve; só o que o dashboard desenha. */
const TOTALS_SUMMARY = {
  groupBy: 'day',
  salesCount: 12,
  total: 250.5,
  ticketAverage: 20.88,
  groups: [{ key: '2026-09-29', salesCount: 12, total: 250.5 }],
};

/** Formas zero-preenchidas como o `paymentMethod` devolve (as cinco, na ordem do enum). */
const METHODS_SUMMARY = {
  groupBy: 'paymentMethod',
  salesCount: 12,
  total: 250.5,
  ticketAverage: 20.88,
  groups: [
    { key: 'CASH', salesCount: 3, total: 100.5 },
    { key: 'PIX', salesCount: 2, total: 50 },
    { key: 'DEBIT', salesCount: 0, total: 0 },
    { key: 'CREDIT', salesCount: 1, total: 100 },
    { key: 'VOUCHER', salesCount: 0, total: 0 },
  ],
};

/** Serve o resumo pela dimensão pedida; qualquer rota inesperada falha o teste. */
function stubBackend(): FetchStub {
  const fetchStub = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname === '/api/v1/reports/sales-summary') {
      return jsonResponse(
        url.searchParams.get('groupBy') === 'paymentMethod' ? METHODS_SUMMARY : TOTALS_SUMMARY,
      );
    }
    throw new Error(`fetch inesperado: ${url.pathname}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
}

/** Chamadas do resumo, na ordem em que o dashboard as pediu. */
function summaryCalls(fetchStub: FetchStub): StubCall[] {
  return fetchStub.mock.calls.filter(([input, init]) => {
    const url = new URL(String(input), 'http://localhost');
    return (init?.method ?? 'GET') === 'GET' && url.pathname === '/api/v1/reports/sales-summary';
  });
}

/** Query de uma chamada do resumo da dimensão pedida. */
function summaryQuery(fetchStub: FetchStub, groupBy: string): URLSearchParams {
  const call = summaryCalls(fetchStub).find(
    ([input]) => new URL(String(input), 'http://localhost').searchParams.get('groupBy') === groupBy,
  );
  if (call === undefined) {
    throw new Error(`nenhuma chamada do resumo com groupBy=${groupBy}`);
  }
  return new URL(String(call[0]), 'http://localhost').searchParams;
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

/** Monta o dashboard com sessão e cache próprios; `retry` desligado para o 403 não repetir. */
function renderPage(permissions: string[] = ['report.read']) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AuthContext.Provider value={sessionWith(permissions)}>
        <DashboardPage />
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('DashboardPage', () => {
  it('mostra os totais gerais do dia e as formas de pagamento', async () => {
    stubBackend();
    renderPage();

    // Totais gerais: os campos do servidor, não a soma dos buckets de `groups`.
    expect(await screen.findByText(expectedMoney(250.5))).toBeInTheDocument();
    expect(screen.getByText(expectedMoney(20.88))).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();

    // As cinco formas, com rótulo pt-BR e o valor do servidor (zero inclusive).
    expect(screen.getByRole('cell', { name: 'Dinheiro' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'PIX' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Débito' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Crédito' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Vale' })).toBeInTheDocument();
    expect(screen.getByText(expectedMoney(100.5))).toBeInTheDocument();
    // Débito e Vale vêm zerados do servidor: a linha existe com o zero, não como ausência.
    expect(screen.getAllByText(expectedMoney(0))).toHaveLength(2);
  });

  it('consulta o dia local em ISO nos dois agrupamentos', async () => {
    const fetchStub = stubBackend();
    renderPage();

    await screen.findByText(expectedMoney(250.5));

    expect(summaryCalls(fetchStub)).toHaveLength(2);

    const day = summaryQuery(fetchStub, 'day');
    const methods = summaryQuery(fetchStub, 'paymentMethod');

    // O mesmo período nos dois pedidos: o dia de quem opera (offsets diferentes, mesmo intervalo).
    expect(methods.get('from')).toBe(day.get('from'));
    expect(methods.get('to')).toBe(day.get('to'));

    const from = day.get('from');
    const to = day.get('to');
    expect(from).not.toBeNull();
    expect(to).not.toBeNull();
    if (from === null || to === null) {
      throw new Error('período ausente na consulta do dashboard');
    }

    // Limites em meia-noite local, com o fim exclusivo no dia seguinte (o intervalo tem ~24 h).
    const start = new Date(from);
    expect(start.getHours()).toBe(0);
    expect(start.getMinutes()).toBe(0);
    const span = Date.parse(to) - Date.parse(from);
    expect(span).toBeGreaterThanOrEqual(23 * 60 * 60 * 1000);
    expect(span).toBeLessThanOrEqual(25 * 60 * 60 * 1000);
  });

  it('sem report.read mostra o estado sem permissão e não consulta os relatórios', async () => {
    const fetchStub = stubBackend();
    renderPage([]);

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    // O OPERADOR não vê os números nem o picker: a consulta nem sai da tela.
    expect(screen.queryByText('Faturamento')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
    expect(fetchStub).not.toHaveBeenCalled();
  });
});
