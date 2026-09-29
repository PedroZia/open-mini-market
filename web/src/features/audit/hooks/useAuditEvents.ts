import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { listAuditEvents, type AuditEventQuery } from '../api/auditApi';

/** Prefixo do cache da consulta de auditoria. */
export const auditEventsQueryKey = ['audit-events'] as const;

/**
 * Página da consulta de auditoria do filtro corrente (1211a). `placeholderData: keepPreviousData`
 * mantém a página anterior na tela enquanto a nova chega — trocar página ou filtro não pisca o
 * estado de carregando; o `DataTable` marca a transição com "Atualizando…".
 */
export function useAuditEvents(query: AuditEventQuery) {
  return useQuery({
    queryKey: [...auditEventsQueryKey, query],
    queryFn: () => listAuditEvents(query),
    placeholderData: keepPreviousData,
  });
}
