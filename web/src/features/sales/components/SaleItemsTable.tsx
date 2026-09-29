import { formatMoney } from '../../../shared/lib/money';
import { formatQuantity } from '../../../shared/lib/quantity';
import type { SaleItemResponse } from '../api/salesApi';

/**
 * Itens da venda no detalhe (1209b): o snapshot de cada linha, como o servidor o gravou (BR-01).
 * Preço unitário e total da linha são exibidos em pt-BR e **nunca** recalculados aqui (BR-12);
 * quantidade sai na escala de três casas de `numeric(14,3)` (§5).
 */

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

/** O contrato promete `undefined`, mas campo ausente chega como `null` na resposta JSON. */
type Nullable<T> = T | null | undefined;

/** Decimal do servidor em pt-BR; ausente vira traço, nunca zero inventado (BR-12). */
function money(value: Nullable<number>): string {
  return value === undefined || value === null ? '—' : formatMoney(value);
}

/** Quantidade em pt-BR; ausente vira traço. */
function quantity(value: Nullable<number>): string {
  return value === undefined || value === null ? '—' : formatQuantity(value);
}

export interface SaleItemsTableProps {
  items: readonly SaleItemResponse[];
}

export function SaleItemsTable({ items }: SaleItemsTableProps) {
  if (items.length === 0) {
    return <p className="text-sm text-ink-muted">Nenhum item na venda.</p>;
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table aria-label="Itens da venda" className="w-full text-left text-sm">
        <thead className="border-b border-line bg-canvas text-ink-muted">
          <tr>
            <th scope="col" className={headerCellClassName}>
              Nome
            </th>
            <th scope="col" className={headerCellClassName}>
              Código
            </th>
            <th scope="col" className={headerCellClassName}>
              Unidade
            </th>
            <th scope="col" className={headerCellClassName}>
              Quantidade
            </th>
            <th scope="col" className={headerCellClassName}>
              Preço unitário
            </th>
            <th scope="col" className={headerCellClassName}>
              Total da linha
            </th>
          </tr>
        </thead>

        <tbody className="divide-y divide-line">
          {items.map((item, index) => (
            <tr key={item.productId ?? index}>
              <td className={bodyCellClassName}>{item.name ?? '—'}</td>
              <td className={`${bodyCellClassName} whitespace-nowrap text-ink-muted`}>
                {item.barcode ?? '—'}
              </td>
              <td className={bodyCellClassName}>{item.unit ?? '—'}</td>
              <td className={`${bodyCellClassName} whitespace-nowrap`}>{quantity(item.quantity)}</td>
              <td className={`${bodyCellClassName} whitespace-nowrap`}>{money(item.unitPrice)}</td>
              <td className={`${bodyCellClassName} whitespace-nowrap`}>{money(item.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
