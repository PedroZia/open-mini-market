import { createApiClient } from '@minimarket/api-client';

import { getToken } from './session';
import { createTerminalApi, type TerminalApi } from './terminalApi';

/**
 * Client da API da TUI: **uma instância por processo**, montada antes do login — o token ainda não
 * existe e é lido da sessão em memória a cada requisição (`getToken`), nunca de arquivo ou variável
 * de ambiente. A base vem de `MINIMARKET_API_URL` (passo 1119) com o default de desenvolvimento
 * (`http://localhost:8081`); para apontar para outro ambiente, veja `terminal/README.md`.
 */

/** Base da API: `MINIMARKET_API_URL` quando definida; sem ela, o backend local de dev (8081). */
export function resolveApiUrl(env: NodeJS.ProcessEnv = process.env): string {
  return env.MINIMARKET_API_URL ?? 'http://localhost:8081';
}

/** API injetada no shell; o teste do shell troca por um dublê. */
export const terminalApi: TerminalApi = createTerminalApi(
  createApiClient({
    baseUrl: resolveApiUrl(),
    token: getToken,
  }),
);
