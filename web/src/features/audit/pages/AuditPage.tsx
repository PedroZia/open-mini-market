import { useState } from 'react';
import { formatDateTime, localDateTimeToInstant } from '../../../shared/lib/datetime';
import { normalizePage, sortParam, type Sort } from '../../../shared/lib/page';
import { isForbidden } from '../../../shared/lib/problem';
import { DataTable, type DataTableColumn } from '../../../shared/ui/DataTable';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { AuditEventResponse, OperationSource } from '../api/auditApi';
import { useAuditEvents } from '../hooks/useAuditEvents';
import { isUuid } from '../lib/uuid';

/**
 * Consulta do log de auditoria (1211a): filtros combináveis do `GET /audit-events` com paginação do
 * servidor. A leitura exige `audit.read` — quem não tem (OPERADOR) não vê o item na navegação e a
 * rota aberta na mão leva 403, que vira o estado "sem permissão" (§10.3), não tabela vazia.
 *
 * O instante é o único eixo ordenável do recurso: só a primeira coluna declara `sortKey`, com
 * estado inicial `occurredat,desc` (o default do servidor — a investigação lê o mais recente
 * primeiro). Os rótulos dos filtros são livres porque o catálogo de ações/entidades é do backend;
 * os exemplos do placeholder são códigos reais, nunca uma lista inventada. `details` fica de fora
 * nesta etapa: a leitura rica de antes/depois e o histórico por entidade são o 1211b.
 */

/** Tamanho de página da consulta, o mesmo default do servidor (§9.1). */
const PAGE_SIZE = 20;

/** Única chave de ordenação da whitelist do recurso. */
const OCCURRED_AT = 'occurredat';

// O foco visível é o global de `index.css` (azul, 2px): aqui não se sobrescreve anel nenhum.
const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none';

const clearButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

/** Origem da operação em pt-BR (a mesma trilha registra terminal, retaguarda e sistema). */
const SOURCE_LABELS: Record<OperationSource, string> = {
  API: 'API',
  TUI: 'TUI',
  WEB: 'Retaguarda',
  SYSTEM: 'Sistema',
};

/** Id curto quando a coluna não tem o nome do autor — nunca um nome inventado. */
function shortId(id: string): string {
  return id.slice(0, 8);
}

/** Autor do evento: username quando o log o tem, senão o id curto, senão um traço. */
function actorLabel(event: AuditEventResponse): string {
  if (event.actorUsername !== undefined && event.actorUsername !== '') {
    return event.actorUsername;
  }
  if (event.actorUserId !== undefined && event.actorUserId !== '') {
    return shortId(event.actorUserId);
  }
  return '—';
}

/** Entidade do evento: tipo + id curto quando houver — o par que identifica o alvo. */
function entityLabel(event: AuditEventResponse): string {
  const type = event.entityType === undefined || event.entityType === '' ? '—' : event.entityType;
  if (event.entityId === undefined || event.entityId === '') {
    return type;
  }
  return `${type} · ${shortId(event.entityId)}`;
}

/** Motivo informado pelo operador; ação sem motivo mostra um traço. */
function reasonLabel(reason: string | undefined): string {
  return reason === undefined || reason === '' ? '—' : reason;
}

interface UuidFilterProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}

/**
 * Campo de filtro por UUID: avisa no próprio campo quando o texto não fecha com o formato e o
 * valor incompleto simplesmente não entra na query (o servidor recusaria com 400).
 */
function UuidFilter({ id, label, value, onChange }: UuidFilterProps) {
  const invalid = value.trim() !== '' && !isUuid(value);

  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <input
        id={id}
        type="text"
        value={value}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? `${id}-erro` : undefined}
        onChange={(event) => onChange(event.target.value)}
        className={fieldClassName}
      />
      {invalid ? (
        <p id={`${id}-erro`} role="alert" className="text-xs font-medium text-danger">
          Informe um UUID válido.
        </p>
      ) : null}
    </div>
  );
}

export function AuditPage() {
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [actorUserId, setActorUserId] = useState('');
  const [action, setAction] = useState('');
  const [cashSessionId, setCashSessionId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sort, setSort] = useState<Sort>({ field: OCCURRED_AT, direction: 'desc' });
  const [page, setPage] = useState(0);

  const audit = useAuditEvents({
    entityType: entityType.trim() === '' ? undefined : entityType.trim(),
    entityId: isUuid(entityId) ? entityId.trim() : undefined,
    actorUserId: isUuid(actorUserId) ? actorUserId.trim() : undefined,
    action: action.trim() === '' ? undefined : action.trim(),
    cashSessionId: isUuid(cashSessionId) ? cashSessionId.trim() : undefined,
    from: localDateTimeToInstant(from),
    to: localDateTimeToInstant(to),
    sort: sortParam(sort) ?? undefined,
    page,
    size: PAGE_SIZE,
  });

  function clearFilters(): void {
    setEntityType('');
    setEntityId('');
    setActorUserId('');
    setAction('');
    setCashSessionId('');
    setFrom('');
    setTo('');
    setPage(0);
  }

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(audit.error)) {
    return (
      <section aria-labelledby="titulo-auditoria" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-auditoria" className="text-2xl font-semibold">
          Auditoria
        </h1>
        <NoPermission
          onRetry={() => {
            void audit.refetch();
          }}
        />
      </section>
    );
  }

  const columns: DataTableColumn<AuditEventResponse>[] = [
    {
      id: 'occurredAt',
      header: 'Instante',
      sortKey: OCCURRED_AT,
      render: (event) => (event.occurredAt === undefined ? '—' : formatDateTime(event.occurredAt)),
    },
    {
      id: 'action',
      header: 'Ação',
      render: (event) => event.action ?? '—',
    },
    {
      id: 'actor',
      header: 'Autor',
      render: actorLabel,
    },
    {
      id: 'entity',
      header: 'Entidade',
      render: entityLabel,
    },
    {
      id: 'source',
      header: 'Origem',
      render: (event) => (event.source === undefined ? '—' : SOURCE_LABELS[event.source]),
    },
    {
      id: 'reason',
      header: 'Motivo',
      render: (event) => reasonLabel(event.reason),
    },
  ];

  return (
    <section aria-labelledby="titulo-auditoria" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-auditoria" className="text-2xl font-semibold">
          Auditoria
        </h1>
        <p className="text-sm text-ink-muted">
          Investigue as operações registradas: quem fez, o quê, quando e por quê.
        </p>
      </header>

      <div className="grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2 lg:grid-cols-3">
        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-tipo-entidade" className="text-sm font-medium text-ink">
            Tipo da entidade
          </label>
          <input
            id="filtro-tipo-entidade"
            type="text"
            value={entityType}
            placeholder="Ex.: SALE"
            onChange={(event) => {
              setEntityType(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-acao" className="text-sm font-medium text-ink">
            Ação
          </label>
          <input
            id="filtro-acao"
            type="text"
            value={action}
            placeholder="Ex.: SALE_COMPLETED"
            onChange={(event) => {
              setAction(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <UuidFilter
          id="filtro-entidade"
          label="ID da entidade"
          value={entityId}
          onChange={(value) => {
            setEntityId(value);
            setPage(0);
          }}
        />

        <UuidFilter
          id="filtro-autor"
          label="ID do autor"
          value={actorUserId}
          onChange={(value) => {
            setActorUserId(value);
            setPage(0);
          }}
        />

        <UuidFilter
          id="filtro-sessao-caixa"
          label="ID da sessão de caixa"
          value={cashSessionId}
          onChange={(value) => {
            setCashSessionId(value);
            setPage(0);
          }}
        />

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-inicio" className="text-sm font-medium text-ink">
            De
          </label>
          <input
            id="filtro-inicio"
            type="datetime-local"
            value={from}
            onChange={(event) => {
              setFrom(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="filtro-fim" className="text-sm font-medium text-ink">
            Até (não inclui)
          </label>
          <input
            id="filtro-fim"
            type="datetime-local"
            value={to}
            onChange={(event) => {
              setTo(event.target.value);
              setPage(0);
            }}
            className={fieldClassName}
          />
        </div>

        <div className="flex items-end">
          <button type="button" onClick={clearFilters} className={clearButtonClassName}>
            Limpar filtros
          </button>
        </div>
      </div>

      <DataTable
        label="Auditoria"
        columns={columns}
        rowKey={(event) => (event.id === undefined ? '' : String(event.id))}
        page={page}
        data={audit.data === undefined ? null : normalizePage(audit.data)}
        loading={audit.isPending || audit.isFetching}
        error={audit.error}
        sort={sort}
        onSortChange={(next) => {
          setSort(next);
          setPage(0);
        }}
        onPageChange={setPage}
        onRetry={() => {
          void audit.refetch();
        }}
        emptyMessage="Nenhum evento encontrado para os filtros."
      />
    </section>
  );
}
