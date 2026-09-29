import { formatDateTime } from '../../../shared/lib/datetime';
import { Modal } from '../../../shared/ui/Modal';
import type { AuditEventResponse } from '../api/auditApi';
import { isUuid } from '../lib/uuid';
import { DetailsView } from './DetailsView';

/**
 * Detalhes de um evento do log (1211b), no `Modal` compartilhado: `role="dialog"` com Esc, foco
 * preso no painel e devolvido ao gatilho — a navegação por teclado da consulta continua valendo.
 *
 * O rodapé traz o atalho de histórico quando o evento aponta para uma entidade do contrato
 * (`entityType` + `entityId` em UUID, que é o formato aceito pelo filtro): a ação não consulta nada
 * aqui — quem fixa o par nos filtros é a página. Evento sem detalhes ainda abre: mostra "Sem
 * detalhes" e, se houver entidade, o mesmo atalho.
 */

export interface AuditEventDetailsModalProps {
  event: AuditEventResponse;
  /** Fixa o par entidade nos filtros da consulta — a linha do tempo daquele registro. */
  onViewHistory: (entityType: string, entityId: string) => void;
  onClose: () => void;
}

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 motion-reduce:transition-none';

/** Par do evento quando existe e cabe no filtro; sem ele não há histórico a abrir. */
function historyTarget(event: AuditEventResponse): { entityType: string; entityId: string } | null {
  const entityType = event.entityType?.trim() ?? '';
  const entityId = event.entityId?.trim() ?? '';
  return entityType === '' || !isUuid(entityId) ? null : { entityType, entityId };
}

/** Linha de contexto do cabeçalho: a ação e o instante que a tabela já mostram. */
function summary(event: AuditEventResponse): string {
  const parts = [event.action === undefined || event.action === '' ? 'Evento' : event.action];
  if (event.occurredAt !== undefined && event.occurredAt !== '') {
    parts.push(formatDateTime(event.occurredAt));
  }
  return parts.join(' · ');
}

export function AuditEventDetailsModal({
  event,
  onViewHistory,
  onClose,
}: AuditEventDetailsModalProps) {
  const target = historyTarget(event);

  return (
    <Modal
      open
      title="Detalhes do evento"
      onClose={onClose}
      footer={
        target === null ? undefined : (
          <button
            type="button"
            onClick={() => onViewHistory(target.entityType, target.entityId)}
            className={primaryButtonClassName}
          >
            Ver histórico
          </button>
        )
      }
    >
      <div className="flex flex-col gap-4">
        <p className="text-ink-muted">{summary(event)}</p>
        <DetailsView details={event.details} />
      </div>
    </Modal>
  );
}
