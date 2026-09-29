import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { formatDateTime } from '../../../shared/lib/datetime';
import { CashSessionDetailPage } from './CashSessionDetailPage';

/**
 * Sessão de caixa (1210a) com a rede stubada: o detalhe de `GET /cash-sessions/{id}` e o resumo de
 * `GET /{id}/summary`. A tela desenha esperado, contado, diferença, os totais por tipo de movimento
 * e as vendas por forma de pagamento — tudo como o servidor devolveu, sem soma local (BR-12).
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
const BRUNO_ID = '0198f3b7-8e2f-7c4a-8d1e-000000000002';

/** Sessão fechada com falta de R$ 5,00 na conferência, como o detalhe a devolve. */
const CLOSED_DETAIL: Record<string, unknown> = {
  id: SESSION_ID,
  cashRegisterId: FRONT_ID,
  status: 'CLOSED',
  openedAt: '2026-09-28T08:00:00Z',
  openedByUserId: ANA_ID,
  openingAmount: 100,
  closedAt: '2026-09-28T23:10:00Z',
  closedByUserId: BRUNO_ID,
  countedAmount: 172.4,
  expectedAmount: 177.4,
  differenceAmount: -5,
  closingNotes: 'faltou troco',
};

/** Resumo da mesma sessão: os totais são do servidor e a diferença é a do fechamento. */
const CLOSED_SUMMARY: Record<string, unknown> = {
  sessionId: SESSION_ID,
  status: 'CLOSED',
  openingAmount: 100,
  expectedAmount: 177.4,
  countedAmount: 172.4,
  differenceAmount: -5,
  totalsByType: { OPENING: 100, SALE: 72.4, WITHDRAWAL: 15, SUPPLY: 20 },
  paymentsByMethod: { CASH: 50, PIX: 22.4, DEBIT: 0, CREDIT: 0, VOUCHER: 0 },
};

/** Sessão ainda aberta: contado e diferença são nulos até o fechamento. */
const OPEN_DETAIL: Record<string, unknown> = {
  ...CLOSED_DETAIL,
  status: 'OPEN',
  closedAt: undefined,
  closedByUserId: undefined,
  countedAmount: null,
  differenceAmount: null,
  closingNotes: undefined,
};

const OPEN_SUMMARY: Record<string, unknown> = {
  ...CLOSED_SUMMARY,
  status: 'OPEN',
  countedAmount: null,
  differenceAmount: null,
};

interface StubBackendOptions {
  /** Detalhe servido no `GET /cash-sessions/{id}`; sem ele, a sessão fechada do fixture. */
  detail?: Record<string, unknown>;
  /** Resumo servido no `GET /{id}/summary`; sem ele, o resumo da sessão fechada. */
  summary?: Record<string, unknown>;
  /** Falha das duas leituras (403...); sem ela as respostas vão com 200. */
  error?: { status: number; body: unknown };
}

function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const fetchStub: FetchStub = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost');

    if (url.pathname === `/api/v1/cash-sessions/${SESSION_ID}`) {
      if (options.error !== undefined) {
        return jsonResponse(options.error.body, options.error.status);
      }
      return jsonResponse(options.detail ?? CLOSED_DETAIL);
    }
    if (url.pathname === `/api/v1/cash-sessions/${SESSION_ID}/summary`) {
      if (options.error !== undefined) {
        return jsonResponse(options.error.body, options.error.status);
      }
      return jsonResponse(options.summary ?? CLOSED_SUMMARY);
    }

    throw new Error(`fetch inesperado: ${url.pathname}`);
  });
  vi.stubGlobal('fetch', fetchStub);
  return fetchStub;
}

const routes: RouteObject[] = [
  { path: '/cash-registers', element: <p>Lista de caixas</p> },
  { path: '/cash-registers/:id', element: <p>Detalhe do caixa</p> },
  { path: '/cash-sessions/:id', element: <CashSessionDetailPage /> },
];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [`/cash-sessions/${SESSION_ID}`] });

  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** Dinheiro como a tela mostra: BRL, sem recálculo nenhum (BR-12). */
function expectedMoney(value: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
    .format(value)
    .replace(/\s+/g, ' ');
}

/** O `dl` do detalhe da sessão, para ler os valores sem cruzar com as tabelas do resumo. */
function detailList(): HTMLElement {
  const dl = screen.getByText('Aberta em').closest('dl');
  if (dl === null) {
    throw new Error('dl do detalhe não encontrado');
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

/** Seção dona de um título (o `<section>` mais próximo), para ler valores sem cruzar blocos. */
function sectionOf(heading: string): HTMLElement {
  const section = screen.getByRole('heading', { name: heading }).closest('section');
  if (section === null) {
    throw new Error(`seção sem título: ${heading}`);
  }
  return section;
}

describe('CashSessionDetailPage', () => {
  it('mostra abertura, fechamento e o resumo do servidor com as duas quebras', async () => {
    const fetchStub = stubBackend();
    renderPage();

    // O cabeçalho aparece antes do servidor responder: a espera é pelo detalhe já desenhado.
    expect(await screen.findByText('Aberta em')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Sessão de caixa' })).toBeInTheDocument();
    expect(screen.getByText('Fechado')).toBeInTheDocument();

    const detail = detailList();
    expect(summaryValue(detail, 'Aberta em')).toHaveTextContent(
      formatDateTime('2026-09-28T08:00:00Z'),
    );
    expect(summaryValue(detail, 'Fechada em')).toHaveTextContent(
      formatDateTime('2026-09-28T23:10:00Z'),
    );
    expect(summaryValue(detail, 'Operador')).toHaveTextContent('0198f3a2');
    expect(summaryValue(detail, 'Abertura')).toHaveTextContent(expectedMoney(100));
    expect(screen.getByText(/faltou troco/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '0198f5a1' })).toHaveAttribute(
      'href',
      `/cash-registers/${FRONT_ID}`,
    );
    expect(screen.getByRole('link', { name: '← Voltar para caixas' })).toHaveAttribute(
      'href',
      `/cash-registers/${FRONT_ID}`,
    );

    const summary = sectionOf('Resumo');
    expect(summaryValue(summary, 'Esperado')).toHaveTextContent(expectedMoney(177.4));
    expect(summaryValue(summary, 'Contado')).toHaveTextContent(expectedMoney(172.4));
    expect(summaryValue(summary, 'Diferença')).toHaveTextContent(expectedMoney(-5));
    expect(within(summary).getByText('Falta')).toBeInTheDocument();

    const totals = within(summary).getByRole('table', { name: 'Totais por tipo de movimento' });
    expect(within(totals).getByText('Abertura')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(100))).toBeInTheDocument();
    expect(within(totals).getByText('Vendas')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(72.4))).toBeInTheDocument();
    expect(within(totals).getByText('Sangrias')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(15))).toBeInTheDocument();
    expect(within(totals).getByText('Suprimentos')).toBeInTheDocument();
    expect(within(totals).getByText(expectedMoney(20))).toBeInTheDocument();

    const payments = within(summary).getByRole('table', {
      name: 'Vendas por forma de pagamento',
    });
    expect(within(payments).getByText('Dinheiro')).toBeInTheDocument();
    expect(within(payments).getByText(expectedMoney(50))).toBeInTheDocument();
    expect(within(payments).getByText('PIX')).toBeInTheDocument();
    expect(within(payments).getByText(expectedMoney(22.4))).toBeInTheDocument();
    expect(within(payments).getByText('Débito')).toBeInTheDocument();
    expect(within(payments).getByText('Crédito')).toBeInTheDocument();
    expect(within(payments).getByText('Voucher')).toBeInTheDocument();
    expect(within(payments).getAllByText(expectedMoney(0))).toHaveLength(3);

    // O detalhe e o resumo são as duas rotas do contrato, sem query nenhuma.
    expect(fetchStub).toHaveBeenCalledWith(
      `/api/v1/cash-sessions/${SESSION_ID}`,
      expect.anything(),
    );
    expect(fetchStub).toHaveBeenCalledWith(
      `/api/v1/cash-sessions/${SESSION_ID}/summary`,
      expect.anything(),
    );
  });

  it('sessão aberta mostra contado e diferença como traço, sem data de fechamento', async () => {
    stubBackend({ detail: OPEN_DETAIL, summary: OPEN_SUMMARY });
    renderPage();

    // O cabeçalho aparece antes do servidor responder: a espera é pelo detalhe já desenhado.
    expect(await screen.findByText('Aberta em')).toBeInTheDocument();
    expect(screen.getByText('Aberto')).toBeInTheDocument();
    expect(screen.queryByText('Fechada em')).toBeNull();
    expect(screen.queryByText(/faltou troco/)).toBeNull();

    const detail = detailList();
    expect(summaryValue(detail, 'Abertura')).toHaveTextContent(expectedMoney(100));

    const summary = sectionOf('Resumo');
    expect(summaryValue(summary, 'Esperado')).toHaveTextContent(expectedMoney(177.4));
    expect(summaryValue(summary, 'Contado')).toHaveTextContent('—');
    expect(summaryValue(summary, 'Diferença')).toHaveTextContent('—');
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({
      error: { status: 403, body: { code: 'ACCESS_DENIED', detail: 'acesso negado' } },
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    // O estado negado substitui o conteúdo: nada de detalhe nem resumo na tela.
    expect(screen.queryByText('Fechado')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Resumo' })).toBeNull();
  });
});
