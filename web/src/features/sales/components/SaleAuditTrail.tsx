import { formatDateTime } from '../../../shared/lib/datetime';
import { errorMessage } from '../../../shared/lib/problem';
import type { AuditEventResponse, OperationSource } from '../api/salesApi';
import { useSaleAuditEvents } from '../hooks/useSales';

/**
 * Trilha de auditoria da venda (1209b): os eventos de `GET /audit-events` filtrados por
 * `entityType=SALE` e `entityId`, em ordem crescente. O detalhe da venda não traz a auditoria
 * embutida — é esta consulta que conta a história da venda.
 *
 * Só quem tem `audit.read` chega aqui (o pai nem monta o componente sem a permissão) — a query
 * nasce habilitada e o servidor continua sendo a autoridade. O `details` do evento fica de fora: o
 * visualizador rico de antes/depois é o 1211, e esta tela não inventa payload.
 */

/** Rótulos pt-BR das ações de venda do backend; código fora do mapa aparece cru, nunca inventado. */
const ACTION_LABELS: Record<string, string> = {
  SALE_CREATED: 'Venda aberta',
  SALE_ITEM_ADDED: 'Item incluído',
  SALE_ITEM_QUANTITY_CHANGED: 'Quantidade alterada',
  SALE_ITEM_REMOVED: 'Item removido',
  SALE_DISCOUNT_APPLIED: 'Desconto aplicado',
  SALE_DISCOUNT_REMOVED: 'Desconto removido',
  SALE_CUSTOMER_LINKED: 'Cliente vinculado',
  SALE_CUSTOMER_UNLINKED: 'Cliente desvinculado',
  PAYMENT_ADDED: 'Pagamento registrado',
  PAYMENT_CANCELLED: 'Pagamento cancelado',
  SALE_COMPLETED: 'Venda concluída',
  SALE_CANCELLED: 'Venda cancelada',
};

/** Origem da operação em pt-BR (a TUI e a retaguarda escrevem na mesma trilha). */
const SOURCE_LABELS: Record<OperationSource, string> = {
  API: 'API',
  TUI: 'TUI',
  WEB: 'Retaguarda',
  SYSTEM: 'Sistema',
};

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

function actionLabel(action: string | undefined): string {
  if (action === undefined || action === '') {
    return '—';
  }
  return ACTION_LABELS[action] ?? action;
}

function sourceLabel(source: OperationSource | undefined): string {
  return source === undefined ? '—' : SOURCE_LABELS[source];
}

export interface SaleAuditTrailProps {
  saleId: string;
}

export function SaleAuditTrail({ saleId }: SaleAuditTrailProps) {
  const audit = useSaleAuditEvents(saleId, true);
  const events: readonly AuditEventResponse[] = audit.data?.items ?? [];
  const failed = audit.error !== undefined && audit.error !== null;

  if (audit.isPending) {
    return (
      <p role="status" className="text-sm text-ink-muted">
        Carregando a trilha…
      </p>
    );
  }

  if (failed) {
    return (
      <div role="alert" className="flex flex-col items-start gap-3 text-sm">
        <p className="font-medium text-danger">{errorMessage(audit.error)}</p>
        <button
          type="button"
          onClick={() => {
            void audit.refetch();
          }}
          className={retryButtonClassName}
        >
          Tentar de novo
        </button>
      </div>
    );
  }

  if (events.length === 0) {
    return <p className="text-sm text-ink-muted">Nenhum evento registrado para esta venda.</p>;
  }

  return (
    <ol className="flex flex-col gap-3 rounded-lg border border-line bg-surface p-4 text-sm">
      {events.map((event, index) => (
        <li
          key={event.id ?? index}
          className="flex flex-col gap-0.5 border-b border-line pb-3 last:border-b-0 last:pb-0"
        >
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="font-medium text-ink">{actionLabel(event.action)}</span>
            <span className="text-xs text-ink-muted">
              {event.occurredAt === undefined ? '—' : formatDateTime(event.occurredAt)}
            </span>
          </div>
          <p className="text-xs text-ink-muted">
            {event.actorUsername === undefined || event.actorUsername === ''
              ? '—'
              : event.actorUsername}
            {' · '}
            {sourceLabel(event.source)}
            {event.reason === undefined || event.reason === null || event.reason === ''
              ? null
              : ` · Motivo: ${event.reason}`}
          </p>
        </li>
      ))}
    </ol>
  );
}
