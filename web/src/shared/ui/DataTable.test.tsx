import { ApiError } from '@minimarket/api-client';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Page, Sort } from '../lib/page';
import { DataTable, type DataTableColumn } from './DataTable';

interface Row {
  id: string;
  name: string;
  price: number;
}

const rows: Row[] = [
  { id: 'p1', name: 'Arroz', price: 10 },
  { id: 'p2', name: 'Feijão', price: 20 },
];

/** "Nome" declara a chave da whitelist; "Preço" não — é coluna que não ordena. */
const columns: DataTableColumn<Row>[] = [
  { id: 'name', header: 'Nome', sortKey: 'name', render: (row) => row.name },
  { id: 'price', header: 'Preço', render: (row) => `R$ ${row.price}` },
];

function pageOf(overrides: Partial<Page<Row>> = {}): Page<Row> {
  return { items: rows, page: 0, size: 20, totalItems: rows.length, totalPages: 1, ...overrides };
}

function renderTable(
  overrides: {
    page?: number;
    data?: Page<Row> | null;
    loading?: boolean;
    error?: unknown;
    sort?: Sort | null;
    onSortChange?: (sort: Sort) => void;
    onPageChange?: (page: number) => void;
    onRetry?: () => void;
    emptyMessage?: string;
  } = {},
) {
  return render(
    <DataTable
      label="Produtos"
      columns={columns}
      rowKey={(row) => row.id}
      page={overrides.page ?? 0}
      data={overrides.data === undefined ? pageOf() : overrides.data}
      loading={overrides.loading}
      error={overrides.error}
      sort={overrides.sort}
      onSortChange={overrides.onSortChange}
      onPageChange={overrides.onPageChange ?? (() => {})}
      onRetry={overrides.onRetry}
      emptyMessage={overrides.emptyMessage}
    />,
  );
}

describe('DataTable — dados', () => {
  it('renderiza as linhas da página com o nome acessível da tabela', () => {
    renderTable();

    const table = screen.getByRole('table', { name: 'Produtos' });
    expect(within(table).getByRole('cell', { name: 'Arroz' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: 'R$ 20' })).toBeInTheDocument();
  });

  it('mostra o estado vazio e o total da página', () => {
    renderTable({ data: pageOf({ items: [], totalItems: 0, totalPages: 0 }) });

    expect(screen.getByText('Nenhum registro encontrado.')).toBeInTheDocument();
    expect(screen.getByText('Página 1 de 1 · 0 itens')).toBeInTheDocument();
  });

  it('deixa a feature trocar a mensagem do vazio', () => {
    renderTable({ data: pageOf({ items: [], totalItems: 0 }), emptyMessage: 'Nenhum produto cadastrado.' });

    expect(screen.getByText('Nenhum produto cadastrado.')).toBeInTheDocument();
  });
});

describe('DataTable — paginação de servidor', () => {
  it('mostra página/total, pede a próxima e volta para a anterior', () => {
    const onPageChange = vi.fn();
    renderTable({
      page: 1,
      data: pageOf({ page: 1, totalItems: 42, totalPages: 3 }),
      onPageChange,
    });

    expect(screen.getByText('Página 2 de 3 · 42 itens')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Próxima página' }));
    expect(onPageChange).toHaveBeenCalledWith(2);

    fireEvent.click(screen.getByRole('button', { name: 'Página anterior' }));
    expect(onPageChange).toHaveBeenCalledWith(0);
  });

  it('desabilita a página anterior na primeira página', () => {
    renderTable({ page: 0, data: pageOf({ totalPages: 3 }) });

    expect(screen.getByRole('button', { name: 'Página anterior' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Próxima página' })).toBeEnabled();
  });

  it('desabilita a próxima página na última', () => {
    renderTable({ page: 2, data: pageOf({ page: 2, totalPages: 3 }) });

    expect(screen.getByRole('button', { name: 'Próxima página' })).toBeDisabled();
  });

  it('não deixa paginar enquanto a página carrega', () => {
    renderTable({ page: 1, data: pageOf({ page: 1, totalPages: 3 }), loading: true });

    expect(screen.getByRole('button', { name: 'Próxima página' })).toBeDisabled();
  });
});

describe('DataTable — ordenação', () => {
  it('pede a ordenação pela chave que a coluna declara', () => {
    const onSortChange = vi.fn();
    renderTable({ onSortChange, sort: null });

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Nome' }));

    expect(onSortChange).toHaveBeenCalledWith({ field: 'name', direction: 'asc' });
  });

  it('não oferece ordenação para coluna fora da whitelist', () => {
    renderTable({ onSortChange: () => {}, sort: null });

    expect(screen.queryByRole('button', { name: 'Ordenar por Preço' })).toBeNull();
  });

  it('inverte a direção da coluna já ordenada e anuncia no aria-sort', () => {
    const onSortChange = vi.fn();
    renderTable({ onSortChange, sort: { field: 'name', direction: 'asc' } });

    expect(screen.getByRole('columnheader', { name: 'Nome' })).toHaveAttribute(
      'aria-sort',
      'ascending',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Ordenar por Nome' }));
    expect(onSortChange).toHaveBeenCalledWith({ field: 'name', direction: 'desc' });
  });
});

describe('DataTable — loading e erro', () => {
  it('mostra o carregando quando ainda não há dados', () => {
    renderTable({ data: null, loading: true });

    expect(screen.getByRole('status')).toHaveTextContent('Carregando…');
  });

  it('mantém as linhas e avisa que está atualizando', () => {
    renderTable({ data: pageOf(), loading: true });

    expect(screen.getByRole('status')).toHaveTextContent('Atualizando…');
    expect(screen.getByRole('cell', { name: 'Arroz' })).toBeInTheDocument();
  });

  it('mostra o erro com mensagem clara, sem detalhe técnico, e oferece nova tentativa', () => {
    const onRetry = vi.fn();
    renderTable({
      data: null,
      error: new ApiError(500, { code: 'INTERNAL_ERROR', detail: 'java.lang.IllegalStateException: stack' }),
      onRetry,
    });

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('O servidor falhou ao responder. Tente de novo em instantes.');
    expect(alert).not.toHaveTextContent('IllegalStateException');

    fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('mantém as linhas e a faixa de erro quando a atualização falha', () => {
    renderTable({ data: pageOf(), error: new ApiError(500, { code: 'INTERNAL_ERROR' }) });

    expect(screen.getByRole('alert')).toHaveTextContent('O servidor falhou ao responder.');
    expect(screen.getByRole('cell', { name: 'Arroz' })).toBeInTheDocument();
  });
});
