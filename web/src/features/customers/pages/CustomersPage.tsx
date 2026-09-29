import { useState } from 'react';
import { normalizePage } from '../../../shared/lib/page';
import { usePermission } from '../../../shared/lib/permissions';
import { isForbidden } from '../../../shared/lib/problem';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { CustomerResponse } from '../api/customersApi';
import { CustomerFormModal } from '../components/CustomerFormModal';
import { useCustomers, useDisableCustomer } from '../hooks/useCustomers';

/**
 * Gestão de clientes (1207): busca no nome/CPF/telefone, paginação do servidor e as ações de
 * cadastrar, editar e desativar conforme `customer.write`. A lista só devolve clientes vivos — o
 * desativado sai da busca —, então não há coluna de situação nem ação de reativar (não existe rota
 * para isso). O recurso não tem ordenação (§9.3): nenhuma coluna declara `sortKey` e o `DataTable`
 * não recebe `onSortChange`, então a tela nunca pede um `sort` que viraria 400.
 *
 * A página é dona do estado da consulta (busca e página) e o `DataTable` só desenha; cada ação de
 * escrita só existe com a permissão que o servidor exige.
 */

/** Tamanho de página da lista, o mesmo default do servidor (§9.1). */
const PAGE_SIZE = 20;

// O foco visível é o global de `index.css` (azul, 2px): aqui não se sobrescreve anel nenhum.
const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none';

const actionButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50 motion-reduce:transition-none';

export function CustomersPage() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<CustomerResponse | null>(null);

  const canWrite = usePermission('customer.write');
  const customers = useCustomers({ search, page, size: PAGE_SIZE });
  const disable = useDisableCustomer();

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(customers.error)) {
    return (
      <section aria-labelledby="titulo-clientes" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-clientes" className="text-2xl font-semibold">
          Clientes
        </h1>
        <NoPermission
          onRetry={() => {
            void customers.refetch();
          }}
        />
      </section>
    );
  }

  const columns: DataTableColumn<CustomerResponse>[] = [
    { id: 'name', header: 'Nome', render: (customer) => customer.name ?? '—' },
    { id: 'taxId', header: 'CPF', render: (customer) => customer.taxId ?? '—' },
    { id: 'phone', header: 'Telefone', render: (customer) => customer.phone ?? '—' },
    { id: 'email', header: 'E-mail', render: (customer) => customer.email ?? '—' },
  ];

  // Sem `customer.write` a coluna de ações nem existe: a permissão é do papel, não do clique.
  if (canWrite) {
    columns.push({
      id: 'actions',
      header: 'Ações',
      render: (customer) => {
        if (customer.id === undefined) {
          return null;
        }
        const id = customer.id;
        const name = customer.name ?? 'cliente';

        return (
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              aria-label={`Editar ${name}`}
              disabled={disable.isPending}
              onClick={() => setEditing(customer)}
              className={actionButtonClassName}
            >
              Editar
            </button>
            <button
              type="button"
              aria-label={`Desativar ${name}`}
              disabled={disable.isPending}
              onClick={() => disable.mutate(id)}
              className={actionButtonClassName}
            >
              Desativar
            </button>
          </div>
        );
      },
    });
  }

  return (
    <section aria-labelledby="titulo-clientes" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 id="titulo-clientes" className="text-2xl font-semibold">
            Clientes
          </h1>
          <p className="text-sm text-ink-muted">
            Cadastre, edite e desative clientes usados no caixa.
          </p>
        </div>

        {canWrite ? (
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 motion-reduce:transition-none"
          >
            Novo cliente
          </button>
        ) : null}
      </header>

      <div className="rounded-lg border border-line bg-surface p-4">
        <div className="flex max-w-sm flex-col gap-1">
          <label htmlFor="filtro-busca" className="text-sm font-medium text-ink">
            Buscar
          </label>
          <input
            id="filtro-busca"
            type="search"
            value={search}
            placeholder="Nome, CPF ou telefone"
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>
      </div>

      <DataTable
        label="Clientes"
        columns={columns}
        rowKey={(customer) => customer.id ?? ''}
        page={page}
        data={customers.data === undefined ? null : normalizePage(customers.data)}
        loading={customers.isPending || customers.isFetching}
        error={customers.error}
        onPageChange={setPage}
        onRetry={() => {
          void customers.refetch();
        }}
        emptyMessage="Nenhum cliente encontrado."
      />

      {/* Os modais vivem só enquanto abertos: cada abertura relê o cliente (fonte da edição). */}
      {creating ? <CustomerFormModal onClose={() => setCreating(false)} /> : null}
      {editing !== null ? (
        <CustomerFormModal customer={editing} onClose={() => setEditing(null)} />
      ) : null}
    </section>
  );
}
