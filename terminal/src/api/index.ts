import { createApiClient } from '@minimarket/api-client';

import { getToken } from './session';
import { createTerminalApi, type TerminalApi } from './terminalApi';

/**
 * Client da API da TUI: **uma instância por processo**, montada antes do login — o token ainda não
 * existe e é lido da sessão em memória a cada requisição (`getToken`), nunca de arquivo ou variável
 * de ambiente. A base vem do ambiente da loja (`MINIMARKET_API_URL`) com o backend local de default.
 */

/** API injetada no shell; o teste do shell troca por um dublê. */
export const terminalApi: TerminalApi = createTerminalApi(
  createApiClient({
    baseUrl: process.env.MINIMARKET_API_URL ?? 'http://localhost:8080',
    token: getToken,
  }),
);
