import { formatDateTime } from '../../../shared/lib/datetime';
import { formatMoney } from '../../../shared/lib/money';
import { formatQuantity } from '../../../shared/lib/quantity';
import type { StockMovementResponse, StockMovementType } from '../api/stockApi';

/**
 * Movimentos do ledger no detalhe do estoque (1206b): a resposta embutida de `GET
 * /stock/{productId}` (os vinte mais recentes, do mais novo para o mais antigo) — o recurso não tem
 * rota paginada de movimentos, então a tabela não pede página (§9.3, divergência registrada).
 *
 * Nada é recalculado aqui (BR-12): delta, `balanceAfter` e custo vêm como o servidor os gravou, e a
 * data só muda de fuso para exibição.
 */

/** Rótulos pt-BR dos tipos do ledger; o código do contrato é que viaja. */
const TYPE_LABELS: Record<StockMovementType, string> = {
  INITIAL: 'Inicial',
  PURCHASE_IN: 'Entrada',
  SALE_OUT: 'Venda',
  RETURN_IN: 'Devolução',
  ADJUSTMENT: 'Ajuste',
  LOSS: 'Perda',
};

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

/** Delta com o sinal explícito no ganho: a coluna diz se o saldo subiu ou desceu. */
function formatDelta(value: number | undefined): string {
  if (value === undefined) {
    return '—';
  }
  const formatted = formatQuantity(value);
  return value > 0 ? `+${formatted}` : formatted;
}

function typeLabel(type: StockMovementType | undefined): string {
  return type === undefined ? '—' : (TYPE_LABELS[type] ?? type);
}

export interface StockMovementsTableProps {
  movements: readonly StockMovementResponse[];
}

export function StockMovementsTable({ movements }: StockMovementsTableProps) {
  if (movements.length === 0) {
    return <p className="text-sm text-ink-muted">Nenhum movimento registrado ainda.</p>;
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table aria-label="Movimentos de estoque" className="w-full text-left text-sm">
        <thead className="border-b border-line bg-canvas text-ink-muted">
          <tr>
            <th scope="col" className={headerCellClassName}>
              Data
            </th>
            <th scope="col" className={headerCellClassName}>
              Tipo
            </th>
            <th scope="col" className={headerCellClassName}>
              Quantidade
            </th>
            <th scope="col" className={headerCellClassName}>
              Saldo após
            </th>
            <th scope="col" className={headerCellClassName}>
              Custo unitário
            </th>
            <th scope="col" className={headerCellClassName}>
              Motivo
            </th>
          </tr>
        </thead>

        <tbody className="divide-y divide-line">
          {movements.map((movement, index) => (
            <tr key={movement.id ?? index}>
              <td className={`${bodyCellClassName} whitespace-nowrap text-ink-muted`}>
                {movement.createdAt === undefined ? '—' : formatDateTime(movement.createdAt)}
              </td>
              <td className={bodyCellClassName}>{typeLabel(movement.type)}</td>
              <td className={bodyCellClassName}>{formatDelta(movement.quantityDelta)}</td>
              <td className={bodyCellClassName}>
                {movement.balanceAfter === undefined
                  ? '—'
                  : formatQuantity(movement.balanceAfter)}
              </td>
              <td className={bodyCellClassName}>
                {movement.unitCost === undefined || movement.unitCost === null
                  ? '—'
                  : formatMoney(movement.unitCost)}
              </td>
              <td className={`${bodyCellClassName} text-ink-muted`}>{movement.reason ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
