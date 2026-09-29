import { formatMoney } from '../../../shared/lib/money';
import type { SalesSummaryResponse } from '../api/reportsApi';

/**
 * Totais gerais do resumo (1212b): vendas, faturamento e ticket médio exatamente como o servidor os
 * calculou. Nada é somado nem recalculado aqui (BR-12) — são os campos gerais do
 * `SalesSummaryResponse`, que valem para o período inteiro, nunca a soma dos buckets de `groups`.
 */

export interface SalesTotalsCardsProps {
  summary: SalesSummaryResponse;
}

export function SalesTotalsCards({ summary }: SalesTotalsCardsProps) {
  const cards = [
    {
      id: 'salesCount',
      label: 'Vendas',
      value: summary.salesCount === undefined ? '—' : String(summary.salesCount),
    },
    {
      id: 'total',
      label: 'Faturamento',
      value: summary.total === undefined ? '—' : formatMoney(summary.total),
    },
    {
      id: 'ticketAverage',
      label: 'Ticket médio',
      value: summary.ticketAverage === undefined ? '—' : formatMoney(summary.ticketAverage),
    },
  ];

  return (
    <dl className="grid gap-3 sm:grid-cols-3">
      {cards.map((card) => (
        <div key={card.id} className="rounded-lg border border-line bg-surface p-4">
          <dt className="text-sm text-ink-muted">{card.label}</dt>
          <dd className="mt-1 text-2xl font-semibold text-ink">{card.value}</dd>
        </div>
      ))}
    </dl>
  );
}
