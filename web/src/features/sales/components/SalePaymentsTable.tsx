import { formatMoney } from '../../../shared/lib/money';
import type { PaymentMethod, PaymentResponse, PaymentStatus } from '../api/salesApi';

/**
 * Pagamentos da venda no detalhe (1209b): cada linha como o servidor a gravou — valor, troco
 * calculado por ele e situação (BR-05, BR-12). Pagamento cancelado continua na lista: desfazer não
 * apaga o rastro. Nada é recalculado aqui.
 */

/** Rótulos pt-BR das formas do contrato; o código é que viaja. */
const METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Dinheiro',
  PIX: 'PIX',
  DEBIT: 'Débito',
  CREDIT: 'Crédito',
  VOUCHER: 'Voucher',
};

/** Situação do pagamento em pt-BR. */
const STATUS_LABELS: Record<PaymentStatus, string> = {
  APPROVED: 'Aprovado',
  CANCELLED: 'Cancelado',
};

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

/** O contrato promete `undefined`, mas campo ausente chega como `null` na resposta JSON. */
type Nullable<T> = T | null | undefined;

/** Decimal do servidor em pt-BR; ausente vira traço, nunca zero inventado (BR-12). */
function money(value: Nullable<number>): string {
  return value === undefined || value === null ? '—' : formatMoney(value);
}

function methodLabel(method: Nullable<PaymentMethod>): string {
  return method === undefined || method === null ? '—' : METHOD_LABELS[method];
}

function statusLabel(status: Nullable<PaymentStatus>): string {
  return status === undefined || status === null ? '—' : STATUS_LABELS[status];
}

export interface SalePaymentsTableProps {
  payments: readonly PaymentResponse[];
}

export function SalePaymentsTable({ payments }: SalePaymentsTableProps) {
  if (payments.length === 0) {
    return <p className="text-sm text-ink-muted">Nenhum pagamento registrado.</p>;
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table aria-label="Pagamentos da venda" className="w-full text-left text-sm">
        <thead className="border-b border-line bg-canvas text-ink-muted">
          <tr>
            <th scope="col" className={headerCellClassName}>
              Método
            </th>
            <th scope="col" className={headerCellClassName}>
              Valor
            </th>
            <th scope="col" className={headerCellClassName}>
              Troco
            </th>
            <th scope="col" className={headerCellClassName}>
              Situação
            </th>
          </tr>
        </thead>

        <tbody className="divide-y divide-line">
          {payments.map((payment, index) => (
            <tr key={payment.id ?? index}>
              <td className={bodyCellClassName}>{methodLabel(payment.method)}</td>
              <td className={`${bodyCellClassName} whitespace-nowrap`}>{money(payment.amount)}</td>
              <td className={`${bodyCellClassName} whitespace-nowrap`}>{money(payment.changeAmount)}</td>
              <td className={bodyCellClassName}>{statusLabel(payment.status)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
