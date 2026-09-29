import { useMemo, useState } from 'react';
import { formatDateTime, localDateTimeToInstant } from '../../../shared/lib/datetime';
import { formatMoney } from '../../../shared/lib/money';
import { normalizePage } from '../../../shared/lib/page';
import { usePermission } from '../../../shared/lib/permissions';
import { isForbidden } from '../../../shared/lib/problem';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type {
  PageResponseUserResponse,
  SaleStatus,
  SaleSummaryResponse,
  UserResponse,
} from '../api/salesApi';
import { SaleStatusBadge } from '../components/SaleStatusBadge';
import { useOperators, useSales } from '../hooks/useSales';

/**
 * Histórico de vendas (1209a): período, situação e operador como filtros do `GET /sales`, com
 * paginação do servidor. O recurso não tem ordenação (§9.3): nenhuma coluna declara `sortKey` e o
 * `DataTable` não recebe `onSortChange`, então a tela nunca pede um `sort` que viraria 400.
 *
 * A leitura exige `report.read` (GERENTE/ADMIN; 403 vira o estado "sem permissão"). O picker de
 * operador lê `GET /users` e só existe com `user.read` — sem a permissão, o filtro não aparece e a
 * coluna mostra o id curto do operador; nome de usuário é dado que a tela não tem e não inventa.
 *
 * O detalhe, a trilha de auditoria e o cancelamento são o 1209b: aqui nenhuma linha leva a lugar
 * nenhum.
 */

/** Tamanho de página da lista, o mesmo default do servidor (§9.1). */
const PAGE_SIZE = 20;

/** Situação como a tela oferece; `all` é a ausência do parâmetro na query. */
type StatusFilter = 'all' | SaleStatus;

// O foco visível é o global de `index.css` (azul, 2px): aqui não se sobrescreve anel nenhum.
const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none';

/** Valor do `<select>` de situação; fora da união o filtro não muda. */
function isStatusFilter(value: string): value is StatusFilter {
  return value === 'all' || value === 'OPEN' || value === 'COMPLETED' || value === 'CANCELLED';
}

/** Id curto do operador quando a tela não pode resolver o nome — nunca um nome inventado. */
function shortId(id: string): string {
  return id.slice(0, 8);
}

/** Rótulo de um usuário do picker: nome de exibição, senão o username, senão o id curto. */
function userName(user: UserResponse): string {
  if (user.displayName !== undefined && user.displayName !== '') {
    return user.displayName;
  }
  if (user.username !== undefined && user.username !== '') {
    return user.username;
  }
  return user.id === undefined ? '—' : shortId(user.id);
}

/** Nome do operador pelo mapa de `GET /users`; sem o mapa (ou sem o usuário) sobra o id curto. */
function operatorLabel(
  operatorUserId: string | undefined,
  names: ReadonlyMap<string, string> | null,
): string {
  if (operatorUserId === undefined || operatorUserId === '') {
    return '—';
  }
  return names?.get(operatorUserId) ?? shortId(operatorUserId);
}

/** Mapa `id → nome` dos usuários do picker; `null` enquanto a lista não chegou. */
function operatorNames(
  page: PageResponseUserResponse | undefined,
): ReadonlyMap<string, string> | null {
  if (page?.items === undefined) {
    return null;
  }
  const names = new Map<string, string>();
  for (const user of page.items) {
    if (user.id !== undefined) {
      names.set(user.id, userName(user));
    }
  }
  return names;
}

export function SalesPage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [status, setStatus] = useState<StatusFilter>('all');
  const [operatorUserId, setOperatorUserId] = useState('');
  const [page, setPage] = useState(0);

  const canReadUsers = usePermission('user.read');
  const sales = useSales({
    from: localDateTimeToInstant(from),
    to: localDateTimeToInstant(to),
    status: status === 'all' ? undefined : status,
    operatorUserId: operatorUserId === '' ? undefined : operatorUserId,
    page,
    size: PAGE_SIZE,
  });
  const operators = useOperators(canReadUsers);
  const names = useMemo(
    () => (canReadUsers ? operatorNames(operators.data) : null),
    [canReadUsers, operators.data],
  );

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(sales.error)) {
    return (
      <section aria-labelledby="titulo-vendas" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-vendas" className="text-2xl font-semibold">
          Vendas
        </h1>
        <NoPermission
          onRetry={() => {
            void sales.refetch();
          }}
        />
      </section>
    );
  }

  const columns: DataTableColumn<SaleSummaryResponse>[] = [
    {
      id: 'number',
      header: 'Número',
      render: (sale) => (sale.number === undefined ? '—' : String(sale.number)),
    },
    {
      id: 'status',
      header: 'Situação',
      render: (sale) => <SaleStatusBadge status={sale.status} />,
    },
    {
      id: 'createdAt',
      header: 'Data',
      render: (sale) => (sale.createdAt === undefined ? '—' : formatDateTime(sale.createdAt)),
    },
    {
      id: 'operator',
      header: 'Operador',
      render: (sale) => operatorLabel(sale.operatorUserId, names),
    },
    {
      id: 'itemCount',
      header: 'Itens',
      render: (sale) => (sale.itemCount === undefined ? '—' : String(sale.itemCount)),
    },
    {
      id: 'total',
      header: 'Total',
      render: (sale) => (sale.total === undefined ? '—' : formatMoney(sale.total)),
    },
  ];

  return (
    <section aria-labelledby="titulo-vendas" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-vendas" className="text-2xl font-semibold">
          Vendas
        </h1>
        <p className="text-sm text-ink-muted">
          Consulte as vendas da loja por período, situação e operador.
        </p>
      </header>

      <div className="grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-inicio" className="text-sm font-medium text-ink">
            De
          </label>
          <input
            id="filtro-inicio"
            type="datetime-local"
            value={from}
            onChange={(event) => {
              setFrom(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-fim" className="text-sm font-medium text-ink">
            Até (não inclui)
          </label>
          <input
            id="filtro-fim"
            type="datetime-local"
            value={to}
            onChange={(event) => {
              setTo(event.target.value);
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
            value={status}
            onChange={(event) => {
              const value = event.target.value;
              if (isStatusFilter(value)) {
                setStatus(value);
                setPage(0);
              }
            }}
            className={fieldClassName}
          >
            <option value="all">Todas</option>
            <option value="OPEN">Abertas</option>
            <option value="COMPLETED">Concluídas</option>
            <option value="CANCELLED">Canceladas</option>
          </select>
        </div>

        {/* Dado de usuário é de quem tem `user.read`: sem a permissão o filtro não existe. */}
        {canReadUsers ? (
          <div className="flex flex-col gap-1">
            <label htmlFor="filtro-operador" className="text-sm font-medium text-ink">
              Operador
            </label>
            <select
              id="filtro-operador"
              value={operatorUserId}
              onChange={(event) => {
                setOperatorUserId(event.target.value);
                setPage(0);
              }}
              className={fieldClassName}
            >
              <option value="">Todos</option>
              {(operators.data?.items ?? []).map((user) => (
                <option key={user.id} value={user.id}>
                  {userName(user)}
                </option>
              ))}
            </select>
          </div>
        ) : null}
      </div>

      <DataTable
        label="Vendas"
        columns={columns}
        rowKey={(sale) => sale.id ?? ''}
        page={page}
        data={sales.data === undefined ? null : normalizePage(sales.data)}
        loading={sales.isPending || sales.isFetching}
        error={sales.error}
        onPageChange={setPage}
        onRetry={() => {
          void sales.refetch();
        }}
        emptyMessage="Nenhuma venda encontrada para os filtros."
      />
    </section>
  );
}
