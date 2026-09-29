import { Link, useParams } from 'react-router';
import type { ReactNode } from 'react';
import { formatDateTime } from '../../../shared/lib/datetime';
import { formatMoney } from '../../../shared/lib/money';
import { errorMessage, isCashSessionNotOpen, isForbidden } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { CashRegisterResponse } from '../api/cashApi';
import { CashMovementTotals } from '../components/CashMovementTotals';
import { CashSessionStatusBadge } from '../components/CashSessionStatusBadge';
import { useCashRegisters, useCurrentCashSession } from '../hooks/useCash';

/**
 * Detalhe do caixa (1210a): a sessão em andamento (`GET /{id}/current-session`) com abertura,
 * operador, esperado e os totais por tipo de movimento, e o caminho para a sessão completa.
 *
 * Caixa sem sessão aberta é 404 `CASH_SESSION_NOT_OPEN` — estado normal do caixa, não falha: a tela
 * mostra "Sem sessão aberta" (nunca um erro) e deixa atualizar quando a sessão abrir. O código e o
 * nome do caixa vêm da lista (`GET /cash-registers`), que é barata e já está no cache; o operador é
 * o do servidor (`operatorName`) e, sem ele, sobra o id curto — a tela não inventa nome.
 *
 * Nenhum valor é recalculado aqui (BR-12): abertura, esperado e totais são o que o servidor somou.
 */

const backLinkClassName =
  'text-sm font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none';

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const sessionLinkClassName =
  'text-sm font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none';

/** Uma linha do resumo: rótulo e valor de um campo que o servidor devolveu. */
function SummaryItem({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-sm font-medium text-ink-muted">{label}</dt>
      <dd className="text-lg text-ink">{children}</dd>
    </div>
  );
}

/** O contrato promete `undefined`, mas campo ausente chega como `null` na resposta JSON. */
type Nullable<T> = T | null | undefined;

/** Decimal do servidor em pt-BR; ausente vira traço, nunca zero inventado (BR-12). */
function money(value: Nullable<number>): string {
  return value === undefined || value === null ? '—' : formatMoney(value);
}

/** Instante do servidor convertido para pt-BR na apresentação; ausente vira traço. */
function moment(value: Nullable<string>): string {
  return value === undefined || value === null ? '—' : formatDateTime(value);
}

/** Id curto de um UUID do contrato; ausente vira traço. */
function shortId(value: Nullable<string>): string {
  return value === undefined || value === null || value === '' ? '—' : value.slice(0, 8);
}

/** Título pelo código do caixa (como no detalhe da venda); sem a lista, sobra "Caixa". */
function registerHeading(register: CashRegisterResponse | undefined): string {
  const code = register?.code;
  return code === undefined || code === '' ? 'Caixa' : `Caixa ${code}`;
}

/**
 * Operador da sessão aberta: o nome que a lista já traz (`operatorName`); sem ele, o id curto de
 * quem abriu — a tela não resolve usuário nem inventa nome.
 */
function operatorLabel(
  register: CashRegisterResponse | undefined,
  openedByUserId: Nullable<string>,
): string {
  const name = register?.operatorName;
  if (name !== undefined && name !== null && name !== '') {
    return name;
  }
  return shortId(openedByUserId);
}

export function CashRegisterDetailPage() {
  const cashRegisterId = useParams().id ?? '';
  const registers = useCashRegisters();
  const session = useCurrentCashSession(cashRegisterId);
  const register = registers.data?.find((candidate) => candidate.id === cashRegisterId);

  // Leitura negada (403) vira estado próprio, não tela em branco (§10.3).
  if (isForbidden(session.error)) {
    return (
      <section
        aria-labelledby="titulo-caixa-detalhe"
        className="mx-auto flex max-w-5xl flex-col gap-6"
      >
        <h1 id="titulo-caixa-detalhe" className="text-2xl font-semibold">
          Caixa
        </h1>
        <NoPermission
          onRetry={() => {
            void session.refetch();
          }}
        />
      </section>
    );
  }

  const failed = session.error !== undefined && session.error !== null;
  const noSession = isCashSessionNotOpen(session.error);
  const open = session.data;

  return (
    <section
      aria-labelledby="titulo-caixa-detalhe"
      className="mx-auto flex max-w-5xl flex-col gap-6"
    >
      <header className="flex flex-col gap-1">
        <Link to="/cash-registers" className={backLinkClassName}>
          ← Voltar para caixas
        </Link>
        <h1 id="titulo-caixa-detalhe" className="text-2xl font-semibold">
          {registerHeading(register)}
        </h1>
        <p className="text-sm text-ink-muted">
          {register?.name ?? 'Sessão atual do caixa.'}
        </p>
      </header>

      {open === undefined ? (
        noSession ? (
          <section
            aria-labelledby="titulo-sem-sessao"
            className="flex flex-col items-start gap-3 rounded-lg border border-line bg-surface p-6"
          >
            <h2 id="titulo-sem-sessao" className="text-lg font-semibold">
              Sem sessão aberta
            </h2>
            <p className="text-sm text-ink-muted">
              Este caixa não tem sessão em andamento. A abertura é feita no terminal do caixa.
            </p>
            <button
              type="button"
              onClick={() => {
                void session.refetch();
              }}
              className={retryButtonClassName}
            >
              Atualizar
            </button>
          </section>
        ) : failed ? (
          <div role="alert" className="flex flex-col items-start gap-3 text-sm">
            <p className="font-medium text-danger">{errorMessage(session.error)}</p>
            <button
              type="button"
              onClick={() => {
                void session.refetch();
              }}
              className={retryButtonClassName}
            >
              Tentar de novo
            </button>
          </div>
        ) : (
          <p role="status" className="text-sm text-ink-muted">
            Carregando…
          </p>
        )
      ) : (
        <>
          {failed ? (
            <p
              role="alert"
              className="flex flex-wrap items-center gap-3 text-sm font-medium text-danger"
            >
              {errorMessage(session.error)}
              <button
                type="button"
                onClick={() => {
                  void session.refetch();
                }}
                className={retryButtonClassName}
              >
                Tentar de novo
              </button>
            </p>
          ) : null}

          <dl className="grid gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2 lg:grid-cols-3">
            <SummaryItem label="Situação">
              <CashSessionStatusBadge status={open.status} />
            </SummaryItem>
            <SummaryItem label="Aberta em">{moment(open.openedAt)}</SummaryItem>
            <SummaryItem label="Operador">
              <span title={open.openedByUserId ?? undefined}>
                {operatorLabel(register, open.openedByUserId)}
              </span>
            </SummaryItem>
            <SummaryItem label="Abertura">{money(open.openingAmount)}</SummaryItem>
            <SummaryItem label="Esperado">{money(open.expectedAmount)}</SummaryItem>
          </dl>

          <section aria-labelledby="titulo-totais-tipo" className="flex flex-col gap-3">
            <h2 id="titulo-totais-tipo" className="text-lg font-semibold">
              Totais por tipo de movimento
            </h2>
            <CashMovementTotals totals={open.totalsByType} />
          </section>

          {open.sessionId === undefined ? null : (
            <p>
              <Link to={`/cash-sessions/${open.sessionId}`} className={sessionLinkClassName}>
                Ver sessão e resumo →
              </Link>
            </p>
          )}
        </>
      )}
    </section>
  );
}
