import { formatMoney } from '../../../shared/lib/money';
import type { PaymentMethod } from '../api/cashApi';

/**
 * Vendas da sessão por forma de pagamento (1210a, passo 909): mostra que a venda no cartão não
 * passou pela gaveta. As cinco formas vêm zero-preenchidas do servidor, com o nome como chave, na
 * ordem do enum — a tabela desenha todas e usa traço para chave ausente, sem inventar zero.
 */

const METHOD_ORDER: readonly PaymentMethod[] = ['CASH', 'PIX', 'DEBIT', 'CREDIT', 'VOUCHER'];

/** Rótulos pt-BR das formas do contrato; o código é que viaja. */
const METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Dinheiro',
  PIX: 'PIX',
  DEBIT: 'Débito',
  CREDIT: 'Crédito',
  VOUCHER: 'Voucher',
};

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

export interface CashPaymentsByMethodProps {
  /** `paymentsByMethod` como o contrato o promete; `undefined` enquanto a resposta não chegou. */
  payments: { [key: string]: number } | undefined;
}

export function CashPaymentsByMethod({ payments }: CashPaymentsByMethodProps) {
  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table aria-label="Vendas por forma de pagamento" className="w-full text-left text-sm">
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
          {METHOD_ORDER.map((method) => {
            const value = payments?.[method];
            return (
              <tr key={method}>
                <td className={bodyCellClassName}>{METHOD_LABELS[method]}</td>
                <td className={`${bodyCellClassName} whitespace-nowrap`}>
                  {value === undefined ? '—' : formatMoney(value)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
