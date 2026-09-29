import { useState } from 'react';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage, isForbidden } from '../../../shared/lib/problem';
import { NoPermission } from '../../../shared/ui/NoPermission';
import type { RoleResponse } from '../api/rolesApi';
import { RolePermissionsModal } from '../components/RolePermissionsModal';
import { useRoles } from '../hooks/useRoles';

/**
 * Papéis e permissões (1208b): lista o catálogo de `GET /roles` com as permissões atuais de cada
 * papel e abre a edição do mapa (`PUT /roles/{code}/permissions`) para quem tem `role.write`.
 *
 * O endpoint devolve array sem paginação (§9.3), então a tela usa lista de cartões, não o
 * `DataTable` — não há página, filtro nem ordenação de servidor para ele.
 *
 * Ler exige `user.read`, o mesmo código do item de navegação; sem `role.write` a leitura continua e
 * só a ação de editar desaparece. O servidor é quem manda (§10.3).
 */

const secondaryButtonClassName =
  'min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

/** Permissões do papel em chips; papel sem nenhuma não fica com célula vazia. */
function PermissionChips({ permissions }: { permissions: readonly string[] }) {
  if (permissions.length === 0) {
    return <p className="text-sm text-ink-muted">Sem permissões.</p>;
  }

  return (
    <ul aria-label="Permissões do papel" className="flex flex-wrap gap-1.5">
      {permissions.map((code) => (
        <li
          key={code}
          className="rounded-full border border-line bg-canvas px-2.5 py-0.5 font-mono text-xs text-ink"
        >
          {code}
        </li>
      ))}
    </ul>
  );
}

export function RolesPage() {
  const [editing, setEditing] = useState<RoleResponse | null>(null);
  const canWrite = usePermission('role.write');
  const roles = useRoles();

  // Leitura negada (403) vira estado próprio, não lista vazia (§10.3).
  if (isForbidden(roles.error)) {
    return (
      <section aria-labelledby="titulo-papeis" className="mx-auto flex max-w-6xl flex-col gap-6">
        <h1 id="titulo-papeis" className="text-2xl font-semibold">
          Papéis
        </h1>
        <NoPermission
          onRetry={() => {
            void roles.refetch();
          }}
        />
      </section>
    );
  }

  return (
    <section aria-labelledby="titulo-papeis" className="mx-auto flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 id="titulo-papeis" className="text-2xl font-semibold">
          Papéis
        </h1>
        <p className="text-sm text-ink-muted">
          O que cada papel pode fazer. O mapa vale na hora, sem deploy — inclusive para papéis do
          sistema.
        </p>
      </header>

      {roles.isPending ? <p className="text-sm text-ink-muted">Carregando papéis…</p> : null}

      {roles.error !== null && roles.error !== undefined ? (
        <div
          role="alert"
          className="flex flex-col items-start gap-3 rounded-lg border border-danger/40 bg-danger/5 p-4 text-sm text-danger"
        >
          <p>{errorMessage(roles.error)}</p>
          <button
            type="button"
            onClick={() => {
              void roles.refetch();
            }}
            className={secondaryButtonClassName}
          >
            Tentar de novo
          </button>
        </div>
      ) : null}

      {roles.data !== undefined && roles.data.length === 0 ? (
        <p className="text-sm text-ink-muted">Nenhum papel no catálogo.</p>
      ) : null}

      {roles.data !== undefined && roles.data.length > 0 ? (
        <ul className="grid gap-4">
          {roles.data.map((role) => {
            const code = role.code ?? '';
            const name = role.name ?? code;
            const permissions = role.permissions ?? [];
            return (
              <li key={code === '' ? name : code}>
                <article className="rounded-lg border border-line bg-surface p-4">
                  <header className="flex flex-wrap items-start justify-between gap-3">
                    <div className="flex flex-col gap-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <h2 className="text-lg font-semibold text-ink">{name}</h2>
                        <code className="rounded bg-canvas px-1.5 py-0.5 text-xs text-ink-muted">
                          {code}
                        </code>
                        {role.system === true ? (
                          <span className="rounded-full border border-line px-2 py-0.5 text-xs text-ink-muted">
                            Do sistema
                          </span>
                        ) : null}
                      </div>
                      {role.description !== undefined && role.description !== '' ? (
                        <p className="text-sm text-ink-muted">{role.description}</p>
                      ) : null}
                    </div>

                    {canWrite && code !== '' ? (
                      <button
                        type="button"
                        aria-label={`Editar permissões de ${name}`}
                        onClick={() => setEditing(role)}
                        className={secondaryButtonClassName}
                      >
                        Editar permissões
                      </button>
                    ) : null}
                  </header>

                  <h3 className="mt-3 text-sm font-medium text-ink">
                    Permissões ({permissions.length})
                  </h3>
                  <div className="mt-2">
                    <PermissionChips permissions={permissions} />
                  </div>
                </article>
              </li>
            );
          })}
        </ul>
      ) : null}

      {/* O modal vive só enquanto aberto: cada abertura parte do papel clicado. */}
      {editing !== null ? (
        <RolePermissionsModal role={editing} onClose={() => setEditing(null)} />
      ) : null}
    </section>
  );
}
