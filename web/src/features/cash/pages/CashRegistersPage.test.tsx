import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { CashRegistersPage } from './CashRegistersPage';

/**
 * Caixas (1210a) com a rede stubada: `GET /cash-registers` é um array **sem** paginação (§9.3) e a
 * tela desenha código, nome, situação e operador — caixa fechado vem sem operador (`null`) e a
 * tela mostra traço, nunca um nome inventado.
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
const BACK_ID = '0198f5a2-4d5e-7f60-9b02-000000000002';

/** Caixas como `GET /cash-registers` devolve: o da frente aberto com a Ana, o depósito fechado. */
const REGISTERS = [
  {
    id: FRONT_ID,
    code: 'C1',
    name: 'Frente de loja',
    status: 'OPEN',
    operatorName: 'Ana Souza',
  },
  { id: BACK_ID, code: 'C2', name: 'Depósito', status: 'CLOSED', operatorName: null },
];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <CashRegistersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('CashRegistersPage', () => {
  it('lista código, nome, situação e operador, com o código levando ao detalhe', async () => {
    const fetchStub: FetchStub = vi.fn(async () => jsonResponse(REGISTERS));
    vi.stubGlobal('fetch', fetchStub);
    renderPage();

    expect(await screen.findByText('Frente de loja')).toBeInTheDocument();

    const table = screen.getByRole('table', { name: 'Caixas' });
    expect(within(table).getByRole('link', { name: 'C1' })).toHaveAttribute(
      'href',
      `/cash-registers/${FRONT_ID}`,
    );
    expect(within(table).getByText('Aberto')).toBeInTheDocument();
    expect(within(table).getByText('Ana Souza')).toBeInTheDocument();
    expect(within(table).getByText('Depósito')).toBeInTheDocument();
    expect(within(table).getByText('Fechado')).toBeInTheDocument();
    // Caixa fechado não tem operador na resposta: traço na célula.
    expect(within(table).getByText('—')).toBeInTheDocument();

    // A rota do contrato é o array puro: a tela não inventa `page`/`size`.
    expect(fetchStub.mock.calls[0]?.[0]).toBe('/api/v1/cash-registers');
  });

  it('mostra o estado sem permissão quando a leitura leva 403', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse({ code: 'ACCESS_DENIED', detail: 'missing authority cash.read' }, 403),
      ),
    );
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});
