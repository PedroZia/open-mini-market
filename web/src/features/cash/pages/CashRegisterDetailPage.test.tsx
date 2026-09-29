import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { formatDateTime } from '../../../shared/lib/datetime';
import { ToastProvider } from '../../../shared/ui/Toast';
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext';
import { CashRegisterDetailPage } from './CashRegisterDetailPage';

/**
 * Detalhe do caixa (1210a/1210b) com a rede stubada: a lista de caixas, a sessão atual de `GET
 * /cash-registers/{id}/current-session` e as ações de operação — sangria em `POST
 * /{id}/withdrawals`, suprimento em `POST /{id}/supplies` e fechamento em `POST /{id}/close`. Cada
 * teste confere a requisição que a tela fez (método, caminho, corpo cru e cabeçalhos) e o que o
 * servidor stubado devolve depois da escrita — o esperado é relido dele, nunca calculado aqui.
 *
 * A permissão de cada ação vem da sessão (`AuthContext`), como em produção; quem manda é o
 * servidor, e a tela só evita frustrar quem clicaria e levaria 403.
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

const FRONT_ID = '0198f5a1-3c4d-7e5f-8a91-000000000001';
const SESSION_ID = '0198f5b2-5e6f-7a81-9c13-000000000002';
const ANA_ID = '0198f3a2-4c1d-7a2e-9b3f-000000000001';

const REGISTERS_PATH = '/api/v1/cash-registers';
const CURRENT_SESSION_PATH = `${REGISTERS_PATH}/${FRONT_ID}/current-session`;
const WITHDRAWALS_PATH = `${REGISTERS_PATH}/${FRONT_ID}/withdrawals`;
const SUPPLIES_PATH = `${REGISTERS_PATH}/${FRONT_ID}/supplies`;
const CLOSE_PATH = `${REGISTERS_PATH}/${FRONT_ID}/close`;

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
const CURRENT_SESSION: Record<string, unknown> = {
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
  /** Resposta do `POST /withdrawals`; sem ela o stub aplica a sangria e devolve 201. */
  withdrawal?: { status: number; body: unknown };
  /** Resposta do `POST /supplies`; sem ela o stub aplica o suprimento e devolve 201. */
  supply?: { status: number; body: unknown };
  /** Resposta do `POST /close`; sem ela o stub fecha a sessão e devolve 200. */
  close?: { status: number; body: unknown };
}

/**
 * Serve cada rota do recurso como o servidor serve: a escrita muda o estado do stub e o `GET`
 * seguinte já reflete o esperado novo — é assim que o teste vê a invalidação.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  let session: Record<string, unknown> = { ...(options.session ?? CURRENT_SESSION) };
  let closed = false;

  const fetchStub: FetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const url = new URL(String(input), 'http://localhost');
    const pathname = url.pathname;

    if (pathname === REGISTERS_PATH && method === 'GET') {
      return jsonResponse(REGISTERS);
    }

    if (pathname === CURRENT_SESSION_PATH && method === 'GET') {
      if (options.sessionError !== undefined) {
        return jsonResponse(options.sessionError.body, options.sessionError.status);
      }
      if (closed) {
        return jsonResponse(
          { code: 'CASH_SESSION_NOT_OPEN', detail: `caixa ${FRONT_ID} não tem sessão aberta` },
          404,
        );
      }
      return jsonResponse(session);
    }

    if (pathname === WITHDRAWALS_PATH && method === 'POST') {
      if (options.withdrawal !== undefined) {
        return jsonResponse(options.withdrawal.body, options.withdrawal.status);
      }
      const body = JSON.parse(String(init?.body)) as { amount: number; reason: string };
      const before = Number(session.expectedAmount ?? 0);
      const after = before - body.amount;
      session = { ...session, expectedAmount: after };
      return jsonResponse(
        {
          sessionId: SESSION_ID,
          type: 'WITHDRAWAL',
          amount: body.amount,
          reason: body.reason,
          expectedBefore: before,
          expectedAfter: after,
          aboveExpected: false,
        },
        201,
      );
    }

    if (pathname === SUPPLIES_PATH && method === 'POST') {
      if (options.supply !== undefined) {
        return jsonResponse(options.supply.body, options.supply.status);
      }
      const body = JSON.parse(String(init?.body)) as { amount: number; reason: string };
      const before = Number(session.expectedAmount ?? 0);
      const after = before + body.amount;
      session = { ...session, expectedAmount: after };
      return jsonResponse(
        {
          sessionId: SESSION_ID,
          type: 'SUPPLY',
          amount: body.amount,
          reason: body.reason,
          expectedBefore: before,
          expectedAfter: after,
          aboveExpected: false,
        },
        201,
      );
    }

    if (pathname === CLOSE_PATH && method === 'POST') {
      if (options.close !== undefined) {
        return jsonResponse(options.close.body, options.close.status);
      }
      const body = JSON.parse(String(init?.body)) as { countedAmount: number; notes?: string };
      const expected = Number(session.expectedAmount ?? 0);
      const detail = {
        ...session,
        status: 'CLOSED',
        closedAt: '2026-09-28T23:30:00Z',
        closedByUserId: ANA_ID,
        countedAmount: body.countedAmount,
        differenceAmount: body.countedAmount - expected,
        closingNotes: body.notes ?? null,
      };
      closed = true;
      return jsonResponse(detail, 200);
    }

    throw new Error(`fetch inesperado: ${method} ${pathname}`);
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

const routes: RouteObject[] = [
  { path: '/cash-registers', element: <p>Lista de caixas</p> },
  { path: '/cash-registers/:id', element: <CashRegisterDetailPage /> },
  { path: '/cash-sessions/:id', element: <p>Detalhe da sessão</p> },
];

/** Sessão autenticada com as permissões do caso; o resto do contexto não é usado pela tela. */
function sessionWith(permissions: string[]): AuthContextValue {
  return {
    status: 'authenticated',
    user: { id: ANA_ID, username: 'ana', displayName: 'Ana Souza' },
    roles: [],
    permissions,
    login: async () => {},
    logout: async () => {},
  };
}

/**
 * Monta o detalhe do caixa com sessão, cache e toast próprios; `retry` desligado para o erro não
 * repetir e o padrão ser `cash.read` (as ações vêm da permissão, como em produção).
 */
function renderPage(permissions: string[] = ['cash.read']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [`/cash-registers/${FRONT_ID}`] });

  render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={sessionWith(permissions)}>
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>
      </AuthContext.Provider>
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
    expect(fetchStub).toHaveBeenCalledWith(CURRENT_SESSION_PATH, expect.anything());
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

describe('CashRegisterDetailPage — ações por permissão', () => {
  it('sem as permissões de operação não mostra ação nenhuma', async () => {
    stubBackend();
    renderPage(['cash.read']);
    await screen.findByText('Aberta em');

    expect(screen.queryByRole('button', { name: 'Sangria' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Suprimento' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Fechar caixa' })).toBeNull();
  });

  it('cash.withdrawal mostra só a sangria', async () => {
    stubBackend();
    renderPage(['cash.read', 'cash.withdrawal']);
    await screen.findByText('Aberta em');

    expect(screen.getByRole('button', { name: 'Sangria' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Suprimento' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Fechar caixa' })).toBeNull();
  });

  it('cash.supply mostra só o suprimento', async () => {
    stubBackend();
    renderPage(['cash.read', 'cash.supply']);
    await screen.findByText('Aberta em');

    expect(screen.getByRole('button', { name: 'Suprimento' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sangria' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Fechar caixa' })).toBeNull();
  });

  it('OPERADOR (cash.close) vê só o fechamento', async () => {
    stubBackend();
    renderPage(['cash.read', 'cash.close']);
    await screen.findByText('Aberta em');

    expect(screen.getByRole('button', { name: 'Fechar caixa' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sangria' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Suprimento' })).toBeNull();
  });

  it('caixa sem sessão aberta não mostra ação nenhuma, mesmo com as permissões', async () => {
    stubBackend({
      sessionError: {
        status: 404,
        body: { code: 'CASH_SESSION_NOT_OPEN', detail: 'sem sessão aberta' },
      },
    });
    renderPage(['cash.read', 'cash.withdrawal', 'cash.supply', 'cash.close']);

    expect(await screen.findByRole('heading', { name: 'Sem sessão aberta' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sangria' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Suprimento' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Fechar caixa' })).toBeNull();
  });
});

describe('CashRegisterDetailPage — sangria', () => {
  it('manda o valor cru e o motivo, relê o esperado e avisa no toast', async () => {
    const fetchStub = stubBackend();
    renderPage(['cash.read', 'cash.withdrawal']);
    await screen.findByText('Aberta em');

    fireEvent.click(screen.getByRole('button', { name: 'Sangria' }));
    const dialog = await screen.findByRole('dialog', { name: 'Sangria' });
    fireEvent.change(within(dialog).getByLabelText('Valor (R$)'), { target: { value: '12,50' } });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'troco levado ao cofre' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar sangria' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', WITHDRAWALS_PATH)).toHaveLength(1));

    const [, init] = lastCall(fetchStub, 'POST', WITHDRAWALS_PATH);
    // O valor digitado em pt-BR vai cru: `12,50` vira 12.5, sem arredondamento nenhum (BR-12).
    expect(JSON.parse(String(init?.body))).toEqual({
      amount: 12.5,
      reason: 'troco levado ao cofre',
    });
    // Operação de dinheiro idempotente por contrato (§8), sem If-Match: o recurso não tem versão.
    expect(headerOf(init, 'idempotency-key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(headerOf(init, 'if-match')).toBeNull();

    // Quem diz o esperado depois do movimento é o servidor: a tela relê o caixa.
    expect(await screen.findByText('Sangria registrada.')).toBeInTheDocument();
    await waitFor(() =>
      expect(summaryValue(detailList(), 'Esperado')).toHaveTextContent(expectedMoney(164.9)),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('sangria acima do esperado vira aviso e não erro, com o movimento gravado', async () => {
    const fetchStub = stubBackend({
      withdrawal: {
        status: 201,
        body: {
          sessionId: SESSION_ID,
          type: 'WITHDRAWAL',
          amount: 200,
          reason: 'troco',
          expectedBefore: 177.4,
          expectedAfter: -22.6,
          aboveExpected: true,
        },
      },
    });
    renderPage(['cash.read', 'cash.withdrawal']);
    await screen.findByText('Aberta em');

    fireEvent.click(screen.getByRole('button', { name: 'Sangria' }));
    const dialog = await screen.findByRole('dialog', { name: 'Sangria' });
    fireEvent.change(within(dialog).getByLabelText('Valor (R$)'), { target: { value: '200' } });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), { target: { value: 'troco' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar sangria' }));

    // O movimento foi registrado: o alerta é aviso (não erro) com o esperado que o servidor somou.
    const aviso = await screen.findByText(/acima do esperado/i);
    expect(aviso).toHaveTextContent(expectedMoney(-22.6));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls(fetchStub, 'POST', WITHDRAWALS_PATH)).toHaveLength(1);
  });
});

describe('CashRegisterDetailPage — suprimento', () => {
  it('manda o valor cru e o motivo, relê o esperado e avisa no toast', async () => {
    const fetchStub = stubBackend();
    renderPage(['cash.read', 'cash.supply']);
    await screen.findByText('Aberta em');

    fireEvent.click(screen.getByRole('button', { name: 'Suprimento' }));
    const dialog = await screen.findByRole('dialog', { name: 'Suprimento' });
    fireEvent.change(within(dialog).getByLabelText('Valor (R$)'), { target: { value: '20,50' } });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), {
      target: { value: 'troco do cofre' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar suprimento' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', SUPPLIES_PATH)).toHaveLength(1));

    const [, init] = lastCall(fetchStub, 'POST', SUPPLIES_PATH);
    expect(JSON.parse(String(init?.body))).toEqual({
      amount: 20.5,
      reason: 'troco do cofre',
    });
    expect(headerOf(init, 'idempotency-key')).toMatch(/^[0-9a-f-]{36}$/);

    expect(await screen.findByText('Suprimento registrado.')).toBeInTheDocument();
    await waitFor(() =>
      expect(summaryValue(detailList(), 'Esperado')).toHaveTextContent(expectedMoney(197.9)),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('CashRegisterDetailPage — fechamento', () => {
  it('manda o contado e as observações, fecha e o caixa fica sem sessão aberta', async () => {
    const fetchStub = stubBackend();
    renderPage(['cash.read', 'cash.close']);
    await screen.findByText('Aberta em');

    fireEvent.click(screen.getByRole('button', { name: 'Fechar caixa' }));
    const dialog = await screen.findByRole('dialog', { name: 'Fechar caixa' });
    fireEvent.change(within(dialog).getByLabelText('Valor contado (R$)'), {
      target: { value: '150,00' },
    });
    fireEvent.change(within(dialog).getByLabelText('Observações (opcional)'), {
      target: { value: 'conferido com o gerente' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Fechar caixa' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', CLOSE_PATH)).toHaveLength(1));

    const [, init] = lastCall(fetchStub, 'POST', CLOSE_PATH);
    expect(JSON.parse(String(init?.body))).toEqual({
      countedAmount: 150,
      notes: 'conferido com o gerente',
    });
    expect(headerOf(init, 'idempotency-key')).toMatch(/^[0-9a-f-]{36}$/);

    // Fechou: o toast confirma e a sessão atual passa a responder 404, como o servidor responde.
    expect(await screen.findByText('Caixa fechado.')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Sem sessão aberta' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Fechar caixa' })).toBeNull();
  });

  it('observação em branco não viaja no corpo', async () => {
    const fetchStub = stubBackend();
    renderPage(['cash.read', 'cash.close']);
    await screen.findByText('Aberta em');

    fireEvent.click(screen.getByRole('button', { name: 'Fechar caixa' }));
    const dialog = await screen.findByRole('dialog', { name: 'Fechar caixa' });
    fireEvent.change(within(dialog).getByLabelText('Valor contado (R$)'), {
      target: { value: '150' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Fechar caixa' }));

    await waitFor(() => expect(calls(fetchStub, 'POST', CLOSE_PATH)).toHaveLength(1));

    const [, init] = lastCall(fetchStub, 'POST', CLOSE_PATH);
    expect(JSON.parse(String(init?.body))).toEqual({ countedAmount: 150 });
  });
});

describe('CashRegisterDetailPage — erros das ações', () => {
  it('409 SESSION_HAS_OPEN_SALES mantém o modal com o recado e relê o caixa', async () => {
    const fetchStub = stubBackend({
      close: {
        status: 409,
        body: {
          code: 'SESSION_HAS_OPEN_SALES',
          detail: `sessão ${SESSION_ID} tem venda aberta`,
        },
      },
    });
    renderPage(['cash.read', 'cash.close']);
    await screen.findByText('Aberta em');
    const readsBefore = calls(fetchStub, 'GET', CURRENT_SESSION_PATH).length;

    fireEvent.click(screen.getByRole('button', { name: 'Fechar caixa' }));
    const dialog = await screen.findByRole('dialog', { name: 'Fechar caixa' });
    fireEvent.change(within(dialog).getByLabelText('Valor contado (R$)'), {
      target: { value: '150' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Fechar caixa' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent(/venda em andamento/i);
    // O `detail` do servidor traz o id da sessão; o operador lê o recado, não o rastro.
    expect(alert).not.toHaveTextContent(SESSION_ID);

    // O conflito diz que a tela estava velha: o caixa é relido e o modal fica para corrigir.
    await waitFor(() =>
      expect(calls(fetchStub, 'GET', CURRENT_SESSION_PATH).length).toBeGreaterThan(readsBefore),
    );
    expect(screen.getByRole('dialog', { name: 'Fechar caixa' })).toBeInTheDocument();
  });

  it('404 CASH_SESSION_NOT_OPEN na sangria explica o caixa sem sessão e relê o estado', async () => {
    const fetchStub = stubBackend({
      withdrawal: {
        status: 404,
        body: {
          code: 'CASH_SESSION_NOT_OPEN',
          detail: `caixa ${FRONT_ID} não tem sessão aberta`,
        },
      },
    });
    renderPage(['cash.read', 'cash.withdrawal']);
    await screen.findByText('Aberta em');
    const readsBefore = calls(fetchStub, 'GET', CURRENT_SESSION_PATH).length;

    fireEvent.click(screen.getByRole('button', { name: 'Sangria' }));
    const dialog = await screen.findByRole('dialog', { name: 'Sangria' });
    fireEvent.change(within(dialog).getByLabelText('Valor (R$)'), { target: { value: '10' } });
    fireEvent.change(within(dialog).getByLabelText('Motivo'), { target: { value: 'troco' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Registrar sangria' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent(/não tem sessão aberta/i);
    expect(alert).not.toHaveTextContent(FRONT_ID);

    await waitFor(() =>
      expect(calls(fetchStub, 'GET', CURRENT_SESSION_PATH).length).toBeGreaterThan(readsBefore),
    );
  });
});
