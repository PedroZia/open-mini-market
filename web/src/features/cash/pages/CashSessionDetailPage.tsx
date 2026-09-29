import { Link, useParams } from 'react-router';
import type { ReactNode } from 'react';
import { formatDateTime } from '../../../shared/lib/datetime';
import { formatMoney } from '../../../shared/lib/money';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { CashSessionDetailResponse } from '../api/cashApi';
import { CashSessionStatusBadge } from '../components/CashSessionStatusBadge';
import { CashSessionSummary } from '../components/CashSessionSummary';
import { useCashSession, useCashSessionSummary } from '../hooks/useCash';

/**
 * Sessão de caixa (1210a): o detalhe (`GET /cash-sessions/{id}`) com abertura e fechamento e o
 * resumo (`GET /{id}/summary`) com esperado × contado, a diferença, os totais por tipo de movimento
 * e as vendas por forma de pagamento. Todos os valores são os do servidor — nada é somado aqui
 * (BR-12) e o contado/diferença aparecem como traço enquanto a sessão está aberta.
 *
 * As duas leituras exigem `cash.read`: 403 vira o estado "sem permissão", nunca tela em branco.
 * Operador e caixa aparecem pelo id curto — o contrato não traz nome nestas respostas.
 */

const backLinkClassName =
  'text-sm font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none';

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const linkClassName =
  'text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none';

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

/** Id curto de um UUID do contrato; ausente vira traço — o id completo fica no `title`. */
function shortId(value: Nullable<string>): string {
  return value === undefined || value === null || value === '' ? '—' : value.slice(0, 8);
}

/** `true` quando o campo opcional veio preenchido (vazio e `null` contam como ausente). */
function present(value: Nullable<string>): boolean {
  return value !== undefined && value !== null && value !== '';
}

/** O caminho de volta: o caixa da sessão quando o detalhe já chegou; senão, a lista de caixas. */
function backTarget(detail: CashSessionDetailResponse | undefined): string {
  return detail?.cashRegisterId === undefined
    ? '/cash-registers'
    : `/cash-registers/${detail.cashRegisterId}`;
}

export function CashSessionDetailPage() {
  const sessionId = useParams().id ?? '';
  const session = useCashSession(sessionId);
  const summary = useCashSessionSummary(sessionId);

  // Leitura negada (403) vira estado próprio, não tela em branco (§10.3).
  if (isForbidden(session.error) || isForbidden(summary.error)) {
    return (
      <section
        aria-labelledby="titulo-sessao-caixa"
        className="mx-auto flex max-w-5xl flex-col gap-6"
      >
        <h1 id="titulo-sessao-caixa" className="text-2xl font-semibold">
          Sessão de caixa
        </h1>
        <NoPermission
          onRetry={() => {
            void session.refetch();
            void summary.refetch();
          }}
        />
      </section>
    );
  }

  const failed = session.error !== undefined && session.error !== null;
  const summaryFailed = summary.error !== undefined && summary.error !== null;
  const detail = session.data;

  return (
    <section
      aria-labelledby="titulo-sessao-caixa"
      className="mx-auto flex max-w-5xl flex-col gap-6"
    >
      <header className="flex flex-col gap-1">
        <Link to={backTarget(detail)} className={backLinkClassName}>
          ← Voltar para caixas
        </Link>
        <h1 id="titulo-sessao-caixa" className="text-2xl font-semibold">
          Sessão de caixa
        </h1>
        <p className="text-sm text-ink-muted">Abertura, conferência e movimentos da sessão.</p>
      </header>

      {failed && detail === undefined ? (
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
      ) : detail === undefined ? (
        <p role="status" className="text-sm text-ink-muted">
          Carregando…
        </p>
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
              <CashSessionStatusBadge status={detail.status} />
            </SummaryItem>
            <SummaryItem label="Caixa">
              {detail.cashRegisterId === undefined ? (
                '—'
              ) : (
                <Link
                  to={`/cash-registers/${detail.cashRegisterId}`}
                  className={linkClassName}
                  title={detail.cashRegisterId}
                >
                  {shortId(detail.cashRegisterId)}
                </Link>
              )}
            </SummaryItem>
            <SummaryItem label="Aberta em">{moment(detail.openedAt)}</SummaryItem>
            <SummaryItem label="Operador">
              <span title={detail.openedByUserId ?? undefined}>
                {shortId(detail.openedByUserId)}
              </span>
            </SummaryItem>
            <SummaryItem label="Abertura">{money(detail.openingAmount)}</SummaryItem>
            {present(detail.closedAt) ? (
              <SummaryItem label="Fechada em">{moment(detail.closedAt)}</SummaryItem>
            ) : null}
            {present(detail.closedByUserId) ? (
              <SummaryItem label="Fechada por">
                <span title={detail.closedByUserId ?? undefined}>
                  {shortId(detail.closedByUserId)}
                </span>
              </SummaryItem>
            ) : null}
          </dl>

          {present(detail.closingNotes) ? (
            <p className="rounded-lg border border-line bg-surface p-4 text-sm text-ink">
              <span className="font-medium">Observações do fechamento: </span>
              {detail.closingNotes}
            </p>
          ) : null}

          {summary.data === undefined ? (
            summaryFailed ? (
              <div role="alert" className="flex flex-col items-start gap-3 text-sm">
                <p className="font-medium text-danger">{errorMessage(summary.error)}</p>
                <button
                  type="button"
                  onClick={() => {
                    void summary.refetch();
                  }}
                  className={retryButtonClassName}
                >
                  Tentar de novo
                </button>
              </div>
            ) : (
              <p role="status" className="text-sm text-ink-muted">
                Carregando o resumo…
              </p>
            )
          ) : (
            <>
              {summaryFailed ? (
                <p
                  role="alert"
                  className="flex flex-wrap items-center gap-3 text-sm font-medium text-danger"
                >
                  {errorMessage(summary.error)}
                  <button
                    type="button"
                    onClick={() => {
                      void summary.refetch();
                    }}
                    className={retryButtonClassName}
                  >
                    Tentar de novo
                  </button>
                </p>
              ) : null}
              <CashSessionSummary summary={summary.data} />
            </>
          )}
        </>
      )}
    </section>
  );
}
