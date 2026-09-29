import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { formatDateTime } from '../../../shared/lib/datetime';
import { CashRegisterDetailPage } from './CashRegisterDetailPage';

/**
 * Detalhe do caixa (1210a) com a rede stubada: a lista de caixas (código, nome e operador) e a
 * sessão atual de `GET /cash-registers/{id}/current-session`. Caixa sem sessão é 404
 * `CASH_SESSION_NOT_OPEN` — a tela mostra o estado "sem sessão aberta", não um erro.
 */

/** Stub do `fetch` no nível em que o client o usa: `ok`, `status` e `text()`. */
type FetchStub = Mock<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>;

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  } as unknown as Response;
}

const FRONT_ID = '0198f5a1-3c4d-7e5f-8a91-000000000001';
const SESSION_ID = '0198f5b2-5e6f-7a81-9c13-000000000002';
const ANA_ID = '0198f3a2-4c1d-7a2e-9b3f-000000000001';

/** Caixa aberto como `GET /cash-registers` o devolve (o operador é o da sessão aberta). */
const REGISTERS = [
  {
    id: FRONT_ID,
    code: 'C1',
    name: 'Frente de loja',
    status: 'OPEN',
    operatorName: 'Ana Souza',
  },
];

/** Sessão atual como `GET /{id}/current-session` a devolve: abertura + vendas - sangria + suprimento. */
const CURRENT_SESSION = {
  sessionId: SESSION_ID,
  cashRegisterId: FRONT_ID,
  status: 'OPEN',
  openedAt: '2026-09-28T08:00:00Z',
  openedByUserId: ANA_ID,
  openingAmount: 100,
  expectedAmount: 177.4,
  totalsByType: { OPENING: 100, SALE: 72.4, WITHDRAWAL: 15, SUPPLY: 20 },
};

interface StubBackendOptions {
  /** Resposta do `current-session`; sem ela, a sessão aberta do fixture. */
  session?: Record<string, unknown>;
  /** Falha da sessão atual (404 `CASH_SESSION_NOT_OPEN`, 403...); sem ela vai 200. */
  sessionError?: { status: number; body: unknown };
}

function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const fetchStub: FetchStub = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost');

    if (url.pathname === '/api/v1/cash-registers') {
      return jsonResponse(REGISTERS);
    }
    if (url.pathname === `/api/v1/cash-registers/${FRONT_ID}/current-session`) {
      if (options.sessionError !== undefined) {
        return jsonResponse(options.sessionError.body, options.sessionError.status);
      }
      return jsonResponse(options.session ?? CURRENT_SESSION);
    }

    throw new Error(`fetch inesperado: ${url.pathname}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
}

const routes: RouteObject[] = [
  { path: '/cash-registers', element: <p>Lista de caixas</p> },
  { path: '/cash-registers/:id', element: <CashRegisterDetailPage /> },
  { path: '/cash-sessions/:id', element: <p>Detalhe da sessão</p> },
];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [`/cash-registers/${FRONT_ID}`] });

  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/**
 * Dinheiro como a tela mostra: BRL, sem recálculo nenhum (BR-12). O espaço do `Intl` é não separável
 * e a query de texto normaliza o DOM com `\s+`: a expectativa precisa da mesma normalização.
 */
function expectedMoney(value: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
    .format(value)
    .replace(/\s+/g, ' ');
}

/** O `dl` do resumo da sessão atual, para ler os valores sem cruzar com a tabela de totais. */
function detailList(): HTMLElement {
  const dl = screen.getByText('Aberta em').closest('dl');
  if (dl === null) {
    throw new Error('dl do resumo não encontrado');
  }
  return dl;
}

/** Valor do resumo a partir do rótulo: é o `dd` que a tela mostra para o campo. */
function summaryValue(scope: HTMLElement, label: string): HTMLElement {
  const value = within(scope).getByText(label).nextElementSibling;
  if (!(value instanceof HTMLElement)) {
    throw new Error(`resumo sem valor: ${label}`);
  }
  return value;
}

describe('CashRegisterDetailPage — sessão atual', () => {
  it('mostra abertura, operador, esperado e os totais do servidor, com o link da sessão', async () => {
    const fetchStub = stubBackend();
    renderPage();

    // A sessão e a lista chegam em paralelo: a espera é pelo detalhe já desenhado.
    expect(await screen.findByText('Aberta em')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Caixa C1' })).toBeInTheDocument();
    expect(screen.getByText('Frente de loja')).toBeInTheDocument();
    expect(screen.getByText('Aberto')).toBeInTheDocument();

    const detail = detailList();
    expect(summaryValue(detail, 'Aberta em')).toHaveTextContent(
      formatDateTime('2026-09-28T08:00:00Z'),
    );
    // O operador é o nome que a lista traz; o servidor é quem resolve, a tela não inventa.
    expect(summaryValue(detail, 'Operador')).toHaveTextContent('Ana Souza');
    expect(summaryValue(detail, 'Abertura')).toHaveTextContent(expectedMoney(100));
    expect(summaryValue(detail, 'Esperado')).toHaveTextContent(expectedMoney(177.4));

    // Os totais são os do servidor, sem soma local (BR-12).
    const totals = screen.getByRole('table', { name: 'Totais por tipo de movimento' });
    expect(within(totals).getByText('Abertura')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(100))).toBeInTheDocument();
    expect(within(totals).getByText('Vendas')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(72.4))).toBeInTheDocument();
    expect(within(totals).getByText('Sangrias')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(15))).toBeInTheDocument();
    expect(within(totals).getByText('Suprimentos')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(20))).toBeInTheDocument();

    expect(screen.getByRole('link', { name: 'Ver sessão e resumo →' })).toHaveAttribute(
      'href',
      `/cash-sessions/${SESSION_ID}`,
    );
    expect(screen.getByRole('link', { name: '← Voltar para caixas' })).toHaveAttribute(
      'href',
      '/cash-registers',
    );

    // A sessão atual é a rota do contrato, sem query nenhuma.
    expect(fetchStub).toHaveBeenCalledWith(
      `/api/v1/cash-registers/${FRONT_ID}/current-session`,
      expect.anything(),
    );
  });

  it('sem sessão aberta mostra o estado neutro do 404 CASH_SESSION_NOT_OPEN, não um erro', async () => {
    stubBackend({
      sessionError: {
        status: 404,
        body: {
          code: 'CASH_SESSION_NOT_OPEN',
          detail: `caixa ${FRONT_ID} não tem sessão aberta`,
        },
      },
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Caixa C1' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Sem sessão aberta' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('button', { name: 'Atualizar' })).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({
      sessionError: { status: 403, body: { code: 'ACCESS_DENIED', detail: 'acesso negado' } },
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sem sessão aberta' })).toBeNull();
  });
});
