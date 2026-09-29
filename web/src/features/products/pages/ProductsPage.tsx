import { useState } from 'react';
import { formatMoney } from '../../../shared/lib/money';
import { normalizePage, sortParam, type Sort } from '../../../shared/lib/page';
import { usePermission } from '../../../shared/lib/permissions';
import { isForbidden } from '../../../shared/lib/problem';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import { useCategories } from '../../categories/hooks/useCategories';
import type { ProductResponse } from '../api/productsApi';
import { useDisableProduct, useEnableProduct, useProducts } from '../hooks/useProducts';

/**
 * Lista de produtos (1204a): busca, filtros de categoria/situação, paginação e ordenação pelo
 * servidor, com desativar/reativar. Cadastro, edição e preço são o 1204b — esta tela só consulta e
 * muda a situação.
 *
 * A página é dona do estado da consulta (filtros, página e ordem) e o `DataTable` só desenha; o
 * servidor continua recalculando tudo (§4.4) — aqui nenhum valor é derivado de preço.
 */

/** Tamanho de página da lista, o mesmo default do servidor (§9.1). */
const PAGE_SIZE = 20;

/** Situação como a tela oferece; vira `active` (boolean) ou ausência do parâmetro na query. */
type ActiveFilter = 'all' | 'active' | 'inactive';

// O foco visível é o global de `index.css` (azul, 2px): aqui não se sobrescreve anel nenhum.
const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none';

const actionButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50 motion-reduce:transition-none';

/** Valor do `<select>` de situação; fora da união o filtro não muda. */
function isActiveFilter(value: string): value is ActiveFilter {
  return value === 'all' || value === 'active' || value === 'inactive';
}

/** Nome da categoria para a coluna: o id do produto resolve no catálogo já carregado. */
function categoryName(names: ReadonlyMap<string, string>, categoryId: string | undefined): string {
  return categoryId === undefined ? '—' : (names.get(categoryId) ?? '—');
}

export function ProductsPage() {
  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [activeFilter, setActiveFilter] = useState<ActiveFilter>('all');
  const [sort, setSort] = useState<Sort | null>(null);
  const [page, setPage] = useState(0);

  const canWrite = usePermission('product.write');
  const categories = useCategories();
  const products = useProducts({
    search,
    categoryId: categoryId === '' ? undefined : categoryId,
    active: activeFilter === 'all' ? undefined : activeFilter === 'active',
    sort: sortParam(sort) ?? undefined,
    page,
    size: PAGE_SIZE,
  });
  const disable = useDisableProduct();
  const enable = useEnableProduct();

  const categoryNames = new Map<string, string>();
  for (const category of categories.data ?? []) {
    if (category.id !== undefined) {
      categoryNames.set(category.id, category.name ?? category.id);
    }
  }

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(products.error)) {
    return (
      <section aria-labelledby="titulo-produtos" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-produtos" className="text-2xl font-semibold">
          Produtos
        </h1>
        <NoPermission
          onRetry={() => {
            void products.refetch();
          }}
        />
      </section>
    );
  }

  const pending = disable.isPending || enable.isPending;

  const columns: DataTableColumn<ProductResponse>[] = [
    { id: 'name', header: 'Nome', sortKey: 'name', render: (product) => product.name ?? '—' },
    { id: 'barcode', header: 'Código de barras', render: (product) => product.barcode ?? '—' },
    {
      id: 'category',
      header: 'Categoria',
      render: (product) => categoryName(categoryNames, product.categoryId),
    },
    { id: 'unit', header: 'Unidade', render: (product) => product.unit ?? '—' },
    {
      id: 'price',
      header: 'Preço',
      sortKey: 'price',
      render: (product) => formatMoney(product.price ?? 0),
    },
    {
      id: 'status',
      header: 'Situação',
      render: (product) => (product.active === false ? 'Desativado' : 'Ativo'),
    },
  ];

  // Sem `product.write` a coluna de ações nem existe: a permissão é do papel, não do clique.
  if (canWrite) {
    columns.push({
      id: 'actions',
      header: 'Ações',
      render: (product) => {
        if (product.id === undefined) {
          return null;
        }
        const id = product.id;
        return product.active === false ? (
          <button
            type="button"
            aria-label={`Reativar ${product.name ?? 'produto'}`}
            disabled={pending}
            onClick={() => enable.mutate(id)}
            className={actionButtonClassName}
          >
            Reativar
          </button>
        ) : (
          <button
            type="button"
            aria-label={`Desativar ${product.name ?? 'produto'}`}
            disabled={pending}
            onClick={() => disable.mutate(id)}
            className={actionButtonClassName}
          >
            Desativar
          </button>
        );
      },
    });
  }

  return (
    <section aria-labelledby="titulo-produtos" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-produtos" className="text-2xl font-semibold">
          Produtos
        </h1>
        <p className="text-sm text-ink-muted">
          Consulte o catálogo e tire ou volte produtos de linha.
        </p>
      </header>

      <div className="grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-3">
        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-busca" className="text-sm font-medium text-ink">
            Buscar
          </label>
          <input
            id="filtro-busca"
            type="search"
            value={search}
            placeholder="Nome do produto"
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-categoria" className="text-sm font-medium text-ink">
            Categoria
          </label>
          <select
            id="filtro-categoria"
            value={categoryId}
            onChange={(event) => {
              setCategoryId(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          >
            <option value="">Todas</option>
            {categories.data?.map((category) =>
              category.id === undefined ? null : (
                <option key={category.id} value={category.id}>
                  {category.name ?? category.id}
                </option>
              ),
            )}
          </select>
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-situacao" className="text-sm font-medium text-ink">
            Situação
          </label>
          <select
            id="filtro-situacao"
            value={activeFilter}
            onChange={(event) => {
              const value = event.target.value;
              if (isActiveFilter(value)) {
                setActiveFilter(value);
                setPage(0);
              }
            }}
            className={fieldClassName}
          >
            <option value="all">Todos</option>
            <option value="active">Ativos</option>
            <option value="inactive">Desativados</option>
          </select>
        </div>
      </div>

      <DataTable
        label="Produtos"
        columns={columns}
        rowKey={(product) => product.id ?? ''}
        page={page}
        data={products.data === undefined ? null : normalizePage(products.data)}
        loading={products.isPending || products.isFetching}
        error={products.error}
        sort={sort}
        onSortChange={(next) => {
          setSort(next);
          setPage(0);
        }}
        onPageChange={setPage}
        onRetry={() => {
          void products.refetch();
        }}
        emptyMessage="Nenhum produto encontrado."
      />
    </section>
  );
}
