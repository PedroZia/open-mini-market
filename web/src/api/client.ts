import { ApiError, createApiClient, type ApiClient, type RequestOptions } from '@minimarket/api-client';
import { getToken } from '../features/auth/session';

/**
 * Client único da retaguarda (§10.2), com os tipos vindos do contrato OpenAPI. Em dev o caminho é
 * relativo porque o Vite faz proxy de `/api` para `http://localhost:8081`.
 *
 * O token vem de `features/auth/session` a cada requisição (ele nasce no login e some no logout) e
 * o 401 de requisição autenticada avisa o `AuthProvider`, que derruba a sessão — a guarda de rota
 * leva ao login. O 401 do próprio login é anônimo (não levou token) e não passa por aqui: é
 * credencial inválida, e a tela mostra a mensagem.
 */

type UnauthorizedHandler = () => void;

let unauthorizedHandler: UnauthorizedHandler | null = null;

/** Registra quem reage ao 401 de chamada autenticada; `null` desliga (desmontagem do provider). */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

const baseClient = createApiClient({
  baseUrl: '',
  token: getToken,
  // `globalThis.fetch` resolvido na chamada (e não na criação do client) para o stub dos testes
  // valer sem recriar o client.
  fetch: (input, init) => globalThis.fetch(input, init),
});

/** Pergunta "esta requisição levou token?" **antes** de enviar — é o que separa sessão de login. */
function withUnauthorizedHandler(client: ApiClient): ApiClient {
  function guard<T>(sentAuthenticated: boolean, request: () => Promise<T>): Promise<T> {
    return request().catch((error: unknown) => {
      if (sentAuthenticated && error instanceof ApiError && error.status === 401) {
        unauthorizedHandler?.();
      }
      throw error;
    });
  }

  return {
    get: <T>(path: string, options?: RequestOptions) =>
      guard(getToken() !== null, () => client.get<T>(path, options)),
    post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      guard(getToken() !== null, () => client.post<T>(path, body, options)),
    put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      guard(getToken() !== null, () => client.put<T>(path, body, options)),
    patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      guard(getToken() !== null, () => client.patch<T>(path, body, options)),
    delete: <T>(path: string, options?: RequestOptions) =>
      guard(getToken() !== null, () => client.delete<T>(path, options)),
  };
}

export const api = withUnauthorizedHandler(baseClient);
