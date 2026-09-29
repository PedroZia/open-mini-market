import { createApiClient } from '@minimarket/api-client';

/**
 * Client único da retaguarda (§10.2), com os tipos vindos do contrato OpenAPI. Em dev o caminho é
 * relativo porque o Vite faz proxy de `/api` para `http://localhost:8081`; o token provider e o
 * tratamento de 401 chegam no 1202 (auth).
 */
export const api = createApiClient({ baseUrl: '' });
