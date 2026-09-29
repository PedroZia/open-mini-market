import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { formatDateTime } from '../../../shared/lib/datetime';
import { formatMoney } from '../../../shared/lib/money';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { DiscountType } from '../api/salesApi';
import { CancelSaleModal } from '../components/CancelSaleModal';
import { SaleAuditTrail } from '../components/SaleAuditTrail';
import { SaleItemsTable } from '../components/SaleItemsTable';
import { SalePaymentsTable } from '../components/SalePaymentsTable';
import { SaleStatusBadge } from '../components/SaleStatusBadge';
import { useSaleDetail } from '../hooks/useSales';

/**
 * Detalhe da venda (1209b): cabeçalho, itens, pagamentos, desconto e a trilha de auditoria, mais o
 * cancelamento da venda aberta. Todos os valores são os do servidor — subtotal, desconto, total,
 * troco e totais de linha **nunca** são recalculados aqui (BR-12).
 *
 * A leitura (`GET /sales/{id}`) é `@Authenticated` sem permissão própria: a sessão lê a venda do seu
 * caixa e quem tem `report.read` lê a de qualquer caixa — venda de outro caixa vira 403 e o estado
 * "sem permissão", nunca tela em branco.
 *
 * O cancelamento só aparece com a venda `OPEN` e `sale.cancel` na sessão; a trilha só existe com
 * `audit.read` (sem a permissão a query nem sai). Operador e cliente aparecem pelo id curto — o
 * detalhe não traz nome de usuário nem de cliente, e a tela não inventa o que não recebeu.
 */

const backLinkClassName =
  'text-sm font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none';

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const cancelButtonClassName =
  'min-h-10 rounded-md bg-danger px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-danger/90 motion-reduce:transition-none';

/** O contrato promete `undefined`, mas campo ausente chega como `null` na resposta JSON. */
type Nullable<T> = T | null | undefined;

/** Percentual informado do desconto em pt-BR (até duas casas). */
const PERCENT = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });

/** Uma linha do resumo: rótulo e valor de um campo que o servidor devolveu. */
function SummaryItem({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-sm font-medium text-ink-muted">{label}</dt>
      <dd className="text-lg text-ink">{children}</dd>
    </div>
  );
}

/** Decimal do servidor em pt-BR; ausente vira traço, nunca zero inventado (BR-12). */
function money(value: Nullable<number>): string {
  return value === undefined || value === null ? '—' : formatMoney(value);
}

/** Instante do servidor convertido para pt-BR na apresentação; ausente vira traço. */
function moment(value: Nullable<string>): string {
  return value === undefined || value === null ? '—' : formatDateTime(value);
}

/** Id curto de um UUID do contrato; ausente vira traço — o id completo fica no `title`. */
function shortId(value: Nullable<string>): string {
  return value === undefined || value === null || value === '' ? '—' : value.slice(0, 8);
}

/** Valor informado do desconto: reais em `VALUE`, percentual em `PERCENT` (BR-03). */
function discountInformed(type: Nullable<DiscountType>, value: Nullable<number>): string {
  if (type === undefined || type === null || value === undefined || value === null) {
    return '—';
  }
  return type === 'PERCENT' ? `${PERCENT.format(value)}%` : formatMoney(value);
}

/** `true` quando o campo opcional veio preenchido (vazio e `null` contam como ausente). */
function present(value: Nullable<string>): boolean {
  return value !== undefined && value !== null && value !== '';
}

export function SaleDetailPage() {
  const saleId = useParams().id ?? '';
  const sale = useSaleDetail(saleId);
  const canCancel = usePermission('sale.cancel');
  const canReadAudit = usePermission('audit.read');
  const [cancelling, setCancelling] = useState(false);

  // Leitura negada (403) vira estado próprio, não tela em branco (§10.3).
  if (isForbidden(sale.error)) {
    return (
      <section aria-labelledby="titulo-venda" className="mx-auto flex max-w-5xl flex-col gap-6">
        <h1 id="titulo-venda" className="text-2xl font-semibold">
          Venda
        </h1>
        <NoPermission
          onRetry={() => {
            void sale.refetch();
          }}
        />
      </section>
    );
  }

  const failed = sale.error !== undefined && sale.error !== null;
  const detail = sale.data;
  // A venda cancelável é a que está aberta agora — o status é do servidor, não da tela.
  const open = detail?.status === 'OPEN';

  return (
    <section aria-labelledby="titulo-venda" className="mx-auto flex max-w-5xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <Link to="/sales" className={backLinkClassName}>
            ← Voltar para vendas
          </Link>
          <h1 id="titulo-venda" className="text-2xl font-semibold">
            {detail?.number === undefined ? 'Venda' : `Venda ${detail.number}`}
          </h1>
          <p className="text-sm text-ink-muted">Itens, pagamentos, desconto e histórico da venda.</p>
        </div>

        {/* A ação só existe com a venda aberta e a permissão do servidor. */}
        {detail !== undefined && open && canCancel ? (
          <button
            type="button"
            onClick={() => setCancelling(true)}
            className={cancelButtonClassName}
          >
            Cancelar venda
          </button>
        ) : null}
      </header>

      {failed && detail === undefined ? (
        <div role="alert" className="flex flex-col items-start gap-3 text-sm">
          <p className="font-medium text-danger">{errorMessage(sale.error)}</p>
          <button
            type="button"
            onClick={() => {
              void sale.refetch();
            }}
            className={retryButtonClassName}
          >
            Tentar de novo
          </button>
        </div>
      ) : detail === undefined ? (
        <p role="status" className="text-sm text-ink-muted">
          Carregando…
        </p>
      ) : (
        <>
          {failed ? (
            <p
              role="alert"
              className="flex flex-wrap items-center gap-3 text-sm font-medium text-danger"
            >
              {errorMessage(sale.error)}
              <button
                type="button"
                onClick={() => {
                  void sale.refetch();
                }}
                className={retryButtonClassName}
              >
                Tentar de novo
              </button>
            </p>
          ) : null}

          <dl className="grid gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2 lg:grid-cols-3">
            <SummaryItem label="Situação">
              <SaleStatusBadge status={detail.status} />
            </SummaryItem>
            <SummaryItem label="Data">{moment(detail.createdAt)}</SummaryItem>
            <SummaryItem label="Operador">
              <span title={detail.operatorUserId ?? undefined}>
                {shortId(detail.operatorUserId)}
              </span>
            </SummaryItem>
            {present(detail.customerId) ? (
              <SummaryItem label="Cliente">
                <span title={detail.customerId}>{shortId(detail.customerId)}</span>
              </SummaryItem>
            ) : null}
            {present(detail.completedAt) ? (
              <SummaryItem label="Concluída em">{moment(detail.completedAt)}</SummaryItem>
            ) : null}
          </dl>

          {detail.status === 'CANCELLED' ? (
            <section
              aria-labelledby="titulo-cancelamento"
              className="rounded-lg border border-danger/40 bg-danger/5 p-4"
            >
              <h2 id="titulo-cancelamento" className="text-lg font-semibold text-danger">
                Venda cancelada
              </h2>
              <dl className="mt-2 grid gap-2 text-sm sm:grid-cols-3">
                <SummaryItem label="Motivo">{detail.cancelReason ?? '—'}</SummaryItem>
                <SummaryItem label="Cancelada por">{shortId(detail.cancelledByUserId)}</SummaryItem>
                <SummaryItem label="Cancelada em">{moment(detail.cancelledAt)}</SummaryItem>
              </dl>
            </section>
          ) : null}

          <section aria-labelledby="titulo-itens" className="flex flex-col gap-3">
            <h2 id="titulo-itens" className="text-lg font-semibold">
              Itens
            </h2>
            <SaleItemsTable items={detail.items ?? []} />
          </section>

          <section aria-labelledby="titulo-pagamentos" className="flex flex-col gap-3">
            <h2 id="titulo-pagamentos" className="text-lg font-semibold">
              Pagamentos
            </h2>
            <SalePaymentsTable payments={detail.payments ?? []} />
          </section>

          <section aria-labelledby="titulo-totais" className="flex flex-col gap-3">
            <h2 id="titulo-totais" className="text-lg font-semibold">
              Totais
            </h2>
            <dl className="grid gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2 lg:grid-cols-3">
              <SummaryItem label="Subtotal">{money(detail.subtotal)}</SummaryItem>
              {detail.discountType === undefined || detail.discountType === null ? null : (
                <SummaryItem label="Desconto">
                  {money(detail.discountAmount)}
                  <span className="block text-xs font-normal text-ink-muted">
                    {detail.discountType === 'PERCENT' ? 'Percentual' : 'Valor'} informado:{' '}
                    {discountInformed(detail.discountType, detail.discountValue)}
                    {present(detail.discountReason) ? ` · ${detail.discountReason ?? ''}` : null}
                  </span>
                </SummaryItem>
              )}
              <SummaryItem label="Total">{money(detail.total)}</SummaryItem>
              <SummaryItem label="Pago">{money(detail.paidAmount)}</SummaryItem>
              <SummaryItem label="Troco">{money(detail.changeAmount)}</SummaryItem>
            </dl>
          </section>

          {/* A trilha é do servidor e só existe para quem tem `audit.read`. */}
          {canReadAudit ? (
            <section aria-labelledby="titulo-trilha" className="flex flex-col gap-3">
              <h2 id="titulo-trilha" className="text-lg font-semibold">
                Trilha de auditoria
              </h2>
              <SaleAuditTrail saleId={saleId} />
            </section>
          ) : null}
        </>
      )}

      {/* O modal vive enquanto o cancelamento está em curso: o 409 com o detalhe relido precisa da
          mensagem na tela, e o botão da ação desaparece quando a venda deixa de estar aberta. */}
      {detail !== undefined && cancelling ? (
        <CancelSaleModal saleId={saleId} onClose={() => setCancelling(false)} />
      ) : null}
    </section>
  );
}
