import { useState } from 'react';
import { Link } from 'react-router';
import { normalizePage } from '../../../shared/lib/page';
import { isForbidden } from '../../../shared/lib/problem';
import { formatQuantity } from '../../../shared/lib/quantity';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { StockItemResponse } from '../api/stockApi';
import { LowStockBadge } from '../components/LowStockBadge';
import { useStockList } from '../hooks/useStock';

/**
 * Lista de saldos de estoque (1206a): busca, filtro de estoque baixo e paginação do servidor. Saldo
 * e mínimo são exibidos como o servidor os devolve (BR-12) e o selo "Estoque baixo" é o `lowStock`
 * derivado lá — produto sem mínimo configurado nunca é baixo.
 *
 * O recurso não tem ordenação (§9.3): nenhuma coluna declara `sortKey` e o `DataTable` não recebe
 * `onSortChange`, então a tela nunca pede um `sort` que viraria 400. A linha leva ao detalhe pelo
 * nome do produto — um link de verdade, alcançável pelo teclado.
 */

/** Tamanho de página da lista, o mesmo default do servidor (§9.1). */
const PAGE_SIZE = 20;

/** Filtro como a tela oferece; `low` é o único que vira `lowStock=true` na query. */
type LowStockFilter = 'all' | 'low';

// O foco visível é o global de `index.css` (azul, 2px): aqui não se sobrescreve anel nenhum.
const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none';

/** Valor do `<select>` de estoque; fora da união o filtro não muda. */
function isLowStockFilter(value: string): value is LowStockFilter {
  return value === 'all' || value === 'low';
}

export function StockListPage() {
  const [search, setSearch] = useState('');
  const [lowStock, setLowStock] = useState<LowStockFilter>('all');
  const [page, setPage] = useState(0);

  const stock = useStockList({
    search,
    lowStock: lowStock === 'low' ? true : undefined,
    page,
    size: PAGE_SIZE,
  });

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(stock.error)) {
    return (
      <section aria-labelledby="titulo-estoque" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-estoque" className="text-2xl font-semibold">
          Estoque
        </h1>
        <NoPermission
          onRetry={() => {
            void stock.refetch();
          }}
        />
      </section>
    );
  }

  const columns: DataTableColumn<StockItemResponse>[] = [
    {
      id: 'name',
      header: 'Nome',
      render: (item) => {
        const name = item.name ?? '—';
        return item.productId === undefined ? (
          name
        ) : (
          <Link
            to={`/stock/${item.productId}`}
            className="font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none"
          >
            {name}
          </Link>
        );
      },
    },
    { id: 'barcode', header: 'Código de barras', render: (item) => item.barcode ?? '—' },
    { id: 'unit', header: 'Unidade', render: (item) => item.unit ?? '—' },
    {
      id: 'quantity',
      header: 'Saldo',
      render: (item) => (
        <span className="flex flex-wrap items-center gap-2">
          {formatQuantity(item.quantity ?? 0)}
          {item.lowStock === true ? <LowStockBadge /> : null}
        </span>
      ),
    },
    {
      id: 'minQuantity',
      header: 'Mínimo',
      render: (item) => {
        // O servidor manda `null` quando o produto não tem mínimo; o contrato só promete `number`.
        const minQuantity: number | null | undefined = item.minQuantity;
        return minQuantity === undefined || minQuantity === null
          ? '—'
          : formatQuantity(minQuantity);
      },
    },
  ];

  return (
    <section aria-labelledby="titulo-estoque" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-estoque" className="text-2xl font-semibold">
          Estoque
        </h1>
        <p className="text-sm text-ink-muted">
          Acompanhe o saldo dos produtos e o que está no mínimo.
        </p>
      </header>

      <div className="grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-busca" className="text-sm font-medium text-ink">
            Buscar
          </label>
          <input
            id="filtro-busca"
            type="search"
            value={search}
            placeholder="Nome do produto ou código de barras"
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-estoque" className="text-sm font-medium text-ink">
            Filtro de estoque
          </label>
          <select
            id="filtro-estoque"
            value={lowStock}
            onChange={(event) => {
              const value = event.target.value;
              if (isLowStockFilter(value)) {
                setLowStock(value);
                setPage(0);
              }
            }}
            className={fieldClassName}
          >
            <option value="all">Todos</option>
            <option value="low">Só estoque baixo</option>
          </select>
        </div>
      </div>

      <DataTable
        label="Estoque"
        columns={columns}
        rowKey={(item) => item.productId ?? ''}
        page={page}
        data={stock.data === undefined ? null : normalizePage(stock.data)}
        loading={stock.isPending || stock.isFetching}
        error={stock.error}
        onPageChange={setPage}
        onRetry={() => {
          void stock.refetch();
        }}
        emptyMessage="Nenhum saldo encontrado."
      />
    </section>
  );
}
