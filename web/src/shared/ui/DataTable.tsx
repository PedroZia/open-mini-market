import type { ReactNode } from 'react';
import type { Page, Sort } from '../lib/page';
import { errorMessage } from '../lib/problem';

/**
 * Tabela única da retaguarda (§10.3): paginação de servidor, ordenação pela whitelist do recurso
 * e os três estados sempre visíveis (vazio, carregando, erro) — nunca uma caixa em branco.
 *
 * A feature dona da query controla tudo: passa `page`, `sort` e os dados já normalizados
 * (`normalizePage`) e recebe de volta `onPageChange`/`onSortChange`. Trocar de página ou de ordem
 * é com ela (inclusive voltar à página 1); aqui só se desenha.
 */

/** Coluna do `DataTable`. */
export interface DataTableColumn<T> {
  /** Identificador estável da coluna (key do React). */
  id: string;
  /** Rótulo do cabeçalho e do anúncio de ordenação. */
  header: string;
  /**
   * Chave da whitelist de `sort` do recurso (`name`, `price`, `createdat`...). Declare **só** o que
   * o backend aceita: sem `sortKey` a coluna não vira botão, então a UI nunca pede um `sort` que
   * devolveria 400.
   */
  sortKey?: string;
  render: (row: T) => ReactNode;
}

export interface DataTableProps<T> {
  /** Nome acessível da tabela (a página já traz o título). */
  label: string;
  columns: readonly DataTableColumn<T>[];
  rowKey: (row: T) => string;
  /** Página pedida (0-based), controlada pela feature dona da query. */
  page: number;
  /** Página do servidor já normalizada (`normalizePage`); `null` antes da primeira resposta. */
  data: Page<T> | null;
  loading?: boolean;
  /** Erro da query como veio do client: o texto sai do mapeador de `problem+json`. */
  error?: unknown;
  /** Ordenação corrente; a feature é quem a guarda (estado/URL) e quem volta à página 1. */
  sort?: Sort | null;
  /** Presente, liga a ordenação das colunas com `sortKey`. */
  onSortChange?: (sort: Sort) => void;
  onPageChange: (page: number) => void;
  /** Refaz a busca; o botão de nova tentativa só aparece no estado de erro. */
  onRetry?: () => void;
  emptyMessage?: string;
}

const paginationButtonClassName =
  'min-h-9 rounded-md border border-line bg-surface px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50 motion-reduce:transition-none';

const retryButtonClassName =
  'min-h-9 rounded-md border border-danger px-3 text-sm font-medium text-danger transition-colors duration-150 ease-out hover:bg-danger/5 motion-reduce:transition-none';

export function DataTable<T>({
  label,
  columns,
  rowKey,
  page,
  data,
  loading = false,
  error,
  sort = null,
  onSortChange,
  onPageChange,
  onRetry,
  emptyMessage = 'Nenhum registro encontrado.',
}: DataTableProps<T>) {
  const rows = data?.items ?? [];
  const hasRows = rows.length > 0;
  const failed = error !== undefined && error !== null;
  const currentPage = data?.page ?? page;
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const totalItems = data?.totalItems ?? rows.length;

  // Com linhas na tela o aviso vira faixa; sem linhas, o próprio corpo mostra o estado.
  const banner =
    hasRows && failed ? (
      <p role="alert" className="flex flex-wrap items-center gap-3 text-sm font-medium text-danger">
        {errorMessage(error)}
        {onRetry ? (
          <button type="button" onClick={onRetry} className={retryButtonClassName}>
            Tentar de novo
          </button>
        ) : null}
      </p>
    ) : hasRows && loading ? (
      <p role="status" className="text-sm text-ink-muted">
        Atualizando…
      </p>
    ) : null;

  let bodyState: ReactNode = null;
  if (!hasRows) {
    if (failed) {
      bodyState = (
        <div role="alert" className="flex flex-col items-center gap-3 text-sm">
          <p className="font-medium text-danger">{errorMessage(error)}</p>
          {onRetry ? (
            <button type="button" onClick={onRetry} className={retryButtonClassName}>
              Tentar de novo
            </button>
          ) : null}
        </div>
      );
    } else if (loading) {
      bodyState = (
        <p role="status" className="text-sm text-ink-muted">
          Carregando…
        </p>
      );
    } else {
      bodyState = <p className="text-sm text-ink-muted">{emptyMessage}</p>;
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {banner}

      <div className="overflow-x-auto rounded-lg border border-line bg-surface">
        <table aria-label={label} aria-busy={loading || undefined} className="w-full text-left text-sm">
          <thead className="border-b border-line bg-canvas">
            <tr>
              {columns.map((column) => {
                const sortKey = column.sortKey;
                const activeSort = sortKey !== undefined && sort?.field === sortKey ? sort : null;
                const ariaSort =
                  sortKey === undefined
                    ? undefined
                    : activeSort === null
                      ? 'none'
                      : activeSort.direction === 'asc'
                        ? 'ascending'
                        : 'descending';

                return (
                  <th
                    key={column.id}
                    scope="col"
                    aria-sort={ariaSort}
                    className="font-medium text-ink-muted"
                  >
                    {sortKey !== undefined && onSortChange ? (
                      <button
                        type="button"
                        aria-label={`Ordenar por ${column.header}`}
                        onClick={() =>
                          onSortChange(
                            activeSort?.direction === 'asc'
                              ? { field: sortKey, direction: 'desc' }
                              : { field: sortKey, direction: 'asc' },
                          )
                        }
                        className="flex min-h-11 w-full items-center gap-1 px-3 text-left font-medium text-ink-muted transition-colors duration-150 ease-out hover:bg-surface motion-reduce:transition-none"
                      >
                        {column.header}
                        <span aria-hidden="true">
                          {activeSort === null
                            ? '↕'
                            : activeSort.direction === 'asc'
                              ? '↑'
                              : '↓'}
                        </span>
                      </button>
                    ) : (
                      <span className="flex min-h-11 items-center px-3">{column.header}</span>
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>

          <tbody className="divide-y divide-line">
            {rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((column) => (
                  <td key={column.id} className="px-3 py-2 align-top text-ink">
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}

            {bodyState !== null ? (
              <tr>
                <td colSpan={columns.length} className="px-3 py-6 text-center">
                  {bodyState}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>

      {data !== null ? (
        <nav
          aria-label={`Paginação de ${label}`}
          className="flex flex-wrap items-center justify-between gap-3"
        >
          <p aria-live="polite" className="text-sm text-ink-muted">
            Página {currentPage + 1} de {totalPages} · {totalItems}{' '}
            {totalItems === 1 ? 'item' : 'itens'}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              aria-label="Página anterior"
              disabled={loading || currentPage <= 0}
              onClick={() => onPageChange(currentPage - 1)}
              className={paginationButtonClassName}
            >
              Anterior
            </button>
            <button
              type="button"
              aria-label="Próxima página"
              disabled={loading || currentPage >= totalPages - 1}
              onClick={() => onPageChange(currentPage + 1)}
              className={paginationButtonClassName}
            >
              Próxima
            </button>
          </div>
        </nav>
      ) : null}
    </div>
  );
}
