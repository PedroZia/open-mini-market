import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { formatQuantity } from '../../../shared/lib/quantity';
import { NoPermission } from '../../../shared/ui/NoPermission';
import { StockAdjustmentModal } from '../components/StockAdjustmentModal';
import { LowStockBadge } from '../components/LowStockBadge';
import { StockMovementsTable } from '../components/StockMovementsTable';
import { StockReceiptModal } from '../components/StockReceiptModal';
import { useStockDetail } from '../hooks/useStock';

/**
 * Detalhe do estoque do produto (1206a/1206b): o resumo do saldo, os movimentos embutidos do ledger
 * (`GET /stock/{productId}`) e as ações de ajuste (`stock.adjust`) e entrada (`stock.receive`). Só a
 * permissão que o servidor exige mostra a ação — quem manda é o backend, a tela só evita frustrar
 * quem clicaria e levaria 403.
 *
 * Nenhum valor é recalculado aqui (BR-12): saldo, mínimo, `lowStock`, delta e `balanceAfter` vêm do
 * servidor; os modais mostram o saldo atual e nunca projetam o saldo novo.
 */

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const actionButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 motion-reduce:transition-none';

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
  const canAdjust = usePermission('stock.adjust');
  const canReceive = usePermission('stock.receive');
  const [adjusting, setAdjusting] = useState(false);
  const [receiving, setReceiving] = useState(false);

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
  const movements = detail?.movements ?? [];

  return (
    <section
      aria-labelledby="titulo-estoque-detalhe"
      className="mx-auto flex max-w-5xl flex-col gap-6"
    >
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <Link
            to="/stock"
            className="text-sm font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none"
          >
            ← Voltar para o estoque
          </Link>
          <h1 id="titulo-estoque-detalhe" className="text-2xl font-semibold">
            {detail?.name ?? 'Estoque'}
          </h1>
          <p className="text-sm text-ink-muted">Saldo do produto, movimentos recentes e correções.</p>
        </div>

        {/* As ações só existem com o detalhe na mão e a permissão do servidor. */}
        {detail !== undefined && (canAdjust || canReceive) ? (
          <div className="flex flex-wrap gap-2">
            {canAdjust ? (
              <button
                type="button"
                onClick={() => setAdjusting(true)}
                className={actionButtonClassName}
              >
                Ajustar
              </button>
            ) : null}
            {canReceive ? (
              <button
                type="button"
                onClick={() => setReceiving(true)}
                className={actionButtonClassName}
              >
                Entrada
              </button>
            ) : null}
          </div>
        ) : null}
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

          <section aria-labelledby="titulo-movimentos" className="flex flex-col gap-3">
            <h2 id="titulo-movimentos" className="text-lg font-semibold">
              Movimentos
            </h2>
            <StockMovementsTable movements={movements} />
          </section>
        </>
      )}

      {/* Os modais vivem só enquanto abertos: fechar descarta o rascunho e o erro do servidor. */}
      {detail !== undefined && adjusting ? (
        <StockAdjustmentModal
          productId={productId}
          productName={detail.name ?? 'produto'}
          currentQuantity={detail.quantity ?? 0}
          onClose={() => setAdjusting(false)}
        />
      ) : null}
      {detail !== undefined && receiving ? (
        <StockReceiptModal
          productId={productId}
          productName={detail.name ?? 'produto'}
          currentQuantity={detail.quantity ?? 0}
          onClose={() => setReceiving(false)}
        />
      ) : null}
    </section>
  );
}
