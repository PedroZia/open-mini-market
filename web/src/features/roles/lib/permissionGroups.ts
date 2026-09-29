import type { RoleResponse } from '../api/rolesApi';

/**
 * Catálogo de permissões derivado de `GET /roles`: cada papel traz o seu conjunto e a união de
 * todos é tudo que existe (o ADMIN efetivo tem todas). Não há endpoint de catálogo separado — se
 * existisse, seria outra fonte de verdade a manter em sincronia.
 */

/** Grupo do catálogo; o prefixo é o que vem antes do `.` (`product`, `sale`, `cash`...). */
export interface PermissionGroup {
  prefix: string;
  /** Códigos do grupo, sem repetição e em ordem alfabética. */
  codes: string[];
}

/** Prefixo de navegação de um código; código sem `.` vira grupo dele mesmo. */
function prefixOf(code: string): string {
  const dot = code.indexOf('.');
  return dot === -1 ? code : code.slice(0, dot);
}

/**
 * Une as permissões de todos os papéis, agrupa pelo prefixo e ordena — prefixos e códigos em ordem
 * alfabética para a tela não depender da ordem em que o servidor devolveu os papéis.
 */
export function permissionGroups(roles: readonly RoleResponse[]): PermissionGroup[] {
  const byPrefix = new Map<string, Set<string>>();

  for (const role of roles) {
    for (const code of role.permissions ?? []) {
      const prefix = prefixOf(code);
      const codes = byPrefix.get(prefix) ?? new Set<string>();
      codes.add(code);
      byPrefix.set(prefix, codes);
    }
  }

  return [...byPrefix.entries()]
    .map(([prefix, codes]) => ({ prefix, codes: [...codes].sort() }))
    .sort((left, right) => left.prefix.localeCompare(right.prefix));
}
