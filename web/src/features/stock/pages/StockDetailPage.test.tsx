import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { formatDateTime } from '../../../shared/lib/datetime';
import { formatQuantity } from '../../../shared/lib/quantity';
import { ToastProvider } from '../../../shared/ui/Toast';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { StockDetailPage } from './StockDetailPage';

/**
 * Detalhe do estoque (1206a/1206b) com a rede stubada: o resumo e os movimentos embutidos de `GET
 * /stock/{productId}`, o ajuste em `POST /stock/{productId}/adjustments` e a entrada em `POST
 * /stock/{productId}/receipts`. Cada teste confere a requisição que a tela fez (método, caminho,
 * corpo e cabeçalhos) e o que o servidor stubado devolve depois da escrita — o saldo e o histórico
 * são relidos dele, nunca calculados aqui.
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

/** Saldo do produto como o `GET /stock/{productId}` de 1206a o devolvia. */
const BASE_DETAIL = {
  productId: 'p1',
  name: 'Arroz',
  barcode: '7891001',
  unit: 'KG',
  quantity: 2.5,
  minQuantity: 5,
  lowStock: true,
  movements: [] as unknown[],
};

/** Movimento como o servidor o grava no ledger (§5): delta assinado e saldo resultante. */
const MOVEMENTS = [
  {
    id: 'm2',
    type: 'PURCHASE_IN',
    quantityDelta: 10,
    balanceAfter: 12.5,
    unitCost: 4.5,
    reason: 'compra do fornecedor',
    createdAt: '2026-09-28T22:21:54Z',
  },
  {
    id: 'm1',
    type: 'SALE_OUT',
    quantityDelta: -1.5,
    balanceAfter: 2.5,
    // O servidor manda `null` nos campos que o movimento não tem; o contrato só promete `number`.
    unitCost: null,
    reason: null,
    createdAt: '2026-09-27T10:00:00Z',
  },
];

interface StubBackendOptions {
  /** Detalhe servido no `GET`; sem ele, o detalhe base sem movimentos. */
  detail?: Record<string, unknown>;
  /** Status da leitura do detalhe; sem ele o detalhe vai com 200. */
  detailStatus?: number;
  /** Resposta do `POST /adjustments`; sem ela o stub aplica o delta e devolve 201. */
  adjustment?: { status: number; body: unknown };
  /** Resposta do `POST /receipts`; sem ela o stub aplica a quantidade e devolve 201. */
  receipt?: { status: number; body: unknown };
}

/**
 * Serve cada rota do recurso como o servidor serve: a escrita muda o estado do stub e o `GET`
 * seguinte já reflete o saldo e o movimento novos — é assim que o teste vê a invalidação.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let detail: Record<string, unknown> = { ...(options.detail ?? BASE_DETAIL) };

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const url = new URL(String(input), 'http://localhost');
    const pathname = url.pathname;

    if (/^\/api\/v1\/stock\/[^/]+$/.test(pathname) && method === 'GET') {
      return jsonResponse(detail, options.detailStatus ?? 200);
    }

    if (/^\/api\/v1\/stock\/[^/]+\/adjustments$/.test(pathname) && method === 'POST') {
      if (options.adjustment !== undefined) {
        return jsonResponse(options.adjustment.body, options.adjustment.status);
      }
      const body = JSON.parse(String(init?.body)) as { quantityDelta: number; reason: string };
      const before = Number(detail.quantity ?? 0);
      const after = before + body.quantityDelta;
      detail = {
        ...detail,
        quantity: after,
        movements: [
          {
            id: 'm-ajuste',
            type: 'ADJUSTMENT',
            quantityDelta: body.quantityDelta,
            balanceAfter: after,
            reason: body.reason,
            createdAt: '2026-09-28T23:00:00Z',
          },
          ...(detail.movements as unknown[]),
        ],
      };
      return jsonResponse(
        {
          movementId: 'm-ajuste',
          productId: 'p1',
          quantityDelta: body.quantityDelta,
          balanceBefore: before,
          balanceAfter: after,
        },
        201,
      );
    }

    if (/^\/api\/v1\/stock\/[^/]+\/receipts$/.test(pathname) && method === 'POST') {
      if (options.receipt !== undefined) {
        return jsonResponse(options.receipt.body, options.receipt.status);
      }
      const body = JSON.parse(String(init?.body)) as {
        quantity: number;
        unitCost?: number;
        reason?: string;
      };
      const before = Number(detail.quantity ?? 0);
      const after = before + body.quantity;
      detail = {
        ...detail,
        quantity: after,
        movements: [
          {
            id: 'm-entrada',
            type: 'PURCHASE_IN',
            quantityDelta: body.quantity,
            balanceAfter: after,
            unitCost: body.unitCost ?? null,
            reason: body.reason ?? null,
            createdAt: '2026-09-28T23:10:00Z',
          },
          ...(detail.movements as unknown[]),
        ],
      };
      return jsonResponse(
        {
          movementId: 'm-entrada',
          productId: 'p1',
          quantity: body.quantity,
          unitCost: body.unitCost ?? null,
          balanceBefore: before,
          balanceAfter: after,
        },
        201,
      );
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

/** `dd` do resumo a partir do rótulo: é o valor que a tela mostra para o campo. */
function summaryValue(label: string): HTMLElement {
  const value = screen.getByText(label).nextElementSibling;
  if (!(value instanceof HTMLElement)) {
    throw new Error(`resumo sem valor: ${label}`);
  }
  return value;
}

/** Movimentos que o servidor devolveu, na tabela do detalhe. */
function movementsTable(): HTMLElement {
  return screen.getByRole('table', { name: 'Movimentos de estoque' });
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
  { path: '/stock', element: <p>Lista de estoque</p> },
  { path: '/stock/:productId', element: <StockDetailPage /> },
];

/** Monta o detalhe de `p1` com sessão, cache e toast próprios; `retry` desligado para o erro não repetir. */
function renderPage(permissions: string[] = ['stock.read']) {
  const router = createMemoryRouter(routes, { initialEntries: ['/stock/p1'] });
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AuthContext.Provider value={sessionWith(permissions)}>
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
  return router;
}

describe('StockDetailPage — resumo', () => {
  it('mostra nome, código, unidade, saldo e mínimo em pt-BR com o selo de estoque baixo', async () => {
    stubBackend();
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Arroz' })).toBeInTheDocument();
    expect(screen.getByText('7891001')).toBeInTheDocument();
    expect(screen.getByText('KG')).toBeInTheDocument();
    expect(summaryValue('Saldo')).toHaveTextContent('2,5');
    expect(summaryValue('Mínimo')).toHaveTextContent('5');
    expect(screen.getByText('Estoque baixo')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Voltar para o estoque/ })).toHaveAttribute(
      'href',
      '/stock',
    );
  });

  it('mostra — no mínimo quando o produto não tem mínimo configurado', async () => {
    stubBackend({
      detail: {
        productId: 'p2',
        name: 'Refrigerante',
        barcode: '7891002',
        unit: 'UN',
        quantity: 12,
        minQuantity: null,
        lowStock: false,
        movements: [],
      },
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Refrigerante' })).toBeInTheDocument();
    expect(summaryValue('Mínimo')).toHaveTextContent('—');
    expect(screen.queryByText('Estoque baixo')).toBeNull();
  });
});

describe('StockDetailPage — movimentos', () => {
  it('lista tipo em pt-BR, delta com sinal, saldo após, custo e motivo', async () => {
    stubBackend({ detail: { ...BASE_DETAIL, movements: MOVEMENTS } });
    renderPage();
    await screen.findByRole('heading', { name: 'Arroz' });

    const table = movementsTable();
    expect(within(table).getByText('Entrada')).toBeInTheDocument();
    expect(within(table).getByText('Venda')).toBeInTheDocument();
    // O ganho leva o "+" explícito; a redução mantém o "-" do número.
    expect(within(table).getByText('+10')).toBeInTheDocument();
    expect(within(table).getByText('-1,5')).toBeInTheDocument();
    expect(within(table).getByText(formatQuantity(12.5))).toBeInTheDocument();
    // DINHEIRO em pt-BR (o `R$` usa espaço não separável; o casamento é pelo valor).
    expect(within(table).getByText(/4,50/)).toBeInTheDocument();
    expect(within(table).getByText('compra do fornecedor')).toBeInTheDocument();
    // Custo e motivo ausentes no movimento viram travessão, não "null".
    expect(within(table).getAllByText('—')).toHaveLength(2);
    // A data é o instante do servidor convertido para pt-BR, não o ISO cru.
    expect(
      within(table).getByText(formatDateTime('2026-09-28T22:21:54Z')),
    ).toBeInTheDocument();
    expect(within(table).queryByText('2026-09-28T22:21:54Z')).toBeNull();
  });

  it('mostra o estado vazio quando o produto ainda não tem movimento', async () => {
    stubBackend();
    renderPage();
    await screen.findByRole('heading', { name: 'Arroz' });

    expect(screen.getByText('Nenhum movimento registrado ainda.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Movimentos de estoque' })).toBeNull();
  });
});

describe('StockDetailPage — permissões', () => {
  it('esconde ajuste e entrada sem as permissões de escrita', async () => {
    stubBackend();
    renderPage(['stock.read']);
    await screen.findByRole('heading', { name: 'Arroz' });

    expect(screen.queryByRole('button', { name: 'Ajustar' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Entrada' })).toBeNull();
  });

  it('mostra só a ação que a sessão tem permissão', async () => {
    stubBackend();
    renderPage(['stock.read', 'stock.adjust']);
    await screen.findByRole('heading', { name: 'Arroz' });

    expect(screen.getByRole('button', { name: 'Ajustar' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Entrada' })).toBeNull();
  });

  it('mostra a entrada para quem só tem stock.receive', async () => {
    stubBackend();
    renderPage(['stock.read', 'stock.receive']);
    await screen.findByRole('heading', { name: 'Arroz' });

    expect(screen.getByRole('button', { name: 'Entrada' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ajustar' })).toBeNull();
  });
});

describe('StockDetailPage — ajuste', () => {
  it('manda o delta assinado e o motivo, relê o saldo e o histórico e avisa no toast', async () => {
    const fetchStub = stubBackend();
    renderPage(['stock.read', 'stock.adjust']);
    await screen.findByRole('heading', { name: 'Arroz' });
    const detailBefore = calls(fetchStub, 'GET', '/api/v1/stock/p1').length;

    fireEvent.click(screen.getByRole('button', { name: 'Ajustar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar estoque' });
    // O saldo atual vem do servidor; a tela não projeta o saldo novo.
    expect(within(dialog).getByText(/Saldo atual/)).toHaveTextContent('2,5');
    expect(within(dialog).queryByText('0')).toBeNull();

    fireEvent.change(within(dialog).getByLabelText('Quantidade (delta)'), {
      target: { value: '-2,5' },
    });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'quebra na prateleira' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar ajuste' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/stock/p1/adjustments')).toHaveLength(1),
    );

    const [, init] = lastCall(fetchStub, 'POST', '/api/v1/stock/p1/adjustments');
    expect(JSON.parse(String(init?.body))).toEqual({
      quantityDelta: -2.5,
      reason: 'quebra na prateleira',
    });
    // Operação idempotente por contrato (§8), sem If-Match: o recurso não tem versão.
    expect(headerOf(init, 'idempotency-key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(headerOf(init, 'if-match')).toBeNull();

    // Quem diz o saldo final é o servidor: o detalhe foi relido e mostra o novo saldo e o movimento.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', '/api/v1/stock/p1').length).toBeGreaterThan(detailBefore),
    );
    expect(await screen.findByText('Ajuste registrado.')).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Ajuste' })).toBeInTheDocument();
    expect(within(movementsTable()).getByText('-2,5')).toBeInTheDocument();
    expect(summaryValue('Saldo')).toHaveTextContent('0');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('bloqueia delta zero ou motivo vazio sem chamar o servidor', async () => {
    const fetchStub = stubBackend();
    renderPage(['stock.read', 'stock.adjust']);
    await screen.findByRole('heading', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Ajustar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar estoque' });
    fireEvent.change(within(dialog).getByLabelText('Quantidade (delta)'), {
      target: { value: '0' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar ajuste' }));

    expect(await screen.findByText('O delta não pode ser zero.')).toBeInTheDocument();
    expect(screen.getByText('Informe o motivo do ajuste.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/stock/p1/adjustments')).toHaveLength(0);
  });

  it('mostra a mensagem clara do 422 INSUFFICIENT_STOCK sem vazar o detalhe técnico', async () => {
    stubBackend({
      adjustment: {
        status: 422,
        body: {
          code: 'INSUFFICIENT_STOCK',
          detail: 'produto p1 ficaria com saldo -1.5 (delta -4) e a loja não permite estoque negativo',
        },
      },
    });
    renderPage(['stock.read', 'stock.adjust']);
    await screen.findByRole('heading', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Ajustar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar estoque' });
    fireEvent.change(within(dialog).getByLabelText('Quantidade (delta)'), {
      target: { value: '-4' },
    });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'inventário' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar ajuste' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent(/estoque insuficiente/i);
    expect(alert).not.toHaveTextContent('saldo -1.5');
    // O modal fica aberto para corrigir o delta.
    expect(screen.getByRole('dialog', { name: 'Ajustar estoque' })).toBeInTheDocument();
  });
});

describe('StockDetailPage — entrada', () => {
  it('manda quantidade, custo e motivo, relê o saldo e o histórico e avisa no toast', async () => {
    const fetchStub = stubBackend();
    renderPage(['stock.read', 'stock.receive']);
    await screen.findByRole('heading', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Entrada' }));
    const dialog = await screen.findByRole('dialog', { name: 'Entrada de mercadoria' });
    expect(within(dialog).getByText(/Saldo atual/)).toHaveTextContent('2,5');

    fireEvent.change(within(dialog).getByLabelText('Quantidade'), { target: { value: '10' } });
    fireEvent.change(within(dialog).getByLabelText('Custo unitário (R$)'), {
      target: { value: '5,25' },
    });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'compra do fornecedor' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/stock/p1/receipts')).toHaveLength(1),
    );

    const [, init] = lastCall(fetchStub, 'POST', '/api/v1/stock/p1/receipts');
    expect(JSON.parse(String(init?.body))).toEqual({
      quantity: 10,
      unitCost: 5.25,
      reason: 'compra do fornecedor',
    });
    expect(headerOf(init, 'idempotency-key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(headerOf(init, 'if-match')).toBeNull();

    expect(await screen.findByText('Entrada registrada.')).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'Entrada' })).toBeInTheDocument();
    expect(within(movementsTable()).getByText('+10')).toBeInTheDocument();
    expect(within(movementsTable()).getByText(/5,25/)).toBeInTheDocument();
    expect(summaryValue('Saldo')).toHaveTextContent(formatQuantity(12.5));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('omite custo e motivo em branco e recusa quantidade zero', async () => {
    const fetchStub = stubBackend();
    renderPage(['stock.read', 'stock.receive']);
    await screen.findByRole('heading', { name: 'Arroz' });

    fireEvent.click(screen.getByRole('button', { name: 'Entrada' }));
    const dialog = await screen.findByRole('dialog', { name: 'Entrada de mercadoria' });
    fireEvent.change(within(dialog).getByLabelText('Quantidade'), { target: { value: '0' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }));

    expect(await screen.findByText('A quantidade precisa ser maior que zero.')).toBeInTheDocument();
    expect(calls(fetchStub, 'POST', '/api/v1/stock/p1/receipts')).toHaveLength(0);

    fireEvent.change(within(dialog).getByLabelText('Quantidade'), { target: { value: '3' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }));

    await waitFor(() =>
      expect(calls(fetchStub, 'POST', '/api/v1/stock/p1/receipts')).toHaveLength(1),
    );
    const [, init] = lastCall(fetchStub, 'POST', '/api/v1/stock/p1/receipts');
    expect(JSON.parse(String(init?.body))).toEqual({ quantity: 3 });
  });
});

describe('StockDetailPage — erros de leitura', () => {
  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ detail: { code: 'ACCESS_DENIED' }, detailStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByText('Saldo')).toBeNull();
    // Sem leitura não há saldo nem ações de escrita.
    expect(screen.queryByRole('button', { name: 'Ajustar' })).toBeNull();
  });

  it('mostra a mensagem do problem+json e o caminho de volta quando o produto não existe', async () => {
    stubBackend({
      detail: { code: 'PRODUCT_NOT_FOUND', detail: 'produto não encontrado' },
      detailStatus: 404,
    });
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O registro não existe mais. Atualize a lista.',
    );
    expect(screen.getByRole('link', { name: /Voltar para o estoque/ })).toBeInTheDocument();
  });
});
