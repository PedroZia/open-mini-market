import { useState } from 'react';
import { normalizePage, sortParam, type Sort } from '../../../shared/lib/page';
import { usePermission } from '../../../shared/lib/permissions';
import { isForbidden } from '../../../shared/lib/problem';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { UserResponse } from '../api/usersApi';
import { PasswordResetModal } from '../components/PasswordResetModal';
import { UserFormModal } from '../components/UserFormModal';
import {
  useDisableUser,
  useEnableUser,
  useRevokeSessions,
  useUsers,
} from '../hooks/useUsers';

/**
 * Administração de acessos (1208a): busca, filtro de situação, ordenação e paginação pelo servidor,
 * com criar/editar, desativar/habilitar, reset de senha e revogação de sessões — cada ação só
 * aparece com a permissão que o servidor exige (`user.write`/`user.session.revoke`).
 *
 * A ordenação só oferece `username` e `displayname`: a whitelist do servidor também aceita
 * `createdat`, mas o `UserResponse` não devolve a data de criação, então não há coluna para
 * ordená-la.
 *
 * O "último ADMIN ativo" é regra do servidor (409 `CONFLICT`); a tela mostra a mensagem e relê a
 * lista, sem tentar prever a regra no cliente.
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

/** Papéis como o servidor os devolve (códigos); sem papel o traço, nunca célula vazia. */
function rolesLabel(roles: string[] | undefined): string {
  return roles === undefined || roles.length === 0 ? '—' : roles.join(', ');
}

export function UsersPage() {
  const [search, setSearch] = useState('');
  const [activeFilter, setActiveFilter] = useState<ActiveFilter>('all');
  const [sort, setSort] = useState<Sort | null>(null);
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<UserResponse | null>(null);
  const [resetting, setResetting] = useState<UserResponse | null>(null);

  const canWrite = usePermission('user.write');
  const canRevoke = usePermission('user.session.revoke');
  const users = useUsers({
    search,
    active: activeFilter === 'all' ? undefined : activeFilter === 'active',
    sort: sortParam(sort) ?? undefined,
    page,
    size: PAGE_SIZE,
  });
  const disable = useDisableUser();
  const enable = useEnableUser();
  const revoke = useRevokeSessions();

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(users.error)) {
    return (
      <section aria-labelledby="titulo-usuarios" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-usuarios" className="text-2xl font-semibold">
          Usuários
        </h1>
        <NoPermission
          onRetry={() => {
            void users.refetch();
          }}
        />
      </section>
    );
  }

  const pending = disable.isPending || enable.isPending || revoke.isPending;

  const columns: DataTableColumn<UserResponse>[] = [
    {
      id: 'username',
      header: 'Usuário',
      sortKey: 'username',
      render: (user) => user.username ?? '—',
    },
    {
      id: 'displayName',
      header: 'Nome',
      sortKey: 'displayname',
      render: (user) => user.displayName ?? '—',
    },
    { id: 'roles', header: 'Papéis', render: (user) => rolesLabel(user.roles) },
    {
      id: 'status',
      header: 'Situação',
      render: (user) => (user.status === 'ACTIVE' ? 'Ativo' : 'Desativado'),
    },
    {
      id: 'mustChangePassword',
      header: 'Senha a trocar',
      render: (user) => (user.mustChangePassword === true ? 'Sim' : '—'),
    },
  ];

  // Sem `user.write` nem `user.session.revoke` a coluna de ações nem existe: a permissão é do papel,
  // não do clique. Usuário desativado só aceita reabilitar — reset e revogação não fazem sentido
  // para quem não consegue entrar.
  if (canWrite || canRevoke) {
    columns.push({
      id: 'actions',
      header: 'Ações',
      render: (user) => {
        if (user.id === undefined) {
          return null;
        }
        const id = user.id;
        const username = user.username ?? 'usuário';

        if (user.status !== 'ACTIVE') {
          return canWrite ? (
            <button
              type="button"
              aria-label={`Habilitar ${username}`}
              disabled={pending}
              onClick={() => enable.mutate(id)}
              className={actionButtonClassName}
            >
              Habilitar
            </button>
          ) : null;
        }

        return (
          <div className="flex flex-wrap gap-2">
            {canWrite ? (
              <button
                type="button"
                aria-label={`Editar ${username}`}
                disabled={pending}
                onClick={() => setEditing(user)}
                className={actionButtonClassName}
              >
                Editar
              </button>
            ) : null}
            {canWrite ? (
              <button
                type="button"
                aria-label={`Resetar senha de ${username}`}
                disabled={pending}
                onClick={() => setResetting(user)}
                className={actionButtonClassName}
              >
                Resetar senha
              </button>
            ) : null}
            {canRevoke ? (
              <button
                type="button"
                aria-label={`Revogar sessões de ${username}`}
                disabled={pending}
                onClick={() => revoke.mutate(id)}
                className={actionButtonClassName}
              >
                Revogar sessões
              </button>
            ) : null}
            {canWrite ? (
              <button
                type="button"
                aria-label={`Desativar ${username}`}
                disabled={pending}
                onClick={() => disable.mutate(id)}
                className={actionButtonClassName}
              >
                Desativar
              </button>
            ) : null}
          </div>
        );
      },
    });
  }

  return (
    <section aria-labelledby="titulo-usuarios" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 id="titulo-usuarios" className="text-2xl font-semibold">
            Usuários
          </h1>
          <p className="text-sm text-ink-muted">
            Administre quem acessa a retaguarda e com quais papéis.
          </p>
        </div>

        {canWrite ? (
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 motion-reduce:transition-none"
          >
            Novo usuário
          </button>
        ) : null}
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
            placeholder="Usuário ou nome"
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
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
        label="Usuários"
        columns={columns}
        rowKey={(user) => user.id ?? ''}
        page={page}
        data={users.data === undefined ? null : normalizePage(users.data)}
        loading={users.isPending || users.isFetching}
        error={users.error}
        sort={sort}
        onSortChange={(next) => {
          setSort(next);
          setPage(0);
        }}
        onPageChange={setPage}
        onRetry={() => {
          void users.refetch();
        }}
        emptyMessage="Nenhum usuário encontrado."
      />

      {/* Os modais vivem só enquanto abertos: cada abertura parte do usuário clicado. */}
      {creating ? <UserFormModal onClose={() => setCreating(false)} /> : null}
      {editing !== null ? (
        <UserFormModal user={editing} onClose={() => setEditing(null)} />
      ) : null}
      {resetting !== null && resetting.id !== undefined ? (
        <PasswordResetModal
          userId={resetting.id}
          username={resetting.username ?? 'usuário'}
          onClose={() => setResetting(null)}
        />
      ) : null}
    </section>
  );
}
