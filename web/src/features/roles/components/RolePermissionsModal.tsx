import { ApiError } from '@minimarket/api-client';
import { useMemo, useState } from 'react';
import { usePermission } from '../../../shared/lib/permissions';
import { errorMessage } from '../../../shared/lib/problem';
import { Modal } from '../../../shared/ui/Modal';
import type { RoleResponse } from '../api/rolesApi';
import { useReplaceRolePermissions, useRoles } from '../hooks/useRoles';
import { permissionGroups } from '../lib/permissionGroups';

/**
 * Edição do mapa de permissões de um papel (1208b). Os checkboxes saem do catálogo derivado da
 * união das permissões de `GET /roles` — não há lista fixa no cliente, então uma permissão nova do
 * servidor aparece aqui sem deploy do web. Papel `system` também é editável: o selo só informa que
 * o papel nasce com o sistema.
 *
 * A tela é a de `PUT /roles/{code}/permissions`: manda o conjunto completo marcado. Erro do
 * servidor fica no banner do próprio modal (o toast global é silenciado pela mutation): `400
 * UNKNOWN_PERMISSION` — catálogo velho na tela — ganha mensagem própria, nunca tela branca.
 */

/** Mensagem própria do 400 que o servidor devolve quando o corpo traz código fora do catálogo. */
const UNKNOWN_PERMISSION_MESSAGE =
  'Alguma permissão enviada não existe no catálogo do servidor. Atualize a lista e tente de novo.';

/** Mensagem do erro para o banner; o `UNKNOWN_PERMISSION` tem texto mais útil que o `detail`. */
function saveErrorMessage(failure: unknown): string {
  if (failure instanceof ApiError && failure.code === 'UNKNOWN_PERMISSION') {
    return UNKNOWN_PERMISSION_MESSAGE;
  }
  return errorMessage(failure);
}

export interface RolePermissionsModalProps {
  /** Papel em edição, como veio de `GET /roles`; o `code` é o do path do PUT. */
  role: RoleResponse;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function RolePermissionsModal({ role, onClose }: RolePermissionsModalProps) {
  const code = role.code ?? '';
  const name = role.name ?? code;
  const catalog = useRoles();
  const replace = useReplaceRolePermissions();
  const canWrite = usePermission('role.write');
  const [selected, setSelected] = useState<ReadonlySet<string>>(
    // O modal é montado a cada abertura: o que chegou do servidor é o ponto de partida.
    () => new Set(role.permissions ?? []),
  );
  const [failure, setFailure] = useState<unknown>(null);

  const groups = useMemo(() => permissionGroups(catalog.data ?? []), [catalog.data]);

  function toggle(permissionCode: string): void {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(permissionCode)) {
        next.delete(permissionCode);
      } else {
        next.add(permissionCode);
      }
      return next;
    });
  }

  function save(): void {
    setFailure(null);
    replace.mutate(
      // Conjunto completo, em ordem estável: o PUT substitui, não soma.
      { code, body: { permissions: [...selected].sort() } },
      { onSuccess: onClose, onError: setFailure },
    );
  }

  return (
    <Modal open title={`Permissões de ${name}`} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          save();
        }}
      >
        <p className="text-sm text-ink-muted">
          Marque o que o papel pode fazer. O mapa vale na hora, inclusive para papéis do sistema.
        </p>

        {catalog.isPending ? (
          <p className="text-sm text-ink-muted">Carregando catálogo de permissões…</p>
        ) : null}
        {catalog.error !== null && catalog.error !== undefined ? (
          <p role="alert" className="rounded-md border border-danger/40 bg-danger/5 px-3 py-2 font-medium text-danger">
            {errorMessage(catalog.error)}
          </p>
        ) : null}
        {groups.length === 0 &&
        catalog.isPending === false &&
        (catalog.error === null || catalog.error === undefined) ? (
          <p className="text-sm text-ink-muted">Nenhuma permissão no catálogo.</p>
        ) : null}

        {groups.map((group) => (
          <fieldset key={group.prefix} className="rounded-md border border-line px-3 py-2">
            <legend className="px-1 font-mono text-xs text-ink-muted">{group.prefix}</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {group.codes.map((permissionCode) => (
                <label
                  key={permissionCode}
                  className="flex items-center gap-2 font-mono text-xs text-ink"
                >
                  <input
                    type="checkbox"
                    checked={selected.has(permissionCode)}
                    onChange={() => {
                      toggle(permissionCode);
                    }}
                    className="size-4"
                  />
                  {permissionCode}
                </label>
              ))}
            </div>
          </fieldset>
        ))}

        {failure !== null && failure !== undefined ? (
          <p
            role="alert"
            className="rounded-md border border-danger/40 bg-danger/5 px-3 py-2 font-medium text-danger"
          >
            {saveErrorMessage(failure)}
          </p>
        ) : null}

        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={secondaryButtonClassName}>
            Cancelar
          </button>
          {canWrite ? (
            <button
              type="submit"
              disabled={replace.isPending}
              aria-busy={replace.isPending}
              className={primaryButtonClassName}
            >
              Salvar
            </button>
          ) : null}
        </div>
      </form>
    </Modal>
  );
}
