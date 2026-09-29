import { useMemo, type ReactNode } from 'react';
import { formatMoney } from '../../../shared/lib/money';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import { SalesTotalsCards } from '../../reports/components/SalesTotalsCards';
import { useSalesSummary } from '../../reports/hooks/useReports';
import { paymentMethodLabel } from '../../reports/lib/labels';
import { localDayRange } from '../lib/dayRange';

/**
 * Dashboard da retaguarda (1212b): o resumo do dia local — vendas, faturamento, ticket médio e as
 * formas de pagamento — consumindo o `GET /reports/sales-summary` do 1212a em duas consultas do
 * mesmo período: `groupBy=day` só pelos totais gerais (os campos gerais do servidor, nunca a soma
 * dos buckets) e `groupBy=paymentMethod` pelas formas.
 *
 * Sem `report.read` (OPERADOR) as consultas nem saem (`enabled`) e o lugar dos números fica com o
 * estado "sem permissão" (§10.3): "Início" é a rota de todo mundo, então esconder o item de menu não
 * bastaria.
 */

const retryButtonClassName =
  'min-h-9 rounded-md border border-danger px-3 text-sm font-medium text-danger transition-colors duration-150 ease-out hover:bg-danger/5 motion-reduce:transition-none';

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

/**
 * Carga e erro de uma consulta do resumo; `null` quando os dados estão na tela (ou a caminho de uma
 * atualização, que não apaga o que já veio). O 403 não passa por aqui: sem `report.read` nem há
 * consulta, e a página mostra o estado de permissão antes.
 */
function summaryState(pending: boolean, error: unknown, onRetry: () => void): ReactNode {
  if (pending) {
    return (
      <p role="status" className="text-sm text-ink-muted">
        Carregando…
      </p>
    );
  }
  if (error !== null && error !== undefined) {
    return (
      <div role="alert" className="flex flex-wrap items-center gap-3 text-sm">
        <p className="font-medium text-danger">{errorMessage(error)}</p>
        <button type="button" onClick={onRetry} className={retryButtonClassName}>
          Tentar de novo
        </button>
      </div>
    );
  }
  return null;
}

export function DashboardPage() {
  const canReadReports = usePermission('report.read');
  // O dia é fixado na abertura da página: o dashboard é a visão do dia em que se abriu a tela.
  const day = useMemo(() => localDayRange(new Date()), []);
  const totals = useSalesSummary({ from: day.from, to: day.to, groupBy: 'day' }, canReadReports);
  const methods = useSalesSummary(
    { from: day.from, to: day.to, groupBy: 'paymentMethod' },
    canReadReports,
  );

  const methodGroups = methods.data?.groups ?? [];

  return (
    <section aria-labelledby="titulo-inicio" className="mx-auto flex max-w-6xl flex-col gap-8">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-inicio" className="text-2xl font-semibold">
          Início
        </h1>
        <p className="text-sm text-ink-muted">Vendas do dia e formas de pagamento.</p>
      </header>

      {!canReadReports ? (
        <NoPermission />
      ) : (
        <>
          <section aria-labelledby="titulo-resumo-dia" className="flex flex-col gap-3">
            <h2 id="titulo-resumo-dia" className="text-lg font-semibold">
              Resumo do dia
            </h2>

            {summaryState(totals.isPending, totals.error, () => {
              void totals.refetch();
            }) ??
              (totals.data === undefined ? null : <SalesTotalsCards summary={totals.data} />)}
          </section>

          <section aria-labelledby="titulo-formas-pagamento" className="flex flex-col gap-3">
            <h2 id="titulo-formas-pagamento" className="text-lg font-semibold">
              Formas de pagamento
            </h2>

            {summaryState(methods.isPending, methods.error, () => {
              void methods.refetch();
            }) ?? (
              <div className="overflow-x-auto rounded-lg border border-line bg-surface">
                <table aria-label="Formas de pagamento do dia" className="w-full text-left text-sm">
                  <thead className="border-b border-line bg-canvas text-ink-muted">
                    <tr>
                      <th scope="col" className={headerCellClassName}>
                        Forma
                      </th>
                      <th scope="col" className={headerCellClassName}>
                        Total
                      </th>
                    </tr>
                  </thead>

                  <tbody className="divide-y divide-line">
                    {methodGroups.length === 0 ? (
                      <tr>
                        <td colSpan={2} className="px-3 py-6 text-center text-sm text-ink-muted">
                          Nenhum pagamento no dia.
                        </td>
                      </tr>
                    ) : (
                      methodGroups.map((group, index) => (
                        <tr key={group.key ?? index}>
                          <td className={bodyCellClassName}>
                            {group.key === undefined ? '—' : paymentMethodLabel(group.key)}
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
            )}
          </section>
        </>
      )}
    </section>
  );
}
