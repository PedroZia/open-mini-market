import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { AuditPage } from './AuditPage';

/**
 * Consulta de auditoria (1211a) com a rede stubada — o setup do web desliga o `fetch` real. Cada
 * teste instala o seu stub e confere o que a tela pediu ao servidor, como manda o contrato (§9.3):
 * `GET /audit-events` só com os filtros preenchidos (tipos e UUIDs exatos, período em ISO com
 * offset, página/tamanho e `sort` restrito a `occurredat`), com paginação de servidor.
 *
 * A página não usa permissão para montar a query — quem barra é o servidor: o 403 da rota vira o
 * estado "sem permissão". Por isso os testes renderizam só o `QueryClientProvider`.
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

/** Evento de auditoria como o stub o guarda; espelha o `AuditEventResponse` sem repetir o tipo. */
interface StubAuditEvent {
  id: number;
  occurredAt: string;
  action: string;
  entityType: string;
  entityId?: string;
  actorUserId?: string;
  actorUsername?: string;
  source: string;
  reason?: string;
  cashSessionId?: string;
  details?: Record<string, unknown>;
}

// UUIDs no formato do contrato: os campos de id só entram na query fechados nesse formato.
const ENTITY_ID = '0198f9c4-5b6d-7e8f-9a0b-000000000010';
const PRODUCT_ID = '0198f9c4-5b6d-7e8f-9a0b-000000000011';
const ANA_ID = '0198f3a2-4c1d-7a2e-9b3f-000000000001';
const BRUNO_ID = '0198f3b7-8e2f-7c4a-8d1e-000000000002';
const CASH_SESSION_ID = '0198f5a1-3c4d-7e5f-8a91-000000000003';

const SALE_COMPLETED: StubAuditEvent = {
  id: 2,
  occurredAt: '2026-09-28T22:21:54Z',
  action: 'SALE_COMPLETED',
  entityType: 'SALE',
  entityId: ENTITY_ID,
  actorUserId: BRUNO_ID,
  actorUsername: 'bruno',
  source: 'TUI',
  reason: 'Fechamento do turno',
  cashSessionId: CASH_SESSION_ID,
  // O antes/depois de uma alteração e mais uma chave fora do par: os dois têm que aparecer.
  details: {
    before: { total: 120 },
    after: { total: 140 },
    paymentsByMethod: { CASH: 140, PIX: 10 },
  },
};

// Sem `actorUsername`, sem `entityId` e sem `details`: a tela cai no id curto do autor, no tipo
// puro da entidade e no estado "Sem detalhes".
const LOGIN_FAILED: StubAuditEvent = {
  id: 1,
  occurredAt: '2026-09-28T20:00:00Z',
  action: 'LOGIN_FAILED',
  entityType: 'USER',
  actorUserId: ANA_ID,
  source: 'WEB',
};

const EVENTS: StubAuditEvent[] = [SALE_COMPLETED, LOGIN_FAILED];

interface StubBackendOptions {
  events?: StubAuditEvent[];
  /** Status da leitura; sem ele a consulta vai com 200. */
  listStatus?: number;
}

/**
 * Serve `GET /audit-events` como o servidor serve: filtros exatos, período com piso inclusivo e
 * teto exclusivo, ordenação só por `occurredat` (default desc) e paginação. Rota inesperada
 * derruba o teste.
 */
function stubBackend(options: StubBackendOptions = {}): FetchStub {
  const events = options.events ?? EVENTS;

  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const parsed = new URL(url, 'http://localhost');
    const pathname = parsed.pathname;

    if (pathname === '/api/v1/audit-events' && method === 'GET') {
      if (options.listStatus !== undefined && options.listStatus !== 200) {
        return jsonResponse(
          { status: options.listStatus, code: 'ACCESS_DENIED', detail: 'acesso negado' },
          options.listStatus,
        );
      }

      const params = parsed.searchParams;
      const from = params.get('from');
      const to = params.get('to');
      const entityType = params.get('entityType');
      const entityId = params.get('entityId');
      const actorUserId = params.get('actorUserId');
      const action = params.get('action');
      const cashSessionId = params.get('cashSessionId');

      const found = events.filter((event) => {
        const matchesFrom = from === null || Date.parse(event.occurredAt) >= Date.parse(from);
        const matchesTo = to === null || Date.parse(event.occurredAt) < Date.parse(to);
        const matchesEntityType = entityType === null || event.entityType === entityType;
        const matchesEntityId = entityId === null || event.entityId === entityId;
        const matchesActor = actorUserId === null || event.actorUserId === actorUserId;
        const matchesAction = action === null || event.action === action;
        const matchesCashSession = cashSessionId === null || event.cashSessionId === cashSessionId;
        return (
          matchesFrom &&
          matchesTo &&
          matchesEntityType &&
          matchesEntityId &&
          matchesActor &&
          matchesAction &&
          matchesCashSession
        );
      });

      const ascending = (params.get('sort') ?? 'occurredat,desc') === 'occurredat,asc';
      const ordered = [...found].sort((left, right) =>
        ascending
          ? Date.parse(left.occurredAt) - Date.parse(right.occurredAt)
          : Date.parse(right.occurredAt) - Date.parse(left.occurredAt),
      );

      const page = Number(params.get('page') ?? 0);
      const size = Number(params.get('size') ?? 20);
      return jsonResponse({
        items: ordered.slice(page * size, page * size + size),
        page,
        size,
        totalItems: ordered.length,
        totalPages: Math.ceil(ordered.length / size),
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

/** Query da última leitura — é nela que a tela prova o que pediu ao servidor. */
function lastQuery(fetchStub: FetchStub): URLSearchParams {
  const last = calls(fetchStub, 'GET', '/api/v1/audit-events').at(-1);
  if (last === undefined) {
    throw new Error('nenhuma chamada a /api/v1/audit-events');
  }
  return new URL(String(last[0]), 'http://localhost').searchParams;
}

/** Data formatada como a coluna mostra: pt-BR, fuso local (só apresentação). */
function expectedDate(instant: string): string {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={client}>
      <AuditPage />
    </QueryClientProvider>,
  );
}

/** Espera a consulta inicial aparecer; o evento concluído é a âncora dos testes. */
async function waitForList(): Promise<void> {
  await screen.findByRole('cell', { name: 'SALE_COMPLETED' });
}

/** Linha da tabela pela ação do evento. */
function rowOf(action: string): HTMLElement {
  const cell = screen.getByRole('cell', { name: action });
  const row = cell.closest('tr');
  if (row === null) {
    throw new Error(`linha da ação ${action} não encontrada`);
  }
  return row;
}

describe('AuditPage — consulta', () => {
  it('renderiza instante, ação, autor, entidade, origem e motivo', async () => {
    stubBackend();
    renderPage();
    await waitForList();

    expect(screen.getByRole('heading', { name: 'Auditoria' })).toBeInTheDocument();

    const sale = rowOf('SALE_COMPLETED');
    expect(
      within(sale).getByRole('cell', { name: expectedDate(SALE_COMPLETED.occurredAt) }),
    ).toBeInTheDocument();
    expect(within(sale).getByRole('cell', { name: 'bruno' })).toBeInTheDocument();
    // Entidade é o par tipo + id curto: é por ele que a investigação chega ao registro.
    expect(within(sale).getByRole('cell', { name: 'SALE · 0198f9c4' })).toBeInTheDocument();
    expect(within(sale).getByRole('cell', { name: 'TUI' })).toBeInTheDocument();
    expect(within(sale).getByRole('cell', { name: 'Fechamento do turno' })).toBeInTheDocument();

    // Sem username, o autor é o id curto; sem id de entidade, sobra o tipo; sem motivo, um traço.
    const failed = rowOf('LOGIN_FAILED');
    expect(within(failed).getByRole('cell', { name: '0198f3a2' })).toBeInTheDocument();
    expect(within(failed).getByRole('cell', { name: 'USER' })).toBeInTheDocument();
    expect(within(failed).getByRole('cell', { name: 'Retaguarda' })).toBeInTheDocument();
    expect(within(failed).getByRole('cell', { name: '—' })).toBeInTheDocument();
  });

  it('põe os filtros combinados na query do servidor', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    expect(lastQuery(fetchStub).get('size')).toBe('20');
    expect(lastQuery(fetchStub).get('page')).toBe('0');
    expect(lastQuery(fetchStub).get('sort')).toBe('occurredat,desc');
    // Nada preenchido: nenhum filtro vazio vira parâmetro.
    expect(lastQuery(fetchStub).get('entityType')).toBeNull();
    expect(lastQuery(fetchStub).get('action')).toBeNull();
    expect(lastQuery(fetchStub).get('entityId')).toBeNull();
    expect(lastQuery(fetchStub).get('actorUserId')).toBeNull();
    expect(lastQuery(fetchStub).get('cashSessionId')).toBeNull();
    expect(lastQuery(fetchStub).get('from')).toBeNull();
    expect(lastQuery(fetchStub).get('to')).toBeNull();

    fireEvent.change(screen.getByLabelText('Tipo da entidade'), { target: { value: 'SALE' } });
    await waitFor(() => expect(lastQuery(fetchStub).get('entityType')).toBe('SALE'));

    fireEvent.change(screen.getByLabelText('Ação'), { target: { value: 'SALE_COMPLETED' } });
    await waitFor(() => expect(lastQuery(fetchStub).get('action')).toBe('SALE_COMPLETED'));

    fireEvent.change(screen.getByLabelText('ID da entidade'), { target: { value: ENTITY_ID } });
    await waitFor(() => expect(lastQuery(fetchStub).get('entityId')).toBe(ENTITY_ID));

    fireEvent.change(screen.getByLabelText('ID do autor'), { target: { value: BRUNO_ID } });
    await waitFor(() => expect(lastQuery(fetchStub).get('actorUserId')).toBe(BRUNO_ID));

    fireEvent.change(screen.getByLabelText('ID da sessão de caixa'), {
      target: { value: CASH_SESSION_ID },
    });
    await waitFor(() => expect(lastQuery(fetchStub).get('cashSessionId')).toBe(CASH_SESSION_ID));

    // O datetime-local (hora local) vira ISO com offset — é o que o servidor aceita.
    const from = '2026-09-28T08:00';
    fireEvent.change(screen.getByLabelText('De'), { target: { value: from } });
    await waitFor(() =>
      expect(lastQuery(fetchStub).get('from')).toBe(new Date(from).toISOString()),
    );

    const to = '2026-09-28T18:00';
    fireEvent.change(screen.getByLabelText('Até (não inclui)'), { target: { value: to } });
    await waitFor(() => expect(lastQuery(fetchStub).get('to')).toBe(new Date(to).toISOString()));

    // Tudo combinado: os sete filtros convivem na mesma leitura.
    const query = lastQuery(fetchStub);
    expect(query.get('entityType')).toBe('SALE');
    expect(query.get('action')).toBe('SALE_COMPLETED');
    expect(query.get('entityId')).toBe(ENTITY_ID);
    expect(query.get('actorUserId')).toBe(BRUNO_ID);
    expect(query.get('cashSessionId')).toBe(CASH_SESSION_ID);
    expect(query.get('from')).toBe(new Date(from).toISOString());
    expect(query.get('to')).toBe(new Date(to).toISOString());
  });

  it('mostra o que o filtro trouxe do servidor', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.change(screen.getByLabelText('Tipo da entidade'), { target: { value: 'USER' } });
    await waitFor(() => expect(lastQuery(fetchStub).get('entityType')).toBe('USER'));

    await waitFor(() =>
      expect(screen.queryByRole('cell', { name: 'SALE_COMPLETED' })).toBeNull(),
    );
    expect(screen.getByRole('cell', { name: 'LOGIN_FAILED' })).toBeInTheDocument();
  });

  it('avisa UUID inválido e mantém o filtro fora da query até o valor fechar', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.change(screen.getByLabelText('ID do autor'), { target: { value: 'não-é-uuid' } });
    expect(await screen.findByText('Informe um UUID válido.')).toBeInTheDocument();
    // O valor incompleto não vai à query: o servidor responderia 400.
    expect(lastQuery(fetchStub).get('actorUserId')).toBeNull();

    fireEvent.change(screen.getByLabelText('ID do autor'), { target: { value: ANA_ID } });
    await waitFor(() => expect(lastQuery(fetchStub).get('actorUserId')).toBe(ANA_ID));
    expect(screen.queryByText('Informe um UUID válido.')).toBeNull();
  });

  it('ordena só pelo instante: default desc e alterna para asc', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    expect(lastQuery(fetchStub).get('sort')).toBe('occurredat,desc');
    expect(screen.getByRole('columnheader', { name: /Instante/ })).toHaveAttribute(
      'aria-sort',
      'descending',
    );
    // Ordenação é whitelist do recurso: só o instante é botão de ordenar.
    expect(screen.getAllByRole('button', { name: /^Ordenar por/ })).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Instante' }));
    await waitFor(() => expect(lastQuery(fetchStub).get('sort')).toBe('occurredat,asc'));
    expect(screen.getByRole('columnheader', { name: /Instante/ })).toHaveAttribute(
      'aria-sort',
      'ascending',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Instante' }));
    await waitFor(() => expect(lastQuery(fetchStub).get('sort')).toBe('occurredat,desc'));
  });

  it('pede a próxima página ao servidor e volta à primeira quando o filtro muda', async () => {
    // 25 eventos com página de 20: a segunda página existe e o botão leva até ela.
    const many = Array.from({ length: 25 }, (_, index) => ({
      id: index + 1,
      occurredAt: new Date(Date.UTC(2026, 8, 28, 12, index)).toISOString(),
      action: `EVENT_${index + 1}`,
      entityType: 'PRODUCT',
      source: 'API',
    }));
    const fetchStub = stubBackend({ events: many });
    renderPage();
    await screen.findByRole('cell', { name: 'EVENT_25' });

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));

    await waitFor(() => expect(lastQuery(fetchStub).get('page')).toBe('1'));
    expect(screen.getByText('Página 2 de 2 · 25 itens')).toBeInTheDocument();

    // Filtro novo recomeça da página 1 (0 no contrato), como o servidor espera.
    fireEvent.change(screen.getByLabelText('Tipo da entidade'), { target: { value: 'PRODUCT' } });
    await waitFor(() => expect(lastQuery(fetchStub).get('entityType')).toBe('PRODUCT'));
    expect(lastQuery(fetchStub).get('page')).toBe('0');
  });

  it('limpa os filtros de volta ao estado inicial', async () => {
    const fetchStub = stubBackend();
    renderPage();
    await waitForList();

    fireEvent.change(screen.getByLabelText('Tipo da entidade'), { target: { value: 'SALE' } });
    fireEvent.change(screen.getByLabelText('Ação'), { target: { value: 'SALE_COMPLETED' } });
    fireEvent.change(screen.getByLabelText('ID da entidade'), { target: { value: ENTITY_ID } });
    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-09-28T08:00' } });
    await waitFor(() => expect(lastQuery(fetchStub).get('entityType')).toBe('SALE'));

    fireEvent.click(screen.getByRole('button', { name: 'Limpar filtros' }));

    await waitFor(() => expect(lastQuery(fetchStub).get('entityType')).toBeNull());
    const query = lastQuery(fetchStub);
    expect(query.get('action')).toBeNull();
    expect(query.get('entityId')).toBeNull();
    expect(query.get('from')).toBeNull();
    expect(query.get('page')).toBe('0');
    // A ordenação não é filtro: continua no default desc.
    expect(query.get('sort')).toBe('occurredat,desc');

    expect(screen.getByLabelText('Tipo da entidade')).toHaveValue('');
    expect(screen.getByLabelText('Ação')).toHaveValue('');
    expect(screen.getByLabelText('ID da entidade')).toHaveValue('');
    expect(screen.getByLabelText('De')).toHaveValue('');
  });

  it('mostra o vazio quando não há evento para os filtros', async () => {
    stubBackend({ events: [] });
    renderPage();

    expect(
      await screen.findByText('Nenhum evento encontrado para os filtros.'),
    ).toBeInTheDocument();
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    stubBackend({ listStatus: 403 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('AuditPage — detalhes e histórico (1211b)', () => {
  it('abre os detalhes do evento com o antes/depois e fecha com Esc', async () => {
    stubBackend();
    renderPage();
    await waitForList();

    const trigger = screen.getByRole('button', { name: 'Detalhes de SALE_COMPLETED' });
    // Como no navegador: o clique parte de um gatilho já focado — é para ele que o foco volta.
    trigger.focus();
    fireEvent.click(trigger);

    const dialog = await screen.findByRole('dialog', { name: 'Detalhes do evento' });
    expect(within(dialog).getByRole('button', { name: 'Fechar' })).toHaveFocus();
    expect(
      within(dialog).getByText(`SALE_COMPLETED · ${expectedDate(SALE_COMPLETED.occurredAt)}`),
    ).toBeInTheDocument();

    // O par `before`/`after` vira duas colunas; o resto do mapa continua visível.
    const before = within(dialog).getByRole('region', { name: 'Antes' });
    const after = within(dialog).getByRole('region', { name: 'Depois' });
    expect(within(before).getByText('120')).toBeInTheDocument();
    expect(within(after).getByText('140')).toBeInTheDocument();
    expect(within(dialog).getByText('CASH')).toBeInTheDocument();
    expect(within(dialog).getByText('PIX')).toBeInTheDocument();

    // Teclado: Esc fecha o diálogo e o foco volta para o gatilho.
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('evento sem detalhes mostra o estado vazio e não oferece histórico sem entidade', async () => {
    stubBackend();
    renderPage();
    await waitForList();

    fireEvent.click(screen.getByRole('button', { name: 'Detalhes de LOGIN_FAILED' }));

    const dialog = await screen.findByRole('dialog', { name: 'Detalhes do evento' });
    expect(within(dialog).getByText('Sem detalhes')).toBeInTheDocument();
    // LOGIN_FAILED tem autor, mas não tem `entityId`: não há par para fixar nos filtros.
    expect(within(dialog).queryByRole('button', { name: 'Ver histórico' })).toBeNull();
  });

  it('ver histórico fixa o par entidade nos filtros e volta à primeira página', async () => {
    // 20 eventos de produto e 1 de venda: a venda, mais antiga, fica sozinha na página 2.
    const products = Array.from({ length: 20 }, (_, index) => ({
      id: index + 1,
      occurredAt: new Date(Date.UTC(2026, 8, 28, 12, index + 1)).toISOString(),
      action: `PRODUCT_EVENT_${index + 1}`,
      entityType: 'PRODUCT',
      entityId: PRODUCT_ID,
      source: 'WEB',
    }));
    const sale = { ...SALE_COMPLETED, id: 99, occurredAt: '2026-09-27T10:00:00Z' };
    const fetchStub = stubBackend({ events: [...products, sale] });
    renderPage();
    await screen.findByRole('cell', { name: 'PRODUCT_EVENT_20' });

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));
    await waitFor(() => expect(lastQuery(fetchStub).get('page')).toBe('1'));

    fireEvent.click(screen.getByRole('button', { name: 'Detalhes de SALE_COMPLETED' }));
    const dialog = await screen.findByRole('dialog', { name: 'Detalhes do evento' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ver histórico' }));

    await waitFor(() => expect(lastQuery(fetchStub).get('entityType')).toBe('SALE'));
    const query = lastQuery(fetchStub);
    expect(query.get('entityId')).toBe(ENTITY_ID);
    expect(query.get('page')).toBe('0');
    expect(screen.getByLabelText('Tipo da entidade')).toHaveValue('SALE');
    expect(screen.getByLabelText('ID da entidade')).toHaveValue(ENTITY_ID);

    // O modal fecha e a consulta passa a mostrar só a linha do tempo daquela entidade.
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(await screen.findByText('Página 1 de 1 · 1 item')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'SALE_COMPLETED' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: 'PRODUCT_EVENT_1' })).toBeNull();
  });
});
