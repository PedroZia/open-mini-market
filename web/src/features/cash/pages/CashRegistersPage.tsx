import { Link } from 'react-router';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import { CashSessionStatusBadge } from '../components/CashSessionStatusBadge';
import { useCashRegisters } from '../hooks/useCash';

/**
 * Caixas da loja (1210a): a resposta de `GET /cash-registers` é um array sem paginação — o §9.3 não
 * define `page`/`size` para a rota, então a tela não força o `DataTable` paginado. A lista mostra
 * código, nome, situação e operador da sessão aberta e leva ao detalhe do caixa.
 *
 * A leitura exige `cash.read` (403 vira o estado "sem permissão", nunca tabela vazia) e o operador
 * vem do próprio servidor (`operatorName` é nulo com o caixa fechado): a tela não resolve usuário
 * nem inventa nome.
 */

const retryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

const linkClassName =
  'font-medium text-brand underline underline-offset-2 transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none';

export function CashRegistersPage() {
  const registers = useCashRegisters();

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(registers.error)) {
    return (
      <section aria-labelledby="titulo-caixa" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-caixa" className="text-2xl font-semibold">
          Caixa
        </h1>
        <NoPermission
          onRetry={() => {
            void registers.refetch();
          }}
        />
      </section>
    );
  }

  const failed = registers.error !== undefined && registers.error !== null;
  const list = registers.data;

  return (
    <section aria-labelledby="titulo-caixa" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-caixa" className="text-2xl font-semibold">
          Caixa
        </h1>
        <p className="text-sm text-ink-muted">
          Caixas da loja, a situação de cada um e quem está na sessão aberta.
        </p>
      </header>

      {list === undefined ? (
        failed ? (
          <div role="alert" className="flex flex-col items-start gap-3 text-sm">
            <p className="font-medium text-danger">{errorMessage(registers.error)}</p>
            <button
              type="button"
              onClick={() => {
                void registers.refetch();
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
              {errorMessage(registers.error)}
              <button
                type="button"
                onClick={() => {
                  void registers.refetch();
                }}
                className={retryButtonClassName}
              >
                Tentar de novo
              </button>
            </p>
          ) : null}

          {list.length === 0 ? (
            <p className="text-sm text-ink-muted">Nenhum caixa cadastrado.</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-line bg-surface">
              <table aria-label="Caixas" className="w-full text-left text-sm">
                <thead className="border-b border-line bg-canvas text-ink-muted">
                  <tr>
                    <th scope="col" className={headerCellClassName}>
                      Código
                    </th>
                    <th scope="col" className={headerCellClassName}>
                      Nome
                    </th>
                    <th scope="col" className={headerCellClassName}>
                      Situação
                    </th>
                    <th scope="col" className={headerCellClassName}>
                      Operador
                    </th>
                  </tr>
                </thead>

                <tbody className="divide-y divide-line">
                  {list.map((register, index) => (
                    <tr key={register.id ?? `${register.code ?? 'caixa'}-${index}`}>
                      <td className={`${bodyCellClassName} whitespace-nowrap`}>
                        {register.id === undefined ? (
                          (register.code ?? '—')
                        ) : (
                          <Link to={`/cash-registers/${register.id}`} className={linkClassName}>
                            {register.code ?? '—'}
                          </Link>
                        )}
                      </td>
                      <td className={bodyCellClassName}>{register.name ?? '—'}</td>
                      <td className={bodyCellClassName}>
                        <CashSessionStatusBadge status={register.status} />
                      </td>
                      <td className={`${bodyCellClassName} text-ink-muted`}>
                        {register.operatorName ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
