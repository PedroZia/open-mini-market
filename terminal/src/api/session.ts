/**
 * Sessão do operador na TUI: o token vive **só em memória** — nada em disco, nada em log, nada na
 * tela (§9.2). O client da API recebe `getToken` como provedor e lê o valor a cada requisição, então
 * login e logout trocam o token sem recriar o client (que nasce antes de existir sessão).
 */

let token: string | null = null;

/** Token da sessão corrente, ou `null` antes do login (a requisição sai anônima). */
export function getToken(): string | null {
  return token;
}

/** Guarda o token devolvido pelo login. */
export function setToken(value: string): void {
  token = value;
}

/** Esquece o token: logout, sessão expirada ou caixa fechado. */
export function clearToken(): void {
  token = null;
}
