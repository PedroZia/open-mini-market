/**
 * Sessão da retaguarda: o token vive em memória e é espelhado no `sessionStorage` — nunca no
 * `localStorage` (§10.3 do plano). O espelho existe só para o refresh da aba: fechar a aba
 * descarta o token, e quem diz se ele ainda vale é o `GET /auth/me` da inicialização.
 */

/** Chave do espelho em `sessionStorage`; exportada para o teste simular o refresh da aba. */
export const TOKEN_STORAGE_KEY = 'minimarket.auth.token';

/** Único lugar que guarda o token em memória: o client o lê a cada requisição. */
let token: string | null = null;

/** Token corrente em memória; `null` sem sessão. */
export function getToken(): string | null {
  return token;
}

/** Guarda o token do login (memória + espelho do refresh). */
export function storeToken(value: string): void {
  token = value;
  sessionStorage.setItem(TOKEN_STORAGE_KEY, value);
}

/**
 * Relê o espelho na inicialização e devolve o token já em memória, para o client autenticar o
 * `GET /auth/me` que valida a sessão. Sem espelho, a memória fica limpa.
 */
export function restoreToken(): string | null {
  const stored = sessionStorage.getItem(TOKEN_STORAGE_KEY);
  token = stored;
  return stored;
}

/** Esquece a sessão: memória e espelho. */
export function clearToken(): void {
  token = null;
  sessionStorage.removeItem(TOKEN_STORAGE_KEY);
}
