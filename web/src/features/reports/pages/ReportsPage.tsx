import { useMemo, useState, type ReactNode } from 'react';
import { localDateTimeToInstant } from '../../../shared/lib/datetime';
import { formatMoney } from '../../../shared/lib/money';
import { normalizePage } from '../../../shared/lib/page';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { formatQuantity } from '../../../shared/lib/quantity';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import { useOperators } from '../../sales/hooks/useSales';
import type { LowStockItemResponse } from '../api/reportsApi';
import { SalesTotalsCards } from '../components/SalesTotalsCards';
import { useLowStockReport, useSalesSummary } from '../hooks/useReports';
import { groupLabel, operatorNames, userLabel } from '../lib/labels';

/**
 * Relatórios (1212b): vendas do período agrupadas por dia ou por operador, e o estoque baixo
 * paginado — leituras do 1212a, que exige `report.read` nas duas (OPERADOR recebe 403, que vira o
 * estado "sem permissão" aqui, §10.3).
 *
 * O período é obrigatório no contrato do `sales-summary` (`from` inclusivo, `to` exclusivo): a
 * consulta só sai com os dois campos preenchidos e `datetime-local` vira ISO com offset via
 * `localDateTimeToInstant`. Os totais gerais (vendas, faturamento, ticket médio) são os campos
 * gerais do servidor — a tela nunca soma os buckets (BR-12).
 *
 * O picker de operador lê `GET /users` e só existe com `user.read`, como no histórico de vendas; o
 * `sales-summary` **não** aceita filtro de operador (o contrato é `from`/`to`/`groupBy`), então o
 * picker foca a linha do operador na tabela do agrupamento por operador — sem recálculo nenhum,
 * apenas escolhendo qual grupo o servidor já devolveu fica visível. Sem `user.read` o picker não
 * aparece e a chave do grupo é o id curto do operador: nome de usuário é dado que a tela não tem e
 * não inventa.
 */

/** Tamanho de página do estoque baixo, o mesmo default do servidor (§9.1). */
const PAGE_SIZE = 20;

/** Agrupamentos que a tela oferece; `paymentMethod` é do dashboard, não deste relatório. */
type Grouping = 'day' | 'operator';

/** Valor do `<select>` de agrupamento; fora da união o agrupamento não muda. */
function isGrouping(value: string): value is Grouping {
  return value === 'day' || value === 'operator';
}

// O foco visível é o global de `index.css` (azul, 2px): aqui não se sobrescreve anel nenhum.
const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none';

const retryButtonClassName =
  'min-h-9 rounded-md border border-danger px-3 text-sm font-medium text-danger transition-colors duration-150 ease-out hover:bg-danger/5 motion-reduce:transition-none';

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

export function ReportsPage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [groupBy, setGroupBy] = useState<Grouping>('day');
  const [operatorUserId, setOperatorUserId] = useState('');
  const [page, setPage] = useState(0);

  const canReadUsers = usePermission('user.read');
  const instantFrom = localDateTimeToInstant(from);
  const instantTo = localDateTimeToInstant(to);
  const periodReady = instantFrom !== undefined && instantTo !== undefined;

  const summary = useSalesSummary({ from: instantFrom, to: instantTo, groupBy }, periodReady);
  const lowStock = useLowStockReport({ page, size: PAGE_SIZE });
  const operators = useOperators(canReadUsers);
  const names = useMemo(
    () => (canReadUsers ? operatorNames(operators.data) : null),
    [canReadUsers, operators.data],
  );

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3); `report.read` cobre as duas.
  if (isForbidden(summary.error) || isForbidden(lowStock.error)) {
    return (
      <section
        aria-labelledby="titulo-relatorios"
        className="mx-auto flex max-w-6xl flex-col gap-6"
      >
        <h1 id="titulo-relatorios" className="text-2xl font-semibold">
          Relatórios
        </h1>
        <NoPermission
          onRetry={() => {
            void summary.refetch();
            void lowStock.refetch();
          }}
        />
      </section>
    );
  }

  const lowStockColumns: DataTableColumn<LowStockItemResponse>[] = [
    { id: 'name', header: 'Produto', render: (item) => item.name ?? '—' },
    { id: 'barcode', header: 'Código', render: (item) => item.barcode ?? '—' },
    { id: 'unit', header: 'Unidade', render: (item) => item.unit ?? '—' },
    {
      id: 'quantity',
      header: 'Saldo',
      render: (item) => (item.quantity === undefined ? '—' : formatQuantity(item.quantity)),
    },
    {
      id: 'minQuantity',
      header: 'Mínimo',
      render: (item) => {
        // O contrato só promete `number`, mas o mínimo do produto pode não estar configurado.
        const minQuantity: number | null | undefined = item.minQuantity;
        return minQuantity === undefined || minQuantity === null
          ? '—'
          : formatQuantity(minQuantity);
      },
    },
  ];

  let salesContent: ReactNode = null;
  if (!periodReady) {
    salesContent = (
      <p role="status" className="text-sm text-ink-muted">
        Informe o período (De e Até) para consultar as vendas.
      </p>
    );
  } else if (summary.isPending) {
    salesContent = (
      <p role="status" className="text-sm text-ink-muted">
        Carregando…
      </p>
    );
  } else if (summary.error !== null && summary.error !== undefined) {
    salesContent = (
      <div role="alert" className="flex flex-wrap items-center gap-3 text-sm">
        <p className="font-medium text-danger">{errorMessage(summary.error)}</p>
        <button
          type="button"
          onClick={() => {
            void summary.refetch();
          }}
          className={retryButtonClassName}
        >
          Tentar de novo
        </button>
      </div>
    );
  } else if (summary.data !== undefined) {
    const groups = summary.data.groups ?? [];
    // O foco no operador é a seleção de qual grupo o servidor já devolveu fica visível: nenhum
    // número é somado ou recalculado aqui, e a consulta ao servidor não muda com o picker.
    const visibleGroups =
      groupBy === 'operator' && operatorUserId !== ''
        ? groups.filter((group) => group.key === operatorUserId)
        : groups;

    salesContent = (
      <div className="flex flex-col gap-4">
        <SalesTotalsCards summary={summary.data} />
        <p className="text-xs text-ink-muted">
          Totais do período, de todas as vendas concluídas da loja.
        </p>

        <div className="overflow-x-auto rounded-lg border border-line bg-surface">
          <table aria-label="Vendas por grupo" className="w-full text-left text-sm">
            <thead className="border-b border-line bg-canvas text-ink-muted">
              <tr>
                <th scope="col" className={headerCellClassName}>
                  Grupo
                </th>
                <th scope="col" className={headerCellClassName}>
                  Vendas
                </th>
                <th scope="col" className={headerCellClassName}>
                  Total
                </th>
              </tr>
            </thead>

            <tbody className="divide-y divide-line">
              {visibleGroups.length === 0 ? (
                <tr>
                  <td colSpan={3} className="px-3 py-6 text-center text-sm text-ink-muted">
                    {groupBy === 'operator' && operatorUserId !== ''
                      ? 'O operador selecionado não tem vendas no período.'
                      : 'Nenhuma venda no período.'}
                  </td>
                </tr>
              ) : (
                visibleGroups.map((group, index) => (
                  <tr key={group.key ?? index}>
                    <td className={bodyCellClassName}>
                      {group.key === undefined ? '—' : groupLabel(groupBy, group.key, names)}
                    </td>
                    <td className={bodyCellClassName}>
                      {group.salesCount === undefined ? '—' : String(group.salesCount)}
                    </td>
                    <td className={`${bodyCellClassName} whitespace-nowrap`}>
                      {group.total === undefined ? '—' : formatMoney(group.total)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  return (
    <section aria-labelledby="titulo-relatorios" className="mx-auto flex max-w-6xl flex-col gap-8">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-relatorios" className="text-2xl font-semibold">
          Relatórios
        </h1>
        <p className="text-sm text-ink-muted">
          Vendas por período e operador, e os produtos no mínimo de estoque.
        </p>
      </header>

      <section aria-labelledby="titulo-relatorio-vendas" className="flex flex-col gap-4">
        <h2 id="titulo-relatorio-vendas" className="text-lg font-semibold">
          Vendas
        </h2>

        <div className="grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1">
            <label htmlFor="relatorio-inicio" className="text-sm font-medium text-ink">
              De
            </label>
            <input
              id="relatorio-inicio"
              type="datetime-local"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
              }}
              className={fieldClassName}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="relatorio-fim" className="text-sm font-medium text-ink">
              Até (não inclui)
            </label>
            <input
              id="relatorio-fim"
              type="datetime-local"
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
              }}
              className={fieldClassName}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="relatorio-agrupamento" className="text-sm font-medium text-ink">
              Agrupar por
            </label>
            <select
              id="relatorio-agrupamento"
              value={groupBy}
              onChange={(event) => {
                const value = event.target.value;
                if (isGrouping(value)) {
                  setGroupBy(value);
                }
              }}
              className={fieldClassName}
            >
              <option value="day">Por dia</option>
              <option value="operator">Por operador</option>
            </select>
          </div>

          {/* Dado de usuário é de quem tem `user.read`: sem a permissão o picker não existe. Ele
              aparece só no agrupamento por operador, que é a tabela que ele foca. */}
          {groupBy === 'operator' && canReadUsers ? (
            <div className="flex flex-col gap-1">
              <label htmlFor="relatorio-operador" className="text-sm font-medium text-ink">
                Operador
              </label>
              <select
                id="relatorio-operador"
                value={operatorUserId}
                onChange={(event) => {
                  setOperatorUserId(event.target.value);
                }}
                className={fieldClassName}
              >
                <option value="">Todos</option>
                {(operators.data?.items ?? []).map((user) => (
                  <option key={user.id} value={user.id}>
                    {userLabel(user)}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
        </div>

        {salesContent}
      </section>

      <section aria-labelledby="titulo-relatorio-estoque" className="flex flex-col gap-4">
        <h2 id="titulo-relatorio-estoque" className="text-lg font-semibold">
          Estoque baixo
        </h2>

        <DataTable
          label="Estoque baixo"
          columns={lowStockColumns}
          rowKey={(item) => item.productId ?? ''}
          page={page}
          data={lowStock.data === undefined ? null : normalizePage(lowStock.data)}
          loading={lowStock.isPending || lowStock.isFetching}
          error={lowStock.error}
          onPageChange={setPage}
          onRetry={() => {
            void lowStock.refetch();
          }}
          emptyMessage="Nenhum produto no mínimo de estoque."
        />
      </section>
    </section>
  );
}
