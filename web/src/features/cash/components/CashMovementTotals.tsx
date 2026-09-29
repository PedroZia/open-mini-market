import { formatMoney } from '../../../shared/lib/money';
import type { CashMovementType } from '../api/cashApi';

/**
 * Totais por tipo de movimento da sessão (1210a): a tabela da sessão atual (`current-session`) e do
 * resumo (`summary`) — as duas respostas trazem o mesmo mapa, zero-preenchido com os quatro tipos.
 *
 * Nada é somado nem recalculado aqui (BR-12): cada valor é o que o servidor devolveu; chave ausente
 * vira traço, nunca zero inventado. A ordem é a do enum do contrato.
 */

const TYPE_ORDER: readonly CashMovementType[] = ['OPENING', 'SALE', 'WITHDRAWAL', 'SUPPLY'];

/** Rótulos pt-BR dos tipos; o código do contrato é que viaja. */
const TYPE_LABELS: Record<CashMovementType, string> = {
  OPENING: 'Abertura',
  SALE: 'Vendas',
  WITHDRAWAL: 'Sangrias',
  SUPPLY: 'Suprimentos',
};

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

export interface CashMovementTotalsProps {
  /** `totalsByType` como o contrato o promete; `undefined` enquanto a resposta não chegou. */
  totals: { [key: string]: number } | undefined;
}

export function CashMovementTotals({ totals }: CashMovementTotalsProps) {
  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table aria-label="Totais por tipo de movimento" className="w-full text-left text-sm">
        <thead className="border-b border-line bg-canvas text-ink-muted">
          <tr>
            <th scope="col" className={headerCellClassName}>
              Tipo
            </th>
            <th scope="col" className={headerCellClassName}>
              Total
            </th>
          </tr>
        </thead>

        <tbody className="divide-y divide-line">
          {TYPE_ORDER.map((type) => {
            const value = totals?.[type];
            return (
              <tr key={type}>
                <td className={bodyCellClassName}>{TYPE_LABELS[type]}</td>
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
