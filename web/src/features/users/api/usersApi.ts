import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type UserResponse = components['schemas']['UserResponse'];
export type PageResponseUserResponse = components['schemas']['PageResponseUserResponse'];
export type CreateUserRequest = components['schemas']['CreateUserRequest'];
export type UpdateUserRequest = components['schemas']['UpdateUserRequest'];
export type ResetPasswordRequest = components['schemas']['ResetPasswordRequest'];
export type RoleResponse = components['schemas']['RoleResponse'];

/**
 * Busca, filtro de situação, ordenação e paginação de `GET /users` (passo 112), como o contrato as
 * aceita (§9.3). A whitelist de `sort` é `username`, `displayname` e `createdat` — a última não tem
 * coluna na tela porque o `UserResponse` não devolve `createdAt`.
 */
export interface UserQuery {
  /** Busca em usuário/nome de exibição; ausente ou em branco não vira parâmetro. */
  search?: string | undefined;
  /** Situação; ausente = ativos e desativados juntos. */
  active?: boolean | undefined;
  /** `campo,asc|desc` da whitelist do servidor (`username`, `displayname`, `createdat`). */
  sort?: string | undefined;
  /** Página 0-based, como o servidor devolve. */
  page?: number | undefined;
  /** Tamanho da página (teto de 100 no servidor). */
  size?: number | undefined;
}

const PATH = '/api/v1/users';
const ROLES_PATH = '/api/v1/roles';

/**
 * Lista paginada com busca, filtro de situação e ordenação. Só os parâmetros informados viajam na
 * URL — filtro vazio não vira `?search=` e um `sort` fora da whitelist nunca sai daqui (as colunas
 * do `DataTable` só declaram chaves aceitas).
 */
export function listUsers(query: UserQuery = {}): Promise<PageResponseUserResponse> {
  const params = new URLSearchParams();
  if (query.search !== undefined && query.search.trim() !== '') {
    params.set('search', query.search);
  }
  if (query.active !== undefined) {
    params.set('active', String(query.active));
  }
  if (query.sort !== undefined && query.sort !== '') {
    params.set('sort', query.sort);
  }
  if (query.page !== undefined) {
    params.set('page', String(query.page));
  }
  if (query.size !== undefined) {
    params.set('size', String(query.size));
  }
  const search = params.toString();
  return api.get<PageResponseUserResponse>(search === '' ? PATH : `${PATH}?${search}`);
}

/**
 * Catálogo de papéis (`GET /roles`, passo 114; exige `user.read`): é dele que saem as opções do
 * formulário de usuário. Array simples, sem paginação (§9.3), na ordem do servidor.
 */
export function listRoles(): Promise<RoleResponse[]> {
  return api.get<RoleResponse[]>(ROLES_PATH);
}

/**
 * Cadastra o usuário (`POST /users`, passo 107; exige `user.write`): o servidor normaliza o username,
 * recusa duplicidade, aplica a política de senha, atribui os papéis e audita a criação na mesma
 * transação. Devolve o registro como o banco o guardou.
 */
export function createUser(body: CreateUserRequest): Promise<UserResponse> {
  return api.post<UserResponse>(PATH, body);
}

/**
 * Substitui nome de exibição e papéis (`PUT /users/{id}`, passo 108; exige `user.write`): username e
 * senha não passam por aqui. Papel desconhecido → 400 `UNKNOWN_ROLE`; remover ADMIN do último ADMIN
 * ativo → 409 `CONFLICT`, sem gravar nada.
 */
export function updateUser(id: string, body: UpdateUserRequest): Promise<UserResponse> {
  return api.put<UserResponse>(`${PATH}/${id}`, body);
}

/**
 * Desativa o usuário (`POST /users/{id}/disable`, passo 112; exige `user.write`) sem apagar
 * histórico. Último ADMIN ativo → 409 `CONFLICT`; id desconhecido ou já desativado → 404
 * `USER_NOT_FOUND`.
 */
export function disableUser(id: string): Promise<UserResponse> {
  return api.post<UserResponse>(`${PATH}/${id}/disable`);
}

/** Reativa o usuário desativado (`POST /users/{id}/enable`, passo 112; exige `user.write`). */
export function enableUser(id: string): Promise<UserResponse> {
  return api.post<UserResponse>(`${PATH}/${id}/enable`);
}

/**
 * Reset de senha por ADMIN (`POST /users/{id}/password-reset`, passo 113; exige `user.write`): o
 * servidor guarda o hash da senha temporária, marca `mustChangePassword` e derruba as sessões do
 * usuário (passo 213) na mesma transação. Senha curta → 400 `VALIDATION_ERROR` com `errors[]`.
 */
export function resetPassword(id: string, body: ResetPasswordRequest): Promise<UserResponse> {
  return api.post<UserResponse>(`${PATH}/${id}/password-reset`, body);
}

/**
 * Corta todas as sessões vivas do usuário (`DELETE /users/{id}/sessions`, passo 213; exige
 * `user.session.revoke`, que só ADMIN tem) e responde 204 sem corpo. Id desconhecido ou
 * soft-deletado → 404 `USER_NOT_FOUND`.
 */
export function revokeSessions(id: string): Promise<void> {
  return api.delete<void>(`${PATH}/${id}/sessions`);
}
