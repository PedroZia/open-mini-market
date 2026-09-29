import type { CashSessionStatus } from '../api/cashApi';

/**
 * Situação do caixa/sessão (1210a): só desenha o `status` que o servidor devolveu — a tela não
 * interpreta estado nem prevê transição (§10.3). Rótulo em pt-BR e cores do tema.
 */

const STATUS_LABELS: Record<CashSessionStatus, string> = {
  OPEN: 'Aberto',
  CLOSED: 'Fechado',
};

const STATUS_CLASSES: Record<CashSessionStatus, string> = {
  OPEN: 'border-success text-success',
  CLOSED: 'border-line text-ink-muted',
};

export interface CashSessionStatusBadgeProps {
  /** Status do contrato; ausente vira traço, nunca célula vazia. */
  status: CashSessionStatus | undefined;
}

export function CashSessionStatusBadge({ status }: CashSessionStatusBadgeProps) {
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
