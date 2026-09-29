import type { SaleStatus } from '../api/salesApi';

/**
 * Situação da venda na lista (1209a): só desenha o `status` que o servidor devolveu — a tela não
 * interpreta estado nem prevê transição (§10.3). Rótulo em pt-BR e cores do tema.
 */

const STATUS_LABELS: Record<SaleStatus, string> = {
  OPEN: 'Aberta',
  COMPLETED: 'Concluída',
  CANCELLED: 'Cancelada',
};

const STATUS_CLASSES: Record<SaleStatus, string> = {
  OPEN: 'border-line text-ink-muted',
  COMPLETED: 'border-success text-success',
  CANCELLED: 'border-danger text-danger',
};

export interface SaleStatusBadgeProps {
  /** Status do contrato; ausente ou fora da união vira traço, nunca célula vazia. */
  status: SaleStatus | undefined;
}

export function SaleStatusBadge({ status }: SaleStatusBadgeProps) {
  const label: string | undefined = status === undefined ? undefined : STATUS_LABELS[status];
  if (label === undefined || status === undefined) {
    return '—';
  }

  return (
    <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${STATUS_CLASSES[status]}`}>
      {label}
    </span>
  );
}
