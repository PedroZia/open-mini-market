import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { formatDateTime } from '../../../shared/lib/datetime';
import { ToastProvider } from '../../../shared/ui/Toast';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { SaleDetailPage } from './SaleDetailPage';

/**
 * Detalhe da venda (1209b) com a rede stubada: o detalhe de `GET /sales/{id}`, o cancelamento em
 * `POST /sales/{id}/cancel` e a trilha de `GET /audit-events` (filtrada por `entityType=SALE` e
 * `entityId`). Cada teste confere a requisição que a tela fez (método, caminho, corpo e cabeçalhos)
 * e o que o servidor devolveu — nada de total, desconto ou troco é recalculado aqui (BR-12).
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
const CUSTOMER_ID = '0198f4d1-2b4c-7e6d-8f30-000000000004';

/** Venda aberta como `GET /sales/{id}` a devolve — valores do servidor, consistentes entre si. */
const OPEN_DETAIL: Record<string, unknown> = {
  id: 's1',
  number: 12,
  status: 'OPEN',
  operatorUserId: BRUNO_ID,
  customerId: CUSTOMER_ID,
  subtotal: 31.9,
  discountType: 'PERCENT',
  discountValue: 10,
  discountReason: 'cliente fiel',
  discountAmount: 3.19,
  total: 28.71,
  paidAmount: 28.71,
  changeAmount: 10,
  itemCount: 2,
  createdAt: '2026-09-28T22:21:54Z',
  items: [
    {
      productId: 'p1',
      barcode: '7891001',
      name: 'Arroz 5kg',
      unit: 'UN',
      unitPrice: 25.9,
      quantity: 1,
      lineTotal: 25.9,
    },
    {
      productId: 'p2',
      barcode: '7891002',
      name: 'Feijão 1kg',
      unit: 'KG',
      unitPrice: 4,
      quantity: 1.5,
      lineTotal: 6,
    },
  ],
  payments: [
    {
      id: 'pay1',
      method: 'CASH',
      amount: 20,
      tenderedAmount: 30,
      changeAmount: 10,
      status: 'APPROVED',
      createdAt: '2026-09-28T22:22:30Z',
    },
    { id: 'pay2', method: 'PIX', amount: 8.71, status: 'APPROVED', createdAt: '2026-09-28T22:23:00Z' },
  ],
};

const COMPLETED_DETAIL: Record<string, unknown> = {
  ...OPEN_DETAIL,
  status: 'COMPLETED',
  completedAt: '2026-09-28T22:30:00Z',
};

const CANCELLED_DETAIL: Record<string, unknown> = {
  ...OPEN_DETAIL,
  status: 'CANCELLED',
  cancelReason: 'cliente desistiu',
  cancelledByUserId: CARLA_ID,
  cancelledAt: '2026-09-28T22:40:00Z',
  // Venda cancelada antes do pagamento: os itens saem e nada foi pago.
  items: [],
  payments: [],
  paidAmount: 0,
  changeAmount: 0,
};

/** Eventos da trilha como o `GET /audit-events` os devolve, do mais antigo para o mais novo. */
const AUDIT_EVENTS = [
  {
    id: 1,
    action: 'SALE_CREATED',
    actorUsername: 'ana',
    source: 'TUI',
    occurredAt: '2026-09-28T22:21:54Z',
  },
  {
    id: 2,
    action: 'PAYMENT_ADDED',
    actorUsername: 'ana',
    source: 'TUI',
    occurredAt: '2026-09-28T22:22:30Z',
  },
  {
    id: 3,
    action: 'SALE_DISCOUNT_APPLIED',
    actorUsername: 'carla',
    source: 'WEB',
    reason: 'cliente fiel',
    occurredAt: '2026-09-28T22:23:10Z',
  },
];

interface StubBackendOptions {
  /** Detalhe servido no `GET`; sem ele, a venda aberta do fixture. */
  detail?: Record<string, unknown>;
  /** Falha da leitura do detalhe (403, 404...); sem ela o detalhe vai com 200. */
  detailError?: { status: number; body: unknown };
  /** Resposta do `POST /cancel`; sem ela o stub cancela de verdade e devolve a venda cancelada. */
  cancel?: { status: number; body: unknown; nextDetail?: Record<string, unknown> };
  /** Eventos da trilha; sem eles, os do fixture. */
  events?: unknown[];
}

/**
 * Serve cada rota como o servidor serve: o cancelamento muda o estado do stub e o `GET` seguinte já
 * devolve a venda cancelada — é assim que o teste vê a invalidação. Qualquer rota inesperada falha.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let detail: Record<string, unknown> = { ...(options.detail ?? OPEN_DETAIL) };

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const url = new URL(String(input), 'http://localhost');
    const pathname = url.pathname;

    if (pathname === '/api/v1/sales/s1' && method === 'GET') {
      if (options.detailError !== undefined) {
        return jsonResponse(options.detailError.body, options.detailError.status);
      }
      return jsonResponse(detail);
    }

    if (pathname === '/api/v1/sales/s1/cancel' && method === 'POST') {
      if (options.cancel !== undefined) {
        if (options.cancel.nextDetail !== undefined) {
          detail = options.cancel.nextDetail;
        }
        return jsonResponse(options.cancel.body, options.cancel.status);
      }
      const body = JSON.parse(String(init?.body)) as { reason: string };
      detail = {
        ...detail,
        status: 'CANCELLED',
        cancelReason: body.reason,
        cancelledByUserId: CARLA_ID,
        cancelledAt: '2026-09-28T23:00:00Z',
      };
      return jsonResponse(detail);
    }

    if (pathname === '/api/v1/audit-events' && method === 'GET') {
      const events = options.events ?? AUDIT_EVENTS;
      return jsonResponse({
        items: events,
        page: 0,
        size: 100,
        totalItems: events.length,
        totalPages: 1,
      });
    }

    throw new Error(`fetch inesperado: ${method} ${url.pathname}`);
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
  const call = calls(fetchStub, method, pathname).at(-1);
  if (call === undefined) {
    throw new Error(`nenhuma chamada ${method} ${pathname}`);
  }
  return call;
}

/** Cabeçalho da requisição como o `fetch` o recebeu (o client manda `Headers`). */
function headerOf(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name);
}

/** Seção dona de um título (o `<section>` mais próximo), para ler valores sem cruzar tabelas. */
function sectionOf(heading: string): HTMLElement {
  const section = screen.getByRole('heading', { name: heading }).closest('section');
  if (section === null) {
    throw new Error(`seção sem título: ${heading}`);
  }
  return section;
}

/** Valor do resumo a partir do rótulo: é o `dd` que a tela mostra para o campo. */
function summaryValue(scope: HTMLElement, label: string): HTMLElement {
  const value = within(scope).getByText(label).nextElementSibling;
  if (!(value instanceof HTMLElement)) {
    throw new Error(`resumo sem valor: ${label}`);
  }
  return value;
}

/**
 * Dinheiro como a tela mostra: BRL, sem recálculo nenhum (BR-12). O espaço do `Intl` é
 * não separável e a query de texto normaliza o texto do DOM com `\s+`: a expectativa precisa da
 * mesma normalização para casar.
 */
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

const routes: RouteObject[] = [
  { path: '/sales', element: <p>Lista de vendas</p> },
  { path: '/sales/:id', element: <SaleDetailPage /> },
];

/**
 * Monta o detalhe de `s1` com sessão, cache e toast próprios; `retry` desligado para o erro não
 * repetir. Devolve o client para o teste conferir a invalidação da lista no cache.
 */
function renderPage(permissions: string[] = ['report.read']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: ['/sales/s1'] });

  render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={sessionWith(permissions)}>
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
  return { client, router };
}

describe('SaleDetailPage — detalhe', () => {
  it('mostra cabeçalho, itens, pagamentos, desconto e totais com os valores do servidor', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Venda 12' })).toBeInTheDocument();
    expect(screen.getByText('Aberta')).toBeInTheDocument();
    expect(screen.getByText(formatDateTime('2026-09-28T22:21:54Z'))).toBeInTheDocument();
    // Operador e cliente aparecem pelo id curto: o detalhe não traz nome, e a tela não inventa.
    expect(screen.getByText('0198f3b7')).toBeInTheDocument();
    expect(screen.getByText('0198f4d1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '← Voltar para vendas' })).toHaveAttribute(
      'href',
      '/sales',
    );

    const items = screen.getByRole('table', { name: 'Itens da venda' });
    expect(within(items).getByText('Arroz 5kg')).toBeInTheDocument();
    expect(within(items).getByText('7891001')).toBeInTheDocument();
    expect(within(items).getByText('UN')).toBeInTheDocument();
    // Quantidade na escala do contrato, em pt-BR.
    expect(within(items).getByText('1,5')).toBeInTheDocument();
    // Preço unitário e total da linha são os do servidor (aqui coincidem na linha de 1 unidade).
    expect(within(items).getAllByText(expectedMoney(25.9))).toHaveLength(2);
    expect(within(items).getByText(expectedMoney(4))).toBeInTheDocument();
    expect(within(items).getByText(expectedMoney(6))).toBeInTheDocument();

    const payments = screen.getByRole('table', { name: 'Pagamentos da venda' });
    expect(within(payments).getByText('Dinheiro')).toBeInTheDocument();
    expect(within(payments).getByText('PIX')).toBeInTheDocument();
    expect(within(payments).getByText(expectedMoney(20))).toBeInTheDocument();
    expect(within(payments).getByText(expectedMoney(8.71))).toBeInTheDocument();
    expect(within(payments).getByText(expectedMoney(10))).toBeInTheDocument();
    expect(within(payments).getAllByText('Aprovado')).toHaveLength(2);

    const totals = sectionOf('Totais');
    expect(summaryValue(totals, 'Subtotal')).toHaveTextContent(expectedMoney(31.9));
    expect(summaryValue(totals, 'Desconto')).toHaveTextContent(expectedMoney(3.19));
    expect(within(totals).getByText(/Percentual informado: 10%/)).toBeInTheDocument();
    expect(within(totals).getByText(/cliente fiel/)).toBeInTheDocument();
    expect(summaryValue(totals, 'Total')).toHaveTextContent(expectedMoney(28.71));
    expect(summaryValue(totals, 'Pago')).toHaveTextContent(expectedMoney(28.71));
    expect(summaryValue(totals, 'Troco')).toHaveTextContent(expectedMoney(10));
  });

  it('mostra o motivo, o autor e a data do cancelamento e o vazio de itens e pagamentos', async () => {
    stubBackend({ detail: CANCELLED_DETAIL });
    renderPage(['report.read', 'sale.cancel']);

    expect(await screen.findByRole('heading', { name: 'Venda 12' })).toBeInTheDocument();
    expect(screen.getByText('Cancelada')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Venda cancelada' })).toBeInTheDocument();
    expect(screen.getByText('cliente desistiu')).toBeInTheDocument();
    expect(screen.getByText('0198f3c9')).toBeInTheDocument();
    expect(screen.getByText(formatDateTime('2026-09-28T22:40:00Z'))).toBeInTheDocument();
    expect(screen.getByText('Nenhum item na venda.')).toBeInTheDocument();
    expect(screen.getByText('Nenhum pagamento registrado.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).toBeNull();
  });
});

describe('SaleDetailPage — cancelamento', () => {
  it('só oferece o cancelamento com a venda aberta e sale.cancel na sessão', async () => {
    stubBackend();
    renderPage(['report.read']);

    await screen.findByRole('heading', { name: 'Venda 12' });
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).toBeNull();
  });

  it('não oferece o cancelamento de venda concluída', async () => {
    stubBackend({ detail: COMPLETED_DETAIL });
    renderPage(['report.read', 'sale.cancel']);

    await screen.findByRole('heading', { name: 'Venda 12' });
    expect(screen.getByText('Concluída')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).toBeNull();
  });

  it('exige o motivo sem chamar o servidor', async () => {
    const fetchStub = stubBackend();
    renderPage(['report.read', 'sale.cancel']);
    await screen.findByRole('heading', { name: 'Venda 12' });

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar venda' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancelar venda' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmar cancelamento' }));

    expect(await within(dialog).findByText('Informe o motivo do cancelamento.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/sales/s1/cancel')).toHaveLength(0);
  });

  it('envia o motivo, avisa no toast e relê detalhe e lista', async () => {
    const fetchStub = stubBackend();
    const { client } = renderPage(['report.read', 'sale.cancel']);
    await screen.findByRole('heading', { name: 'Venda 12' });
    const detailBefore = calls(fetchStub, 'GET', '/api/v1/sales/s1').length;

    // A lista vive sob o mesmo prefixo de cache: o cancelamento tem de invalidá-la também.
    client.setQueryData(['sales', { page: 0, size: 20 }], {
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 0,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar venda' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancelar venda' });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'cliente desistiu' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmar cancelamento' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/sales/s1/cancel')).toHaveLength(1),
    );
    const [, init] = lastCall(fetchStub, 'POST', '/api/v1/sales/s1/cancel');
    expect(JSON.parse(String(init?.body))).toEqual({ reason: 'cliente desistiu' });
    // Operação de dinheiro/estado idempotente por contrato (§8): o client manda a chave sozinho.
    expect(headerOf(init, 'idempotency-key')).toMatch(/^[0-9a-f-]{36}$/);

    // Quem diz a situação final é o servidor: o detalhe foi relido com a venda cancelada.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/sales/s1').length).toBeGreaterThan(detailBefore),
    );
    expect(await screen.findByText('Venda cancelada.')).toBeInTheDocument();
    expect(await screen.findByText('Cancelada')).toBeInTheDocument();
    expect(screen.getByText('cliente desistiu')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(client.getQueryState(['sales', { page: 0, size: 20 }])?.isInvalidated).toBe(true);
  });

  it('mostra a mensagem clara do 409 SALE_ALREADY_COMPLETED e relê o detalhe', async () => {
    // Outro operador conclui a venda entre a leitura da tela e o clique.
    const fetchStub = stubBackend({
      cancel: {
        status: 409,
        body: {
          code: 'SALE_ALREADY_COMPLETED',
          detail: 'venda s1 já foi concluída em 2026-09-28T23:00:00Z e não pode ser cancelada',
        },
        nextDetail: COMPLETED_DETAIL,
      },
    });
    renderPage(['report.read', 'sale.cancel']);
    await screen.findByRole('heading', { name: 'Venda 12' });
    const detailBefore = calls(fetchStub, 'GET', '/api/v1/sales/s1').length;

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar venda' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancelar venda' });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'cliente desistiu' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmar cancelamento' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Esta venda já foi concluída e não pode ser cancelada.');
    // O `detail` técnico do servidor (id e instante) não vai para a tela.
    expect(alert).not.toHaveTextContent('2026-09-28T23:00:00Z');

    // A situação na tela estava velha: o detalhe é relido e o cancelamento some.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/sales/s1').length).toBeGreaterThan(detailBefore),
    );
    expect(await screen.findByText('Concluída')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).toBeNull();
  });
});

describe('SaleDetailPage — trilha de auditoria', () => {
  it('lista ação, quem, quando, motivo e origem com audit.read', async () => {
    const fetchStub = stubBackend();
    renderPage(['report.read', 'audit.read']);
    await screen.findByRole('heading', { name: 'Venda 12' });

    const trail = sectionOf('Trilha de auditoria');
    expect(await within(trail).findByText('Venda aberta')).toBeInTheDocument();
    expect(within(trail).getByText('Pagamento registrado')).toBeInTheDocument();
    expect(within(trail).getByText('Desconto aplicado')).toBeInTheDocument();
    expect(within(trail).getAllByText(/ana/)).toHaveLength(2);
    expect(within(trail).getByText(/carla/)).toBeInTheDocument();
    expect(within(trail).getByText(/Retaguarda/)).toBeInTheDocument();
    expect(within(trail).getByText(/Motivo: cliente fiel/)).toBeInTheDocument();
    expect(within(trail).getByText(formatDateTime('2026-09-28T22:21:54Z'))).toBeInTheDocument();

    // A trilha é a consulta de auditoria com o alvo fixo, em ordem crescente (o detalhe não a traz).
    const auditCalls = calls(fetchStub, 'GET', '/api/v1/audit-events');
    expect(auditCalls).toHaveLength(1);
    const params = new URL(String(auditCalls[0]?.[0]), 'http://localhost').searchParams;
    expect(params.get('entityType')).toBe('SALE');
    expect(params.get('entityId')).toBe('s1');
    expect(params.get('sort')).toBe('occurredat,asc');
    expect(params.get('size')).toBe('100');
  });

  it('sem audit.read não mostra a trilha nem chama a rota', async () => {
    const fetchStub = stubBackend();
    renderPage(['report.read']);
    await screen.findByRole('heading', { name: 'Venda 12' });

    expect(screen.queryByRole('heading', { name: 'Trilha de auditoria' })).toBeNull();
    expect(calls(fetchStub, 'GET', '/api/v1/audit-events')).toHaveLength(0);
  });
});

describe('SaleDetailPage — erros de leitura', () => {
  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({
      detailError: { status: 403, body: { code: 'ACCESS_DENIED', detail: 'venda de outro caixa' } },
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Venda 12' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).toBeNull();
  });

  it('mostra a mensagem clara e o caminho de volta quando a venda não existe', async () => {
    stubBackend({
      detailError: {
        status: 404,
        body: { code: 'SALE_NOT_FOUND', detail: 'venda 019... não encontrada' },
      },
    });
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O registro não existe mais. Atualize a lista.',
    );
    expect(screen.getByRole('link', { name: '← Voltar para vendas' })).toBeInTheDocument();
  });
});
