import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Contrato do login/logout/sessão (`/api/v1/auth/*`, passos 205–208): só o client tipado. */
export type LoginResponse = components['schemas']['LoginResponse'];
export type CurrentSessionResponse = components['schemas']['CurrentSessionResponse'];

/**
 * Autentica com usuário e senha (`POST /auth/login`, 200). Sem o header `X-Client`: o default do
 * servidor é `WEB` (205) — a retaguarda não é a TUI. A falha sobe como `ApiError` para a tela
 * decidir a mensagem pelo `code` (`INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `RATE_LIMITED`...).
 */
export function loginRequest(username: string, password: string): Promise<LoginResponse> {
  const body: components['schemas']['LoginRequest'] = { username, password };
  return api.post<LoginResponse>('/api/v1/auth/login', body);
}

/**
 * Sessão corrente (`GET /auth/me`, 207): é o que valida o token no refresh e relê o RBAC do
 * servidor — quem manda nas permissões é ele, não o que ficou guardado no cliente.
 */
export function fetchCurrentSession(): Promise<CurrentSessionResponse> {
  return api.get<CurrentSessionResponse>('/api/v1/auth/me');
}

/** Revoga a sessão (`POST /auth/logout`, 204); o chamador trata a falha como melhor esforço. */
export function revokeSession(): Promise<void> {
  return api.post<void>('/api/v1/auth/logout');
}
