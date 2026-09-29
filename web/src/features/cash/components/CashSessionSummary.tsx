import type { ReactNode } from 'react';
import { formatMoney } from '../../../shared/lib/money';
import type { CashSessionSummaryResponse } from '../api/cashApi';
import { CashMovementTotals } from './CashMovementTotals';
import { CashPaymentsByMethod } from './CashPaymentsByMethod';

/**
 * Resumo do fechamento da sessão (1210a): esperado × contado com a diferença, os totais por tipo de
 * movimento e as vendas por forma de pagamento — tudo como o servidor devolveu (BR-12). O contado e
 * a diferença são nulos enquanto a sessão está aberta: a tela mostra traço, nunca zero inventado.
 */

/** O contrato promete `undefined`, mas campo ausente chega como `null` na resposta JSON. */
type Nullable<T> = T | null | undefined;

/** Uma linha do resumo: rótulo e valor de um campo que o servidor devolveu. */
function SummaryItem({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-sm font-medium text-ink-muted">{label}</dt>
      <dd className="text-lg text-ink">{children}</dd>
    </div>
  );
}

/** Decimal do servidor em pt-BR; ausente vira traço. */
function money(value: Nullable<number>): string {
  return value === undefined || value === null ? '—' : formatMoney(value);
}

/** Diferença com o lado em palavras — cor sozinha não diz "sobra" nem "falta" para todos. */
function difference(value: Nullable<number>): ReactNode {
  if (value === undefined || value === null) {
    return '—';
  }
  const side = value > 0 ? 'Sobra' : value < 0 ? 'Falta' : 'Confere';
  return (
    <>
      {formatMoney(value)}
      <span className="block text-xs font-normal text-ink-muted">{side}</span>
    </>
  );
}

export interface CashSessionSummaryProps {
  /** Resumo como `GET /cash-sessions/{id}/summary` o devolve. */
  summary: CashSessionSummaryResponse;
}

export function CashSessionSummary({ summary }: CashSessionSummaryProps) {
  return (
    <section aria-labelledby="titulo-resumo" className="flex flex-col gap-4">
      <h2 id="titulo-resumo" className="text-lg font-semibold">
        Resumo
      </h2>

      <dl className="grid gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-3">
        <SummaryItem label="Esperado">{money(summary.expectedAmount)}</SummaryItem>
        <SummaryItem label="Contado">{money(summary.countedAmount)}</SummaryItem>
        <SummaryItem label="Diferença">{difference(summary.differenceAmount)}</SummaryItem>
      </dl>

      <section aria-labelledby="titulo-totais-tipo" className="flex flex-col gap-3">
        <h3 id="titulo-totais-tipo" className="text-base font-semibold">
          Totais por tipo de movimento
        </h3>
        <CashMovementTotals totals={summary.totalsByType} />
      </section>

      <section aria-labelledby="titulo-pagamentos-metodo" className="flex flex-col gap-3">
        <h3 id="titulo-pagamentos-metodo" className="text-base font-semibold">
          Vendas por forma de pagamento
        </h3>
        <CashPaymentsByMethod payments={summary.paymentsByMethod} />
      </section>
    </section>
  );
}
