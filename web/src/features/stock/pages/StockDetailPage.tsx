import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { formatQuantity } from '../../../shared/lib/quantity';
import { NoPermission } from '../../../shared/ui/NoPermission';
import { LowStockBadge } from '../components/LowStockBadge';
import { useStockDetail } from '../hooks/useStock';

/**
 * Detalhe do estoque do produto (1206a): o resumo do saldo, com o selo de estoque baixo e o
 * caminho de volta para a lista. Os movimentos do ledger e as ações de ajuste/entrada são o 1206b —
 * a consulta é a mesma (`GET /stock/{productId}`), então este passo não antecipa nada daquele.
 *
 * Nenhum valor é recalculado aqui (BR-12): saldo, mínimo e o próprio `lowStock` vêm do servidor.
 */

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

/** Uma linha do resumo: rótulo e valor de um campo que o servidor devolveu. */
function SummaryItem({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-sm font-medium text-ink-muted">{label}</dt>
      <dd className="text-lg text-ink">{children}</dd>
    </div>
  );
}

export function StockDetailPage() {
  const productId = useParams().productId ?? '';
  const stock = useStockDetail(productId);

  // Leitura negada (403) vira estado próprio, não tela em branco (§10.3).
  if (isForbidden(stock.error)) {
    return (
      <section
        aria-labelledby="titulo-estoque-detalhe"
        className="mx-auto flex max-w-3xl flex-col gap-6"
      >
        <h1 id="titulo-estoque-detalhe" className="text-2xl font-semibold">
          Estoque
        </h1>
        <NoPermission
          onRetry={() => {
            void stock.refetch();
          }}
        />
      </section>
    );
  }

  const failed = stock.error !== undefined && stock.error !== null;
  const detail = stock.data;
  // O servidor manda `null` quando o produto não tem mínimo; o contrato só promete `number`.
  const minQuantity: number | null | undefined = detail?.minQuantity;

  return (
    <section
      aria-labelledby="titulo-estoque-detalhe"
      className="mx-auto flex max-w-3xl flex-col gap-6"
    >
      <header className="flex flex-col gap-1">
        <Link
          to="/stock"
          className="text-sm font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none"
        >
          ← Voltar para o estoque
        </Link>
        <h1 id="titulo-estoque-detalhe" className="text-2xl font-semibold">
          {detail?.name ?? 'Estoque'}
        </h1>
        <p className="text-sm text-ink-muted">Saldo do produto e configuração mínima.</p>
      </header>

      {failed && detail === undefined ? (
        <div role="alert" className="flex flex-col items-start gap-3 text-sm">
          <p className="font-medium text-danger">{errorMessage(stock.error)}</p>
          <button
            type="button"
            onClick={() => {
              void stock.refetch();
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
              {errorMessage(stock.error)}
              <button
                type="button"
                onClick={() => {
                  void stock.refetch();
                }}
                className={retryButtonClassName}
              >
                Tentar de novo
              </button>
            </p>
          ) : null}

          <dl className="grid gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2">
            <SummaryItem label="Código de barras">{detail.barcode ?? '—'}</SummaryItem>
            <SummaryItem label="Unidade">{detail.unit ?? '—'}</SummaryItem>
            <SummaryItem label="Saldo">
              <span className="flex flex-wrap items-center gap-2">
                {formatQuantity(detail.quantity ?? 0)}
                {detail.lowStock === true ? <LowStockBadge /> : null}
              </span>
            </SummaryItem>
            <SummaryItem label="Mínimo">
              {minQuantity === undefined || minQuantity === null ? '—' : formatQuantity(minQuantity)}
            </SummaryItem>
          </dl>
        </>
      )}
    </section>
  );
}
