import { useState } from 'react';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { CategoryResponse } from '../api/categoriesApi';
import { CategoryFormModal } from '../components/CategoryFormModal';
import { useCategories, useDeactivateCategory } from '../hooks/useCategories';

/**
 * Gestão de categorias (1205): `GET /categories` é array simples (§9.3), então a lista é uma tabela
 * própria da feature — o `DataTable` é de paginação de servidor e aqui não há página para pedir.
 *
 * Cadastro e edição abrem no mesmo modal; desativar é `DELETE` (soft delete, 204). Categoria já
 * desativada não oferece "Desativar": o servidor responde 404 para ela (repetir o DELETE não é
 * operação válida) — editar continua valendo, porque desativar não apaga a linha.
 *
 * Só `category.write` vê as ações de escrita; a leitura é `product.read`, a mesma que o filtro de
 * produtos usa, e um 403 nela vira o estado "sem permissão", nunca tabela vazia.
 */

const actionButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50 motion-reduce:transition-none';

const headerCellClassName = 'px-3 py-3 font-medium';

const bodyCellClassName = 'px-3 py-2 align-top text-ink';

export function CategoriesPage() {
  const canWrite = usePermission('category.write');
  const categories = useCategories();
  const deactivate = useDeactivateCategory();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<CategoryResponse | null>(null);

  // Leitura negada (403) vira estado próprio, não tabela vazia (§10.3).
  if (isForbidden(categories.error)) {
    return (
      <section aria-labelledby="titulo-categorias" className="mx-auto flex max-w-4xl flex-col gap-6">
        <h1 id="titulo-categorias" className="text-2xl font-semibold">
          Categorias
        </h1>
        <NoPermission
          onRetry={() => {
            void categories.refetch();
          }}
        />
      </section>
    );
  }

  const rows = categories.data ?? [];

  // Nome do pai a partir da própria lista: o recurso só guarda o id (§9.3).
  const names = new Map<string, string>();
  for (const category of rows) {
    if (category.id !== undefined) {
      names.set(category.id, category.name ?? category.id);
    }
  }

  const failed = categories.error !== null && categories.error !== undefined;

  return (
    <section aria-labelledby="titulo-categorias" className="mx-auto flex max-w-4xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 id="titulo-categorias" className="text-2xl font-semibold">
            Categorias
          </h1>
          <p className="text-sm text-ink-muted">
            Organize o catálogo: crie, renomeie e desative categorias.
          </p>
        </div>

        {canWrite ? (
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 motion-reduce:transition-none"
          >
            Nova categoria
          </button>
        ) : null}
      </header>

      {failed && rows.length > 0 ? (
        <p role="alert" className="flex flex-wrap items-center gap-3 text-sm font-medium text-danger">
          {errorMessage(categories.error)}
          <button
            type="button"
            onClick={() => {
              void categories.refetch();
            }}
            className={actionButtonClassName}
          >
            Tentar de novo
          </button>
        </p>
      ) : null}

      <div className="overflow-x-auto rounded-lg border border-line bg-surface">
        <table
          aria-label="Categorias"
          aria-busy={categories.isPending || undefined}
          className="w-full text-left text-sm"
        >
          <thead className="border-b border-line bg-canvas text-ink-muted">
            <tr>
              <th scope="col" className={headerCellClassName}>
                Nome
              </th>
              <th scope="col" className={headerCellClassName}>
                Categoria pai
              </th>
              <th scope="col" className={headerCellClassName}>
                Ordem
              </th>
              <th scope="col" className={headerCellClassName}>
                Situação
              </th>
              {canWrite ? (
                <th scope="col" className={headerCellClassName}>
                  Ações
                </th>
              ) : null}
            </tr>
          </thead>

          <tbody className="divide-y divide-line">
            {rows.map((category) => {
              const id = category.id;
              if (id === undefined) {
                return null;
              }
              const name = category.name ?? id;
              const active = category.active !== false;

              return (
                <tr key={id}>
                  <td className={`${bodyCellClassName} font-medium`}>{name}</td>
                  <td className={`${bodyCellClassName} text-ink-muted`}>
                    {category.parentId === undefined ? '—' : (names.get(category.parentId) ?? '—')}
                  </td>
                  <td className={`${bodyCellClassName} text-ink-muted`}>
                    {category.sortOrder ?? 0}
                  </td>
                  <td className={bodyCellClassName}>{active ? 'Ativa' : 'Desativada'}</td>
                  {canWrite ? (
                    <td className={bodyCellClassName}>
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          aria-label={`Editar ${name}`}
                          onClick={() => setEditing(category)}
                          className={actionButtonClassName}
                        >
                          Editar
                        </button>
                        {active ? (
                          <button
                            type="button"
                            aria-label={`Desativar ${name}`}
                            disabled={deactivate.isPending}
                            onClick={() => deactivate.mutate(id)}
                            className={actionButtonClassName}
                          >
                            Desativar
                          </button>
                        ) : null}
                      </div>
                    </td>
                  ) : null}
                </tr>
              );
            })}

            {rows.length === 0 ? (
              <tr>
                <td colSpan={canWrite ? 5 : 4} className="px-3 py-6 text-center">
                  {categories.isPending ? (
                    <p role="status" className="text-sm text-ink-muted">
                      Carregando…
                    </p>
                  ) : failed ? (
                    <div role="alert" className="flex flex-col items-center gap-3 text-sm">
                      <p className="font-medium text-danger">{errorMessage(categories.error)}</p>
                      <button
                        type="button"
                        onClick={() => {
                          void categories.refetch();
                        }}
                        className={actionButtonClassName}
                      >
                        Tentar de novo
                      </button>
                    </div>
                  ) : (
                    <p className="text-sm text-ink-muted">Nenhuma categoria cadastrada.</p>
                  )}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>

      {/* Os modais vivem só enquanto abertos: fechar descarta o rascunho e o erro do servidor. */}
      {creating ? <CategoryFormModal categories={rows} onClose={() => setCreating(false)} /> : null}
      {editing !== null ? (
        <CategoryFormModal
          category={editing}
          categories={rows}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </section>
  );
}
