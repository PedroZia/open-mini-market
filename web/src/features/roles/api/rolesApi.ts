import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type RoleResponse = components['schemas']['RoleResponse'];
export type ReplaceRolePermissionsRequest = components['schemas']['ReplaceRolePermissionsRequest'];

const PATH = '/api/v1/roles';

/**
 * Catálogo de papéis (`GET /roles`, passo 114; exige `user.read`): array sem paginação, na ordem do
 * servidor, com as permissões de cada papel. É dele que sai o catálogo da tela — a união das
 * permissões de todos os papéis, já que o ADMIN efetivo tem todas.
 */
export function listRoles(): Promise<RoleResponse[]> {
  return api.get<RoleResponse[]>(PATH);
}

/**
 * Substitui o conjunto de permissões do papel (`PUT /roles/{code}/permissions`, passo 114; exige
 * `role.write`, que só o ADMIN tem): o corpo manda a lista completa, não o delta. Papel `system`
 * também é editável — o mapa muda sem deploy, que é o propósito da tabela. Código fora do catálogo
 * → 400 `UNKNOWN_PERMISSION` sem gravar nada; papel inexistente → 404 `ROLE_NOT_FOUND`.
 */
export function replaceRolePermissions(
  code: string,
  body: ReplaceRolePermissionsRequest,
): Promise<RoleResponse> {
  return api.put<RoleResponse>(`${PATH}/${code}/permissions`, body);
}
